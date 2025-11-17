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

/**
 * Token types for Starlark lexer.
 *
 * <p>Faithful translation of net.starlark.java.syntax.TokenKind
 */
public enum TokenKind {
  // Literals
  INT,
  FLOAT,
  STRING,

  // Identifiers and keywords
  IDENTIFIER,

  // Keywords
  AND("and"),
  BREAK("break"),
  CONTINUE("continue"),
  DEF("def"),
  ELIF("elif"),
  ELSE("else"),
  FALSE("False"),
  FOR("for"),
  IF("if"),
  IN("in"),
  LAMBDA("lambda"),
  LOAD("load"),
  NONE("None"),
  NOT("not"),
  OR("or"),
  PASS("pass"),
  RETURN("return"),
  TRUE("True"),
  WHILE("while"),

  // Operators
  PLUS("+"),
  MINUS("-"),
  STAR("*"),
  SLASH("/"),
  SLASH_SLASH("//"),
  PERCENT("%"),
  STAR_STAR("**"),

  TILDE("~"),
  AMPERSAND("&"),
  PIPE("|"),
  CARET("^"),
  LESS_LESS("<<"),
  GREATER_GREATER(">>"),

  // Comparison operators
  EQUALS_EQUALS("=="),
  NOT_EQUALS("!="),
  LESS("<"),
  LESS_EQUALS("<="),
  GREATER(">"),
  GREATER_EQUALS(">="),

  // Assignment operators
  EQUALS("="),
  PLUS_EQUALS("+="),
  MINUS_EQUALS("-="),
  STAR_EQUALS("*="),
  SLASH_EQUALS("/="),
  SLASH_SLASH_EQUALS("//="),
  PERCENT_EQUALS("%="),
  AMPERSAND_EQUALS("&="),
  PIPE_EQUALS("|="),
  CARET_EQUALS("^="),
  LESS_LESS_EQUALS("<<="),
  GREATER_GREATER_EQUALS(">>="),

  // Delimiters
  COMMA(","),
  SEMICOLON(";"),
  COLON(":"),
  LPAREN("("),
  RPAREN(")"),
  LBRACKET("["),
  RBRACKET("]"),
  LBRACE("{"),
  RBRACE("}"),
  DOT("."),
  RARROW("->"),

  // Layout
  NEWLINE,
  INDENT,
  OUTDENT,
  EOF;

  private final String text;

  TokenKind() {
    this.text = null;
  }

  TokenKind(String text) {
    this.text = text;
  }

  /** Returns the textual representation for operators/keywords, null otherwise. */
  public String getText() {
    return text;
  }

  /** Returns true if this is a keyword. */
  public boolean isKeyword() {
    return text != null && Character.isLetter(text.charAt(0));
  }
}
