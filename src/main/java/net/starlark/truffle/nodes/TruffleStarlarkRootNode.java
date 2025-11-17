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

package net.starlark.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import net.starlark.truffle.TruffleStarlarkContext;
import net.starlark.truffle.TruffleStarlarkLanguage;

/**
 * Root node for Truffle Starlark execution.
 *
 * <p>This is the entry point for executing a Starlark source file.
 * For now, it implements a minimal parser that only recognizes print("...") statements.
 */
public final class TruffleStarlarkRootNode extends RootNode {

  private final Source source;
  @Child private StarlarkNode body;

  public TruffleStarlarkRootNode(TruffleStarlarkLanguage language, Source source) {
    super(language);
    this.source = source;
    this.body = parseSource(source);
  }

  private StarlarkNode parseSource(Source source) {
    // Minimal parser: recognize print("...") pattern
    String code = source.getCharacters().toString().trim();

    if (code.startsWith("print(") && code.endsWith(")")) {
      // Extract the argument
      String arg = code.substring(6, code.length() - 1).trim();

      // Check if it's a string literal
      if (arg.startsWith("\"") && arg.endsWith("\"")) {
        String value = arg.substring(1, arg.length() - 1);
        return new PrintNode(new StringLiteralNode(value));
      }
    }

    // For now, return a no-op if we don't recognize the pattern
    return new NoOpNode();
  }

  @Override
  public Object execute(VirtualFrame frame) {
    TruffleStarlarkLanguage language = TruffleStarlarkLanguage.get(this);
    TruffleStarlarkContext context = TruffleStarlarkContext.get(this);
    return body.execute(frame, context);
  }

  @Override
  public String getName() {
    return source.getName();
  }
}
