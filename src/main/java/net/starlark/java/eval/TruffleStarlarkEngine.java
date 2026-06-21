// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.BinaryOperatorExpression;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.ConditionalExpression;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FloatLiteral;
import net.starlark.java.syntax.FlowStatement;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.IfStatement;
import net.starlark.java.syntax.IndexExpression;
import net.starlark.java.syntax.IntLiteral;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.ReturnStatement;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.TokenKind;
import net.starlark.java.syntax.TypeTable;
import net.starlark.java.syntax.UnaryOperatorExpression;

/**
 * An experimental {@link StarlarkEngine} that compiles Starlark function bodies to Truffle {@link
 * RootCallTarget}s. Selected via {@code --experimental_starlark_engine=truffle}.
 *
 * <p>This is an incremental port: it natively handles a growing subset of node types and <b>falls
 * back to the tree-walking {@link Eval} evaluator</b> for everything else (and always for top-level
 * file bodies, which carry special export semantics). Every native node reuses the exact same
 * primitives as {@link Eval} (e.g. {@link EvalUtils#binaryOp} and {@link Starlark#positionalOnlyCall})
 * so behavior is identical; the {@code StarlarkEngineConsistencyTest} differential harness enforces
 * this. Move a node type from "unsupported" to a real Truffle node to grow native coverage.
 *
 * <p>Supported today: literals, identifiers (all scopes), simple and augmented assignment (to
 * identifiers, index and dot targets), {@code if}, {@code for}/{@code break}/{@code continue},
 * {@code return}, expression statements, binary (incl. {@code and}/{@code or}) and unary operators,
 * conditional expressions, list/tuple and dict literals, index and dot reads, and positional-only
 * calls. Not yet: comprehensions, slices, tuple-unpacking assignment, lambda/def, and {@code *args}
 * /keyword calls - these fall back to {@link Eval}.
 *
 * <p>Currently the nodes operate over the existing {@link StarlarkThread.Frame} (boxed {@code
 * Object[]} locals) rather than an unboxed Truffle {@code VirtualFrame}, so the structure is in
 * place but the partial-evaluation speedups (typed frame slots, inline caches, {@code @ExplodeLoop})
 * are still TODO. The point of this slice is the end-to-end wiring and correctness.
 */
final class TruffleStarlarkEngine implements StarlarkEngine {

  static {
    StarlarkEngine.register("truffle", new TruffleStarlarkEngine());
  }

  /** Sentinel cache value meaning "this function uses a construct we don't compile; use Eval". */
  private static final Object FALLBACK = new Object();

  /** Instrumentation: number of function bodies executed natively (read by tests). */
  static final AtomicLong NATIVE_CALLS = new AtomicLong();

  /** With -Dstarlark.truffle.debug=true, log the active Truffle runtime once (is JVMCI/Graal on?). */
  private static final boolean DEBUG = Boolean.getBoolean("starlark.truffle.debug");

  private static volatile boolean diagnosed = false;

  /** Compiled bodies, keyed by the resolved function (shared across closure instances). */
  private final ConcurrentHashMap<Resolver.Function, Object> compiled = new ConcurrentHashMap<>();

  @Override
  public Object execFunctionBody(StarlarkThread.Frame fr, List<Statement> statements)
      throws EvalException, InterruptedException {
    fr.thread.checkInterrupt();
    if (DEBUG && !diagnosed) {
      diagnosed = true;
      System.err.println(
          "[starlark-truffle] Truffle runtime: "
              + Truffle.getRuntime().getName()
              + " ["
              + Truffle.getRuntime().getClass().getName()
              + "]");
    }
    StarlarkFunction fn = (StarlarkFunction) fr.fn;
    // Top-level file bodies have export side effects handled by the tree-walker; never compile them.
    if (fn.isToplevel()) {
      return StarlarkEngine.TREE_WALKING.execFunctionBody(fr, statements);
    }
    Object target = compiled.computeIfAbsent(fn.rfn, TruffleStarlarkEngine::tryCompile);
    if (target == FALLBACK) {
      return StarlarkEngine.TREE_WALKING.execFunctionBody(fr, statements);
    }
    try {
      return ((RootCallTarget) target).call(fr);
    } catch (HostException e) {
      throw e.rethrow();
    }
  }

