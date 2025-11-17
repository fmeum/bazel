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
 * Represents a single token in the Starlark source.
 */
public final class Token {
  public final TokenKind kind;
  public final int start;
  public final int end;
  public final Object value; // For INT, FLOAT, STRING, IDENTIFIER

  public Token(TokenKind kind, int start, int end, Object value) {
    this.kind = kind;
    this.start = start;
    this.end = end;
    this.value = value;
  }

  public Token(TokenKind kind, int start, int end) {
    this(kind, start, end, null);
  }

  @Override
  public String toString() {
    if (value != null) {
      return kind + "(" + value + ")";
    }
    return kind.toString();
  }
}
