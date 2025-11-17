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

package net.starlark.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import net.starlark.truffle.nodes.TruffleStarlarkRootNode;

/**
 * Truffle-based implementation of the Starlark language.
 *
 * <p>This is a high-performance implementation using the GraalVM Truffle framework,
 * designed to eventually replace the tree-walking interpreter in net.starlark.java.eval.
 */
@TruffleLanguage.Registration(
    id = "starlark",
    name = "Starlark",
    version = "1.0",
    defaultMimeType = TruffleStarlarkLanguage.MIME_TYPE,
    characterMimeTypes = TruffleStarlarkLanguage.MIME_TYPE,
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
    fileTypeDetectors = TruffleStarlarkFileDetector.class
)
public final class TruffleStarlarkLanguage extends TruffleLanguage<TruffleStarlarkContext> {

  public static final String MIME_TYPE = "application/x-starlark";

  private static final LanguageReference<TruffleStarlarkLanguage> REFERENCE =
      LanguageReference.create(TruffleStarlarkLanguage.class);

  public static TruffleStarlarkLanguage get(Node node) {
    return REFERENCE.get(node);
  }

  @Override
  protected TruffleStarlarkContext createContext(Env env) {
    return new TruffleStarlarkContext(this, env);
  }

  @Override
  protected CallTarget parse(ParsingRequest request) throws Exception {
    Source source = request.getSource();
    TruffleStarlarkRootNode rootNode = new TruffleStarlarkRootNode(this, source);
    return rootNode.getCallTarget();
  }
}