  /** Attempts to compile a function body to a CallTarget; returns {@link #FALLBACK} if unsupported. */
  private static Object tryCompile(Resolver.Function rfn) {
    try {
      StmtNode[] body = compileBlock(rfn.getBody());
      return new BodyRootNode(body).getCallTarget();
    } catch (RuntimeException | StackOverflowError e) {
      // Unsupported (a RuntimeException) and any other compile problem fall back to the tree-walker.
      return FALLBACK;
    }
  }

  // ---- compilation (AST -> Truffle nodes), once per function ----

  /** Thrown when a construct is not yet handled natively, triggering Eval fallback. */
  private static final class Unsupported extends RuntimeException {
    Unsupported(String what) {
      super(what);
    }
  }

  private static StmtNode[] compileBlock(List<Statement> stmts) {
    StmtNode[] out = new StmtNode[stmts.size()];
    for (int i = 0; i < stmts.size(); i++) {
      out[i] = compileStmt(stmts.get(i));
    }
    return out;
  }

  private static StmtNode compileStmt(Statement s) {
    if (s instanceof ReturnStatement ret) {
      Expression r = ret.getResult();
      return new ReturnNode(r == null ? null : compileExpr(r));
    } else if (s instanceof IfStatement ifs) {
      return new IfNode(
          compileExpr(ifs.getCondition()),
          compileBlock(ifs.getThenBlock()),
          ifs.getElseBlock() == null ? null : compileBlock(ifs.getElseBlock()));
    } else if (s instanceof ForStatement fors) {
      if (!(fors.getVars() instanceof Identifier var)) {
        throw new Unsupported("for with non-identifier target"); // tuple unpacking
      }
      return new ForNode(
          assignableBinding(var),
          compileExpr(fors.getCollection()),
          fors.getCollection().getStartLocation(),
          fors.getStartLocation(),
          compileBlock(fors.getBody()));
    } else if (s instanceof FlowStatement flow) {
      return new FlowNode(flow.getFlowKind());
    } else if (s instanceof AssignmentStatement assign) {
      return assign.isAugmented() ? compileAugmentedAssign(assign) : compileSimpleAssign(assign);
    } else if (s instanceof ExpressionStatement es) {
      return new ExprStmtNode(compileExpr(es.getExpression()));
    }
    throw new Unsupported("statement: " + s.kind());
  }

