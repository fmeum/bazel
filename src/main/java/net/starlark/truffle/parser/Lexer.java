// Copyright 2025 The Bazel Authors. All rights reserved.
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

package net.starlark.truffle.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Lexer for Starlark source code.
 *
 * <p>Simplified version for Phase 1, handles:
 * - Literals (int, float, string)
 * - Identifiers and keywords
 * - Operators
 * - Basic indentation (INDENT/OUTDENT/NEWLINE)
 */
public final class Lexer {
  private static final Map<String, TokenKind> KEYWORDS = new HashMap<>();

  static {
    // Initialize keyword map
    for (TokenKind kind : TokenKind.values()) {
      if (kind.isKeyword()) {
        KEYWORDS.put(kind.getText(), kind);
      }
    }
  }

  private final String source;
  private final Deque<Integer> indentStack = new ArrayDeque<>();
  private final Deque<Token> tokenQueue = new ArrayDeque<>();

  private int pos = 0;
  private int openParenDepth = 0; // Track ( [ { nesting

  public Lexer(String source) {
    this.source = source;
    this.indentStack.push(0); // Initial indentation level
  }

  public Token nextToken() {
    // Return queued tokens first (for OUTDENT sequences)
    if (!tokenQueue.isEmpty()) {
      return tokenQueue.poll();
    }

    skipWhitespaceAndComments();

    if (pos >= source.length()) {
      // Generate OUTDENTs for any remaining indentation
      if (indentStack.size() > 1) {
        indentStack.pop();
        return new Token(TokenKind.OUTDENT, pos, pos);
      }
      return new Token(TokenKind.EOF, pos, pos);
    }

    int start = pos;
    char c = source.charAt(pos);

    // Newline handling
    if (c == '\n') {
      pos++;
      if (openParenDepth > 0) {
        return nextToken(); // Skip newlines inside parens
      }
      return new Token(TokenKind.NEWLINE, start, pos);
    }

    // Numbers
    if (Character.isDigit(c)) {
      return scanNumber();
    }

    // Strings
    if (c == '"' || c == '\'') {
      return scanString(c);
    }

    // Identifiers and keywords
    if (Character.isLetter(c) || c == '_') {
      return scanIdentifier();
    }

    // Operators and delimiters
    return scanOperator();
  }

  private void skipWhitespaceAndComments() {
    while (pos < source.length()) {
      char c = source.charAt(pos);

      // Comments
      if (c == '#') {
        while (pos < source.length() && source.charAt(pos) != '\n') {
          pos++;
        }
        continue;
      }

      // Only skip spaces/tabs, not newlines
      if (c == ' ' || c == '\t') {
        pos++;
        continue;
      }

      break;
    }
  }

  private Token scanNumber() {
    int start = pos;
    boolean isFloat = false;

    // Scan integer part
    while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
      pos++;
    }

