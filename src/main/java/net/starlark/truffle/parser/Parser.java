package net.starlark.truffle.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.nodes.binary.*;
import net.starlark.truffle.nodes.literal.*;
import net.starlark.truffle.nodes.local.ReadLocalVariableNodeGen;
import net.starlark.truffle.nodes.local.WriteLocalVariableNodeGen;
import net.starlark.truffle.nodes.controlflow.IfNode;
import net.starlark.truffle.nodes.logical.AndNode;
import net.starlark.truffle.nodes.logical.NotNodeGen;
import net.starlark.truffle.nodes.logical.OrNode;
import net.starlark.truffle.nodes.statement.AssignmentNode;
import net.starlark.truffle.nodes.statement.BlockNode;
import net.starlark.truffle.nodes.statement.ExpressionStatementNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recursive descent parser for Starlark (Phase 1: basic expressions and assignments). */
public final class Parser {
  private final Lexer lexer;
  private Token current;
  private final FrameDescriptor.Builder frameBuilder;
  private final Map<String, Integer> locals;

  public Parser(Lexer lexer, FrameDescriptor.Builder frameBuilder) {
    this.lexer = lexer;
    this.frameBuilder = frameBuilder;
    this.locals = new HashMap<>();
    advance();
  }

  /** Parse a complete file and return the root statement node. */
  public StatementNode parseFile() {
    List<StatementNode> statements = new ArrayList<>();

    while (current.kind != TokenKind.EOF) {
      if (current.kind == TokenKind.NEWLINE) {
        advance();
        continue;
      }
      statements.add(parseStatement());
    }

    return new BlockNode(statements.toArray(new StatementNode[0]));
  }

  /** Parse a single statement. */
  private StatementNode parseStatement() {
    // If statement
    if (current.kind == TokenKind.IF) {
      return parseIfStatement();
    }

    // Simple assignment: IDENTIFIER = expression
    if (current.kind == TokenKind.IDENTIFIER) {
      // Save position to check for assignment
      int startPos = current.start;
      String name = (String) current.value;
      advance();

      if (current.kind == TokenKind.EQUALS) {
        // It's an assignment
        advance(); // consume =
        ExpressionNode value = parseExpression();
        consumeNewlineOrEof();

        int slot = getOrCreateLocal(name);
        ExpressionNode writeNode = WriteLocalVariableNodeGen.create(value, slot);
        return new AssignmentNode(writeNode);
      }

      // Not a simple assignment, treat as expression statement
      // We already consumed the identifier, so create a read node
      int slot = getOrCreateLocal(name);
      ExpressionNode expr = ReadLocalVariableNodeGen.create(slot);

      // Continue parsing the rest of the expression (e.g., operators, calls, etc.)
      expr = parseExpressionContinuation(expr);
      consumeNewlineOrEof();
      return new ExpressionStatementNode(expr);
    }

    ExpressionNode expr = parseExpression();
    consumeNewlineOrEof();
    return new ExpressionStatementNode(expr);
  }

  /** Parse the continuation of an expression after we've already parsed the first part. */
  private ExpressionNode parseExpressionContinuation(ExpressionNode left) {
    // Check for binary operators
    if (isComparisonOp(current.kind)) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parseAdditiveExpression();
      return createComparisonNode(op, left, right);
    }

