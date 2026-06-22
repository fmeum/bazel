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

import java.util.Locale;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;

/**
 * Microbenchmark comparing the tree-walking and Truffle Starlark engines on a single hot,
 * closure-free function, calling it enough times that the Truffle CallTarget is JIT-compiled and the
 * steady state is measured (unlike the Bazel-server A/B, which re-parses each round and so never
 * reuses a compiled CallTarget).
 *
 * <p>Run on a JVMCI/Graal-capable JVM (e.g. jargraal: {@code -XX:+EnableJVMCI --upgrade-module-path=
 * <graal compiler>}) to see the Truffle engine's JIT speedup; on a plain JVM the Truffle engine runs
 * interpreted.
 */
public final class TruffleStarlarkBenchmark {

  private static final String SRC =
      "def hot(n):\n"
          + "    s = 0\n"
          + "    for i in range(n):\n"
          + "        s = s + i * i\n"
          + "    return s\n";

  public static void main(String[] args) throws Exception {
    // One engine per JVM process (pass "" for tree-walking or "truffle"), so the two never
    // cross-contaminate one JVM's JIT state. Varying arguments (~2000-2255 inner iterations) defeat
    // constant-folding of the pure function; both engines do the same work, so the ratio is fair.
    String engine = args.length > 0 ? args[0] : "";
    StarlarkInt[] inputs = new StarlarkInt[256];
    for (int k = 0; k < inputs.length; k++) {
      inputs[k] = StarlarkInt.of(2000 + k);
    }
    double usPerCall = benchEngine(engine, inputs) / 1000.0;
    System.out.printf(
        Locale.ROOT,
        "%-12s %8.2f us/call   (JVM %s)%n",
        engine.isEmpty() ? "tree-walking" : engine,
        usPerCall,
        System.getProperty("java.vm.version"));
  }

  /** Returns steady-state ns per hot() call for the given engine (average over a fixed call count). */
  private static double benchEngine(String engine, StarlarkInt[] inputs) throws Exception {
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .set(StarlarkSemantics.EXPERIMENTAL_STARLARK_ENGINE, engine)
            .build();
    try (Mutability mu = Mutability.create("bench")) {
      // Create hot() once, so its resolved function (and thus the Truffle CallTarget) is reused.
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      Module module = Module.create();
      Starlark.execFile(
          ParserInput.fromString(SRC, "bench.star"), FileOptions.DEFAULT, module, thread);
      StarlarkCallable hot = (StarlarkCallable) module.getGlobal("hot");

      long sink = 0;
      sink += callLoop(thread, hot, inputs, 100_000); // warmup: compile the CallTarget
      // Average over a fixed, large call count (each call runs a ~2000-iteration loop, so this is
      // hundreds of millions of iterations - GC/scheduling noise averages out).
      int measured = 300_000;
      long t0 = System.nanoTime();
      sink += callLoop(thread, hot, inputs, measured);
      long dt = System.nanoTime() - t0;
      if (sink == Long.MIN_VALUE) {
        System.out.print(""); // defeat dead-code elimination
      }
      return (double) dt / measured;
    }
  }

  private static long callLoop(StarlarkThread thread, StarlarkCallable hot, StarlarkInt[] inputs, int n)
      throws Exception {
    long sink = 0;
    int mask = inputs.length - 1;
    for (int i = 0; i < n; i++) {
      sink += Starlark.positionalOnlyCall(thread, hot, inputs[i & mask]).hashCode();
    }
    return sink;
  }
}