    // Check for decimal point
    if (pos < source.length() && source.charAt(pos) == '.') {
      isFloat = true;
      pos++;
      while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
        pos++;
      }
    }

    // Check for exponent
    if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
      isFloat = true;
      pos++;
      if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
        pos++;
      }
      while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
        pos++;
      }
    }

    String text = source.substring(start, pos);
    if (isFloat) {
      return new Token(TokenKind.FLOAT, start, pos, Double.parseDouble(text));
    } else {
      return new Token(TokenKind.INT, start, pos, Long.parseLong(text));
    }
  }

  private Token scanString(char quote) {
    int start = pos;
    pos++; // Skip opening quote

    StringBuilder sb = new StringBuilder();
    while (pos < source.length()) {
      char c = source.charAt(pos);

      if (c == quote) {
        pos++; // Skip closing quote
        return new Token(TokenKind.STRING, start, pos, sb.toString());
      }

      if (c == '\\' && pos + 1 < source.length()) {
        pos++;
        char escaped = source.charAt(pos);
        switch (escaped) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case '\\': sb.append('\\'); break;
          case '\'': sb.append('\''); break;
          case '"': sb.append('"'); break;
          default: sb.append(escaped); break;
        }
        pos++;
      } else {
        sb.append(c);
        pos++;
      }
    }

    throw new RuntimeException("Unterminated string at position " + start);
  }

  private Token scanIdentifier() {
    int start = pos;
    while (pos < source.length()) {
      char c = source.charAt(pos);
      if (Character.isLetterOrDigit(c) || c == '_') {
        pos++;
      } else {
        break;
      }
    }

    String text = source.substring(start, pos);
    TokenKind kind = KEYWORDS.getOrDefault(text, TokenKind.IDENTIFIER);

    // Special handling for True, False, None
    if (kind == TokenKind.IDENTIFIER) {
      if (text.equals("True") || text.equals("False") || text.equals("None")) {
        return new Token(kind, start, pos, text);
      }
    }

    return new Token(kind, start, pos, text);
  }

  private Token scanOperator() {
    int start = pos;
    char c = source.charAt(pos);

    // Track paren depth
    if (c == '(' || c == '[' || c == '{') {
      openParenDepth++;
    } else if (c == ')' || c == ']' || c == '}') {
      openParenDepth--;
    }

    // Two-character operators
    if (pos + 1 < source.length()) {
      String twoChar = source.substring(pos, pos + 2);
      TokenKind kind = getTwoCharOp(twoChar);
      if (kind != null) {
        pos += 2;
        return new Token(kind, start, pos);
      }
    }

    // Single-character operators
    pos++;
    return new Token(getSingleCharOp(c), start, pos);
  }

  private TokenKind getTwoCharOp(String s) {
    switch (s) {
      case "==": return TokenKind.EQUALS_EQUALS;
      case "!=": return TokenKind.NOT_EQUALS;
      case "<=": return TokenKind.LESS_EQUALS;
      case ">=": return TokenKind.GREATER_EQUALS;
      case "//": return TokenKind.SLASH_SLASH;
      case "**": return TokenKind.STAR_STAR;
      case "<<": return TokenKind.LESS_LESS;
      case ">>": return TokenKind.GREATER_GREATER;
      case "+=": return TokenKind.PLUS_EQUALS;
      case "-=": return TokenKind.MINUS_EQUALS;
      case "*=": return TokenKind.STAR_EQUALS;
      case "/=": return TokenKind.SLASH_EQUALS;
      case "%=": return TokenKind.PERCENT_EQUALS;
      case "&=": return TokenKind.AMPERSAND_EQUALS;
      case "|=": return TokenKind.PIPE_EQUALS;
      case "^=": return TokenKind.CARET_EQUALS;
      case "->": return TokenKind.RARROW;
      default: return null;
    }
  }

  private TokenKind getSingleCharOp(char c) {
    switch (c) {
      case '+': return TokenKind.PLUS;
      case '-': return TokenKind.MINUS;
      case '*': return TokenKind.STAR;
      case '/': return TokenKind.SLASH;
      case '%': return TokenKind.PERCENT;
      case '~': return TokenKind.TILDE;
      case '&': return TokenKind.AMPERSAND;
      case '|': return TokenKind.PIPE;
      case '^': return TokenKind.CARET;
      case '<': return TokenKind.LESS;
      case '>': return TokenKind.GREATER;
      case '=': return TokenKind.EQUALS;
      case ',': return TokenKind.COMMA;
      case ';': return TokenKind.SEMICOLON;
      case ':': return TokenKind.COLON;
      case '(': return TokenKind.LPAREN;
      case ')': return TokenKind.RPAREN;
      case '[': return TokenKind.LBRACKET;
      case ']': return TokenKind.RBRACKET;
      case '{': return TokenKind.LBRACE;
      case '}': return TokenKind.RBRACE;
      case '.': return TokenKind.DOT;
      default:
        throw new RuntimeException("Unexpected character: " + c);
    }
  }
}