    if (current.kind == TokenKind.PLUS || current.kind == TokenKind.MINUS) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parseMultiplicativeExpression();
      left = createArithmeticNode(op, left, right);
      return parseExpressionContinuation(left);
    }

    if (current.kind == TokenKind.STAR || current.kind == TokenKind.SLASH
        || current.kind == TokenKind.SLASH_SLASH || current.kind == TokenKind.PERCENT) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parsePrimaryExpression();
      left = createArithmeticNode(op, left, right);
      return parseExpressionContinuation(left);
    }

    return left;
  }

  /** Parse if/elif/else statement. */
  private StatementNode parseIfStatement() {
    List<ExpressionNode> conditions = new ArrayList<>();
    List<StatementNode> bodies = new ArrayList<>();

    // Parse if block
    expect(TokenKind.IF);
    conditions.add(parseExpression());
    expect(TokenKind.COLON);
    consumeNewlineOrEof();
    bodies.add(parseSimpleBlock());

    // Parse elif blocks
    while (current.kind == TokenKind.ELIF) {
      advance();
      conditions.add(parseExpression());
      expect(TokenKind.COLON);
      consumeNewlineOrEof();
      bodies.add(parseSimpleBlock());
    }

    // Parse optional else block
    StatementNode elseBlock = null;
    if (current.kind == TokenKind.ELSE) {
      advance();
      expect(TokenKind.COLON);
      consumeNewlineOrEof();
      elseBlock = parseSimpleBlock();
    }

    return new IfNode(
        conditions.toArray(new ExpressionNode[0]),
        bodies.toArray(new StatementNode[0]),
        elseBlock);
  }

  /** Parse a simple block of statements (for Phase 2, just a single statement). */
  private StatementNode parseSimpleBlock() {
    // For Phase 2, simplified: just parse a single statement
    // Phase 3 will add proper indentation-based blocks
    return parseStatement();
  }

  /** Parse an expression. */
  private ExpressionNode parseExpression() {
    return parseOrExpression();
  }

  /** Parse 'or' expression (lowest precedence). */
  private ExpressionNode parseOrExpression() {
    ExpressionNode left = parseAndExpression();

    while (current.kind == TokenKind.OR) {
      advance();
      ExpressionNode right = parseAndExpression();
      left = OrNode.create(left, right);
    }

    return left;
  }

  /** Parse 'and' expression. */
  private ExpressionNode parseAndExpression() {
    ExpressionNode left = parseNotExpression();

    while (current.kind == TokenKind.AND) {
      advance();
      ExpressionNode right = parseNotExpression();
      left = AndNode.create(left, right);
    }

    return left;
  }

  /** Parse 'not' expression. */
  private ExpressionNode parseNotExpression() {
    if (current.kind == TokenKind.NOT) {
      advance();
      ExpressionNode operand = parseNotExpression();  // right-associative
      return NotNodeGen.create(operand);
    }
    return parseComparisonExpression();
  }

  /** Parse comparison expression: <, >, <=, >=, ==, !=, in, not in */
  private ExpressionNode parseComparisonExpression() {
    ExpressionNode left = parseAdditiveExpression();

    while (isComparisonOp(current.kind)) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parseAdditiveExpression();
      left = createComparisonNode(op, left, right);
    }

    return left;
  }

  /** Parse additive expression: + - */
  private ExpressionNode parseAdditiveExpression() {
    ExpressionNode left = parseMultiplicativeExpression();

    while (current.kind == TokenKind.PLUS || current.kind == TokenKind.MINUS) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parseMultiplicativeExpression();
      left = createArithmeticNode(op, left, right);
    }

    return left;
  }

  /** Parse multiplicative expression: * / // % */
  private ExpressionNode parseMultiplicativeExpression() {
    ExpressionNode left = parsePrimaryExpression();

    while (current.kind == TokenKind.STAR || current.kind == TokenKind.SLASH
        || current.kind == TokenKind.SLASH_SLASH || current.kind == TokenKind.PERCENT) {
      TokenKind op = current.kind;
      advance();
      ExpressionNode right = parsePrimaryExpression();
      left = createArithmeticNode(op, left, right);
    }

    return left;
  }

  /** Parse primary expression: literals, identifiers, parenthesized expressions */
  private ExpressionNode parsePrimaryExpression() {
    switch (current.kind) {
      case INT:
        long intValue = (long) current.value;
        advance();
        return new IntLiteralNode(intValue);

      case FLOAT:
        double floatValue = (double) current.value;
        advance();
        return new FloatLiteralNode(floatValue);

      case STRING:
        String stringValue = (String) current.value;
        advance();
        return new StringLiteralNode(stringValue);

      case TRUE:
        advance();
        return new BooleanLiteralNode(true);

      case FALSE:
        advance();
        return new BooleanLiteralNode(false);

      case NONE:
        advance();
        return NoneLiteralNode.INSTANCE;

      case IDENTIFIER:
        String name = (String) current.value;
        advance();
        int slot = getOrCreateLocal(name);
        return ReadLocalVariableNodeGen.create(slot);

      case LPAREN:
        advance();
        ExpressionNode expr = parseExpression();
        expect(TokenKind.RPAREN);
        return expr;

      default:
        throw new ParseException("Unexpected token: " + current.kind, current);
    }
  }

  /** Create a binary arithmetic/comparison node based on operator. */
  private ExpressionNode createArithmeticNode(TokenKind op, ExpressionNode left, ExpressionNode right) {
    switch (op) {
      case PLUS:
        return AddNodeGen.create(left, right);
      case MINUS:
        return SubtractNodeGen.create(left, right);
      case STAR:
        return MultiplyNodeGen.create(left, right);
      case SLASH:
        return DivideNodeGen.create(left, right);
      case SLASH_SLASH:
        return FloorDivideNodeGen.create(left, right);
      case PERCENT:
        return ModuloNodeGen.create(left, right);
      default:
        throw new ParseException("Unknown arithmetic operator: " + op, current);
    }
  }

  /** Create a comparison node based on operator. */
  private ExpressionNode createComparisonNode(TokenKind op, ExpressionNode left, ExpressionNode right) {
    switch (op) {
      case EQUALS_EQUALS:
        return EqualNodeGen.create(left, right);
      case NOT_EQUALS:
        return NotEqualNodeGen.create(left, right);
      case LESS:
        return LessThanNodeGen.create(left, right);
      case LESS_EQUALS:
        return LessOrEqualNodeGen.create(left, right);
      case GREATER:
        return GreaterThanNodeGen.create(left, right);
      case GREATER_EQUALS:
        return GreaterOrEqualNodeGen.create(left, right);
      default:
        throw new ParseException("Unknown comparison operator: " + op, current);
    }
  }

  /** Check if a token is a comparison operator. */
  private boolean isComparisonOp(TokenKind kind) {
    return kind == TokenKind.EQUALS_EQUALS || kind == TokenKind.NOT_EQUALS
        || kind == TokenKind.LESS || kind == TokenKind.LESS_EQUALS
        || kind == TokenKind.GREATER || kind == TokenKind.GREATER_EQUALS;
  }

  /** Get or create a frame slot for a local variable. */
  private int getOrCreateLocal(String name) {
    return locals.computeIfAbsent(name, n -> frameBuilder.addSlots(1, FrameSlotKind.Illegal));
  }

  /** Advance to the next token. */
  private void advance() {
    current = lexer.nextToken();
  }

  /** Expect a specific token kind and consume it. */
  private void expect(TokenKind kind) {
    if (current.kind != kind) {
      throw new ParseException("Expected " + kind + " but got " + current.kind, current);
    }
    advance();
  }

  /** Consume a newline or EOF. */
  private void consumeNewlineOrEof() {
    if (current.kind == TokenKind.NEWLINE) {
      advance();
    } else if (current.kind != TokenKind.EOF) {
      throw new ParseException("Expected newline or EOF but got " + current.kind, current);
    }
  }

  /** Exception thrown during parsing. */
  public static class ParseException extends RuntimeException {
    private final Token token;

    public ParseException(String message, Token token) {
      super(message + " at " + token.start);
      this.token = token;
    }

    public Token getToken() {
      return token;
    }
  }
}