  private static StmtNode compileSimpleAssign(AssignmentStatement node) {
    Expression lhs = node.getLHS();
    ExprNode rhs = compileExpr(node.getRHS());
    Location opLoc = node.getOperatorLocation();
    if (lhs instanceof Identifier id) {
      return new AssignIdentNode(assignableBinding(id), rhs, opLoc);
    } else if (lhs instanceof IndexExpression index) {
      return new AssignIndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), rhs, opLoc);
    } else if (lhs instanceof DotExpression dot) {
      return new AssignDotNode(
          compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation(), rhs, opLoc);
    }
    throw new Unsupported("assignment target: " + lhs.kind()); // e.g. tuple unpacking
  }

  private static StmtNode compileAugmentedAssign(AssignmentStatement node) {
    Expression lhs = node.getLHS();
    ExprNode rhs = compileExpr(node.getRHS());
    TokenKind op = node.getOperator();
    Location opLoc = node.getOperatorLocation();
    if (lhs instanceof Identifier id) {
      return new AugAssignIdentNode(assignableBinding(id), new IdentifierNode(id), op, rhs, opLoc);
    } else if (lhs instanceof IndexExpression index) {
      return new AugAssignIndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), op, rhs, opLoc);
    } else if (lhs instanceof DotExpression dot) {
      return new AugAssignDotNode(
          compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation(), op, rhs, opLoc);
    }
    throw new Unsupported("augmented assignment target: " + lhs.kind());
  }

  /** Returns the binding for an assignable identifier, or fails over to Eval for read-only scopes. */
  private static Resolver.Binding assignableBinding(Identifier id) {
    Resolver.Binding bind = id.getBinding();
    switch (bind.getScope()) {
      case LOCAL:
      case CELL:
      case GLOBAL:
        return bind;
      default:
        throw new Unsupported("assignment to " + bind.getScope() + " variable");
    }
  }

  private static ExprNode compileExpr(Expression e) {
    if (e instanceof IntLiteral lit) {
      Number n = lit.getValue();
      StarlarkInt v =
          n instanceof Integer i
              ? StarlarkInt.of(i)
              : n instanceof Long l ? StarlarkInt.of(l) : StarlarkInt.of((BigInteger) n);
      return new ConstNode(v);
    } else if (e instanceof StringLiteral lit) {
      return new ConstNode(lit.getValue());
    } else if (e instanceof FloatLiteral lit) {
      return new ConstNode(StarlarkFloat.of(lit.getValue()));
    } else if (e instanceof Identifier id) {
      return new IdentifierNode(id);
    } else if (e instanceof BinaryOperatorExpression bin) {
      return new BinaryNode(
          bin.getOperator(),
          bin.getOperatorLocation(),
          compileExpr(bin.getX()),
          compileExpr(bin.getY()));
    } else if (e instanceof UnaryOperatorExpression un) {
      return new UnaryNode(un.getOperator(), un.getStartLocation(), compileExpr(un.getX()));
    } else if (e instanceof ConditionalExpression cond) {
      return new CondNode(
          compileExpr(cond.getCondition()),
          compileExpr(cond.getThenCase()),
          compileExpr(cond.getElseCase()));
    } else if (e instanceof IndexExpression index) {
      return new IndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), index.getLbracketLocation());
    } else if (e instanceof DotExpression dot) {
      return new DotNode(compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation());
    } else if (e instanceof ListExpression list) {
      ExprNode[] elems = new ExprNode[list.getElements().size()];
      for (int i = 0; i < elems.length; i++) {
        elems[i] = compileExpr(list.getElements().get(i));
      }
      return new ListNode(elems, list.isTuple());
    } else if (e instanceof DictExpression dict) {
      List<DictExpression.Entry> entries = dict.getEntries();
      ExprNode[] keys = new ExprNode[entries.size()];
      ExprNode[] values = new ExprNode[entries.size()];
      Location[] colons = new Location[entries.size()];
      for (int i = 0; i < entries.size(); i++) {
        keys[i] = compileExpr(entries.get(i).getKey());
        values[i] = compileExpr(entries.get(i).getValue());
        colons[i] = entries.get(i).getColonLocation();
      }
      return new DictNode(keys, values, colons);
    } else if (e instanceof CallExpression call) {
      if (!(call.getFunction() instanceof Identifier)) {
        throw new Unsupported("call to non-identifier"); // method calls need self-binding
      }
      List<Argument> args = call.getArguments();
      ExprNode[] argNodes = new ExprNode[args.size()];
      for (int i = 0; i < args.size(); i++) {
        Argument a = args.get(i);
        if (!(a instanceof Argument.Positional)) {
          throw new Unsupported("non-positional argument");
        }
        argNodes[i] = compileExpr(a.getValue());
      }
      return new CallNode(compileExpr(call.getFunction()), argNodes);
    }
    throw new Unsupported("expression: " + e.kind());
  }

  private static TokenKind execBlock(StmtNode[] body, StarlarkThread.Frame fr)
      throws EvalException, InterruptedException {
    for (StmtNode s : body) {
      TokenKind flow = s.exec(fr);
      if (flow != TokenKind.PASS) {
        return flow;
      }
    }
    return TokenKind.PASS;
  }

  /** Mirrors {@link Eval}'s {@code assignIdentifier}. */
  private static void assignIdentifier(StarlarkThread.Frame fr, Resolver.Binding bind, Object value) {
    switch (bind.getScope()) {
      case LOCAL -> fr.locals[bind.getIndex()] = value;
      case CELL -> ((StarlarkFunction.Cell) fr.locals[bind.getIndex()]).x = value;
      case GLOBAL -> {
        StarlarkFunction fn = (StarlarkFunction) fr.fn;
        fn.setGlobal(bind.getIndex(), value);
        TypeTable typeTable = fn.getTypeTable();
        if (typeTable != null) {
          fn.setGlobalDeclaredType(bind.getIndex(), typeTable.getGlobalDeclaredType(bind));
        }
      }
      default -> throw new IllegalStateException(bind.getScope().toString());
    }
  }

  /** Mirrors {@link Eval}'s {@code inplaceBinaryOp} (in-place {@code +=} etc. for collections). */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object inplaceBinaryOp(StarlarkThread.Frame fr, TokenKind op, Object x, Object y)
      throws EvalException {
    switch (op) {
      case PLUS:
        if (x instanceof StarlarkList<?> xList && y instanceof StarlarkList<?> yList) {
          xList.extend((StarlarkIterable) yList);
          return xList;
        }
        break;
      case PIPE:
        if (x instanceof Dict && y instanceof Map) {
          Dict<Object, Object> xDict = (Dict<Object, Object>) x;
          xDict.putEntries((Map<Object, Object>) y);
          return xDict;
        } else if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.update(Tuple.of(ySet));
          return xSet;
        }
        break;
      case AMPERSAND:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.intersectionUpdate(Tuple.of(ySet));
          return xSet;
        }
        break;
      case CARET:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.symmetricDifferenceUpdate(ySet);
          return xSet;
        }
        break;
      case MINUS:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.differenceUpdate(Tuple.of(ySet));
          return xSet;
        }
        break;
      default: // fall through
    }
    return EvalUtils.binaryOp(op, x, y, fr.thread);
  }

  // ---- Truffle nodes (operate over the existing StarlarkThread.Frame) ----

  private abstract static class ExprNode extends Node {
    abstract Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException;
  }

  private abstract static class StmtNode extends Node {
    abstract TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException;
  }

  private static final class ConstNode extends ExprNode {
    private final Object value;

    ConstNode(Object value) {
      this.value = value;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) {
      return value;
    }
  }

  /** Mirrors {@link Eval}'s {@code evalIdentifier}, including the "referenced before assignment". */
  private static final class IdentifierNode extends ExprNode {
    private final Resolver.Binding bind;
    private final String name;
    private final Location loc;

    IdentifierNode(Identifier id) {
      this.bind = id.getBinding();
      this.name = id.getName();
      this.loc = id.getStartLocation();
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException {
      StarlarkFunction fn = (StarlarkFunction) fr.fn;
      Object result;
      switch (bind.getScope()) {
        case LOCAL -> result = fr.locals[bind.getIndex()];
        case CELL -> result = ((StarlarkFunction.Cell) fr.locals[bind.getIndex()]).x;
        case FREE -> result = fn.getFreeVar(bind.getIndex()).x;
        case GLOBAL -> result = fn.getGlobal(bind.getIndex());
        case PREDECLARED -> result = fn.getModule().getPredeclared(name);
        case UNIVERSAL -> result = Starlark.UNIVERSE.get(name);
        default -> throw new IllegalStateException(bind.toString());
      }
      if (result == null) {
        fr.setErrorLocation(loc);
        throw Starlark.errorf(
            "%s variable '%s' is referenced before assignment.", bind.getScope(), name);
      }
      return result;
    }
  }

  /** Mirrors {@link Eval}'s {@code evalBinaryOperator}, including AND/OR short-circuiting. */
  private static final class BinaryNode extends ExprNode {
    private final TokenKind op;
    private final Location opLoc;
    @Child private ExprNode left;
    @Child private ExprNode right;

    BinaryNode(TokenKind op, Location opLoc, ExprNode left, ExprNode right) {
      this.op = op;
      this.opLoc = opLoc;
      this.left = left;
      this.right = right;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object x = left.exec(fr);
      switch (op) {
        case AND:
          return Starlark.truth(x) ? right.exec(fr) : x;
        case OR:
          return Starlark.truth(x) ? x : right.exec(fr);
        default:
          Object y = right.exec(fr);
          try {
            // Specialized fast path for int-op-int (the common case in loops), identical to the
            // StarlarkInt cases inside EvalUtils.binaryOp but a clean, partial-evaluation-friendly
            // call the Graal compiler can inline. Everything else defers to EvalUtils.binaryOp.
            if (x instanceof StarlarkInt xi && y instanceof StarlarkInt yi) {
              StarlarkInt fast = fastIntArith(op, xi, yi);
              if (fast != null) {
                return fast;
              }
            }
            return EvalUtils.binaryOp(op, x, y, fr.thread);
          } catch (EvalException ex) {
            fr.setErrorLocation(opLoc);
            throw ex;
          }
      }
    }
  }

  /**
   * Fast path for {@code int <op> int}, returning the same result as the corresponding StarlarkInt
   * case in {@link EvalUtils#binaryOp}, or null for operators handled only by the general path
   * (true/floor division, shifts, comparisons).
   */
  private static StarlarkInt fastIntArith(TokenKind op, StarlarkInt x, StarlarkInt y)
      throws EvalException {
    return switch (op) {
      case PLUS -> StarlarkInt.add(x, y);
      case MINUS -> StarlarkInt.subtract(x, y);
      case STAR -> StarlarkInt.multiply(x, y);
      case PERCENT -> StarlarkInt.mod(x, y);
      case AMPERSAND -> StarlarkInt.and(x, y);
      case PIPE -> StarlarkInt.or(x, y);
      case CARET -> StarlarkInt.xor(x, y);
      default -> null;
    };
  }

  /** Mirrors {@link Eval}'s {@code evalUnaryOperator}. */
  private static final class UnaryNode extends ExprNode {
    private final TokenKind op;
    private final Location loc;
    @Child private ExprNode operand;

    UnaryNode(TokenKind op, Location loc, ExprNode operand) {
      this.op = op;
      this.loc = loc;
      this.operand = operand;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object x = operand.exec(fr);
      try {
        return EvalUtils.unaryOp(op, x);
      } catch (EvalException ex) {
        fr.setErrorLocation(loc);
        throw ex;
      }
    }
  }

  /** Mirrors {@link Eval}'s {@code evalConditional} (ternary). */
  private static final class CondNode extends ExprNode {
    @Child private ExprNode cond;
    @Child private ExprNode thenCase;
    @Child private ExprNode elseCase;

    CondNode(ExprNode cond, ExprNode thenCase, ExprNode elseCase) {
      this.cond = cond;
      this.thenCase = thenCase;
      this.elseCase = elseCase;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      return Starlark.truth(cond.exec(fr)) ? thenCase.exec(fr) : elseCase.exec(fr);
    }
  }

  /** Mirrors {@link Eval}'s {@code evalIndex}. */
  private static final class IndexNode extends ExprNode {
    @Child private ExprNode object;
    @Child private ExprNode key;
    private final Location loc;

    IndexNode(ExprNode object, ExprNode key, Location loc) {
      this.object = object;
      this.key = key;
      this.loc = loc;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object o = object.exec(fr);
      Object k = key.exec(fr);
      try {
        return EvalUtils.index(fr.thread, o, k);
      } catch (EvalException ex) {
        fr.setErrorLocation(loc);
        throw ex;
      }
    }
  }

  /** Mirrors {@link Eval}'s {@code evalDot} (attribute read). */
  private static final class DotNode extends ExprNode {
    @Child private ExprNode object;
    private final String field;
    private final Location loc;

    DotNode(ExprNode object, String field, Location loc) {
      this.object = object;
      this.field = field;
      this.loc = loc;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object o = object.exec(fr);
      try {
        return Starlark.getattr(fr.thread, o, field, /* defaultValue= */ null);
      } catch (EvalException ex) {
        fr.setErrorLocation(loc);
        throw ex;
      }
    }
  }

  /** Mirrors {@link Eval}'s {@code evalList} (list and tuple literals). */
  private static final class ListNode extends ExprNode {
    @Children private final ExprNode[] elements;
    private final boolean tuple;

    ListNode(ExprNode[] elements, boolean tuple) {
      this.elements = elements;
      this.tuple = tuple;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object[] array = new Object[elements.length];
      for (int i = 0; i < elements.length; i++) {
        array[i] = elements[i].exec(fr);
      }
      return tuple ? Tuple.wrap(array) : StarlarkList.wrap(fr.thread.mutability(), array);
    }
  }

  /** Mirrors {@link Eval}'s {@code evalDict}, including hashability and duplicate-key checks. */
  private static final class DictNode extends ExprNode {
    @Children private final ExprNode[] keys;
    @Children private final ExprNode[] values;
    private final Location[] colons;

    DictNode(ExprNode[] keys, ExprNode[] values, Location[] colons) {
      this.keys = keys;
      this.values = values;
      this.colons = colons;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
      for (int i = 0; i < keys.length; i++) {
        Object k = keys[i].exec(fr);
        Object v = values[i].exec(fr);
        try {
          Starlark.checkHashable(k);
        } catch (EvalException ex) {
          fr.setErrorLocation(colons[i]);
          throw ex;
        }
        if (map.put(k, v) != null) {
          fr.setErrorLocation(colons[i]);
          throw Starlark.errorf(
              "dictionary expression has duplicate key: %s",
              Starlark.repr(k, fr.thread.getSemantics()));
        }
      }
      Mutability mu = fr.thread.mutability();
      return mu.isFrozen() ? CompactImmutableDict.copyOf(map) : Dict.wrap(mu, map);
    }
  }

  /** Positional-only call, equivalent to {@link Eval}'s positional call path. */
  private static final class CallNode extends ExprNode {
    @Child private ExprNode fn;
    @Children private final ExprNode[] args;

    CallNode(ExprNode fn, ExprNode[] args) {
      this.fn = fn;
      this.args = args;
    }

    @Override
    Object exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      StarlarkCallable callable = Starlark.getStarlarkCallable(fr.thread, fn.exec(fr));
      Object[] values = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].exec(fr);
      }
      return Starlark.positionalOnlyCall(fr.thread, callable, values);
    }
  }

  private static final class ReturnNode extends StmtNode {
    @Child private ExprNode result;

    ReturnNode(ExprNode result) {
      this.result = result;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      if (result != null) {
        fr.result = result.exec(fr);
      }
      return TokenKind.RETURN;
    }
  }

  private static final class ExprStmtNode extends StmtNode {
    @Child private ExprNode expr;

    ExprStmtNode(ExprNode expr) {
      this.expr = expr;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      expr.exec(fr);
      return TokenKind.PASS;
    }
  }

  private static final class FlowNode extends StmtNode {
    private final TokenKind kind;

    FlowNode(TokenKind kind) {
      this.kind = kind;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) {
      return kind;
    }
  }

  private static final class IfNode extends StmtNode {
    @Child private ExprNode cond;
    @Children private final StmtNode[] thenBlock;
    @Children private final StmtNode[] elseBlock; // may be null

    IfNode(ExprNode cond, StmtNode[] thenBlock, StmtNode[] elseBlock) {
      this.cond = cond;
      this.thenBlock = thenBlock;
      this.elseBlock = elseBlock;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      if (Starlark.truth(cond.exec(fr))) {
        return execBlock(thenBlock, fr);
      } else if (elseBlock != null) {
        return execBlock(elseBlock, fr);
      }
      return TokenKind.PASS;
    }
  }

  /** Mirrors {@link Eval}'s {@code execFor} (single-identifier loop variable). */
  private static final class ForNode extends StmtNode {
    private final Resolver.Binding var;
    @Child private ExprNode collection;
    private final Location collectionLoc;
    private final Location forLoc;
    @Children private final StmtNode[] body;

    ForNode(
        Resolver.Binding var,
        ExprNode collection,
        Location collectionLoc,
        Location forLoc,
        StmtNode[] body) {
      this.var = var;
      this.collection = collection;
      this.collectionLoc = collectionLoc;
      this.forLoc = forLoc;
      this.body = body;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object o = collection.exec(fr);
      Iterable<?> seq;
      try {
        seq = Starlark.toIterable(o);
      } catch (EvalException ex) {
        fr.setErrorLocation(collectionLoc);
        throw ex;
      }
      EvalUtils.addIterator(seq);
      try {
        for (Object it : seq) {
          assignIdentifier(fr, var, it);
          switch (execBlock(body, fr)) {
            case PASS:
            case CONTINUE:
              fr.thread.checkInterrupt();
              continue;
            case BREAK:
              return TokenKind.PASS;
            case RETURN:
              return TokenKind.RETURN;
            default:
              throw new IllegalStateException("unreachable");
          }
        }
      } catch (EvalException ex) {
        fr.setErrorLocation(forLoc);
        throw ex;
      } finally {
        EvalUtils.removeIterator(seq);
      }
      return TokenKind.PASS;
    }
  }

  private static final class AssignIdentNode extends StmtNode {
    private final Resolver.Binding bind;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AssignIdentNode(Resolver.Binding bind, ExprNode rhs, Location opLoc) {
      this.bind = bind;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      try {
        assignIdentifier(fr, bind, rhs.exec(fr));
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AssignIndexNode extends StmtNode {
    @Child private ExprNode object;
    @Child private ExprNode key;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AssignIndexNode(ExprNode object, ExprNode key, ExprNode rhs, Location opLoc) {
      this.object = object;
      this.key = key;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      try {
        Object value = rhs.exec(fr);
        EvalUtils.setIndex(object.exec(fr), key.exec(fr), value);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AssignDotNode extends StmtNode {
    @Child private ExprNode object;
    private final String field;
    private final Location dotLoc;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AssignDotNode(ExprNode object, String field, Location dotLoc, ExprNode rhs, Location opLoc) {
      this.object = object;
      this.field = field;
      this.dotLoc = dotLoc;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      try {
        Object value = rhs.exec(fr);
        Object o = object.exec(fr);
        try {
          EvalUtils.setField(o, field, value);
        } catch (EvalException ex) {
          fr.setErrorLocation(dotLoc);
          throw ex;
        }
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  /** Mirrors {@link Eval}'s {@code execAugmentedAssignment} for an identifier target. */
  private static final class AugAssignIdentNode extends StmtNode {
    private final Resolver.Binding bind;
    @Child private IdentifierNode read;
    private final TokenKind op;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AugAssignIdentNode(
        Resolver.Binding bind, IdentifierNode read, TokenKind op, ExprNode rhs, Location opLoc) {
      this.bind = bind;
      this.read = read;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object x = read.exec(fr); // same semantics as Eval's eval(lhs) for an identifier
      Object y = rhs.exec(fr);
      Object z;
      try {
        z = inplaceBinaryOp(fr, op, x, y);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      assignIdentifier(fr, bind, z);
      return TokenKind.PASS;
    }
  }

  /** Mirrors {@link Eval}'s {@code execAugmentedAssignment} for an index target. */
  private static final class AugAssignIndexNode extends StmtNode {
    @Child private ExprNode object;
    @Child private ExprNode key;
    private final TokenKind op;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AugAssignIndexNode(ExprNode object, ExprNode key, TokenKind op, ExprNode rhs, Location opLoc) {
      this.object = object;
      this.key = key;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object o = object.exec(fr);
      Object k = key.exec(fr);
      Object x = EvalUtils.index(fr.thread, o, k);
      Object y = rhs.exec(fr);
      Object z;
      try {
        z = inplaceBinaryOp(fr, op, x, y);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      try {
        EvalUtils.setIndex(o, k, z);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  /** Mirrors {@link Eval}'s {@code execAugmentedAssignment} for a dot target. */
  private static final class AugAssignDotNode extends StmtNode {
    @Child private ExprNode object;
    private final String field;
    private final Location dotLoc;
    private final TokenKind op;
    @Child private ExprNode rhs;
    private final Location opLoc;

    AugAssignDotNode(
        ExprNode object, String field, Location dotLoc, TokenKind op, ExprNode rhs, Location opLoc) {
      this.object = object;
      this.field = field;
      this.dotLoc = dotLoc;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(StarlarkThread.Frame fr) throws EvalException, InterruptedException {
      Object o = object.exec(fr);
      try {
        Object x = Starlark.getattr(fr.thread, o, field, /* defaultValue= */ null);
        Object y = rhs.exec(fr);
        Object z;
        try {
          z = inplaceBinaryOp(fr, op, x, y);
        } catch (EvalException ex) {
          fr.setErrorLocation(opLoc);
          throw ex;
        }
        EvalUtils.setField(o, field, z);
      } catch (EvalException ex) {
        fr.setErrorLocation(dotLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class BodyRootNode extends RootNode {
    @Children private final StmtNode[] body;

    BodyRootNode(StmtNode[] body) {
      super(null);
      this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      StarlarkThread.Frame fr = (StarlarkThread.Frame) frame.getArguments()[0];
      NATIVE_CALLS.incrementAndGet();
      try {
        execBlock(body, fr);
        return fr.result;
      } catch (EvalException | InterruptedException e) {
        throw new HostException(e);
      }
    }
  }

  /** Carries a checked Starlark exception across the Truffle {@code execute} boundary. */
  private static final class HostException extends RuntimeException {
    HostException(Throwable cause) {
      super(cause);
    }

    /** Rethrows the wrapped checked exception. The return type lets callers write {@code throw}. */
    RuntimeException rethrow() throws EvalException, InterruptedException {
      Throwable cause = getCause();
      if (cause instanceof EvalException e) {
        throw e;
      }
      if (cause instanceof InterruptedException e) {
        throw e;
      }
      throw new IllegalStateException(cause);
    }
  }
}
