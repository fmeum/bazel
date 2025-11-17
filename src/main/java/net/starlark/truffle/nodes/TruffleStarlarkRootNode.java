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

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import net.starlark.truffle.TruffleStarlarkContext;
import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.parser.Lexer;
import net.starlark.truffle.parser.Parser;
import net.starlark.truffle.values.NoneValue;

/**
 * Root node for Truffle Starlark execution.
 *
 * <p>This is the entry point for executing a Starlark source file.
 */
public final class TruffleStarlarkRootNode extends RootNode {

  private final Source source;
  @Child private StatementNode body;

  public TruffleStarlarkRootNode(TruffleStarlarkLanguage language, Source source) {
    this(language, source, parseAndBuildDescriptor(language, source));
  }

  private TruffleStarlarkRootNode(TruffleStarlarkLanguage language, Source source, ParseResult parseResult) {
    super(language, parseResult.descriptor);
    this.source = source;
    this.body = parseResult.body;
  }

  private static class ParseResult {
    final FrameDescriptor descriptor;
    final StatementNode body;

    ParseResult(FrameDescriptor descriptor, StatementNode body) {
      this.descriptor = descriptor;
      this.body = body;
    }
  }

  private static ParseResult parseAndBuildDescriptor(TruffleStarlarkLanguage language, Source source) {
    String code = source.getCharacters().toString();
    Lexer lexer = new Lexer(code);
    FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
    Parser parser = new Parser(lexer, builder, language);
    StatementNode body = parser.parseFile();
    return new ParseResult(builder.build(), body);
  }

  @Override
  public Object execute(VirtualFrame frame) {
    body.executeVoid(frame);
    return NoneValue.INSTANCE;
  }

  @Override
  public String getName() {
    return source.getName();
  }
}
