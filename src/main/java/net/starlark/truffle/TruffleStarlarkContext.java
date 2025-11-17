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

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import java.io.PrintWriter;

/**
 * Context for Truffle Starlark execution.
 *
 * <p>Holds per-context state including I/O streams, globals, and configuration.
 */
public final class TruffleStarlarkContext {

  private static final ContextReference<TruffleStarlarkContext> REFERENCE =
      ContextReference.create(TruffleStarlarkLanguage.class);

  public static TruffleStarlarkContext get(Node node) {
    return REFERENCE.get(node);
  }

  private final TruffleLanguage.Env env;
  private final TruffleStarlarkLanguage language;
  private final PrintWriter output;

  public TruffleStarlarkContext(TruffleStarlarkLanguage language, TruffleLanguage.Env env) {
    this.language = language;
    this.env = env;
    this.output = new PrintWriter(env.out(), true);
  }

  public TruffleLanguage.Env getEnv() {
    return env;
  }

  public TruffleStarlarkLanguage getLanguage() {
    return language;
  }

  public PrintWriter getOutput() {
    return output;
  }

  /** Print a value to the output stream. */
  public void print(Object value) {
    output.println(value);
  }
}
