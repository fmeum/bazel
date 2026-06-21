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
    StarlarkInt arg = StarlarkInt.of(2000); // inner loop length per call
    System.out.printf(
        "JVM: %s %s%n",
        System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));

    double treeWalk = benchEngine("", arg);
    double truffle = benchEngine("truffle", arg);

    System.out.printf(Locale.ROOT, "%n%-14s %12s%n", "engine", "ns/call");
    System.out.println("-".repeat(28));
    System.out.printf(Locale.ROOT, "%-14s %12.0f%n", "tree-walking", treeWalk);
    System.out.printf(Locale.ROOT, "%-14s %12.0f%n", "truffle", truffle);
    System.out.printf(Locale.ROOT, "%nspeedup (treewalk/truffle): %.2fx%n", treeWalk / truffle);
  }

  /** Returns steady-state ns per hot() call for the given engine. */
  private static double benchEngine(String engine, StarlarkInt arg) throws Exception {
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
      sink += callLoop(thread, hot, arg, 30_000); // warmup: compile the CallTarget
      long ops = 0;
      long start = System.nanoTime();
      long end = start + 5_000_000_000L;
      long now;
      do {
        sink += callLoop(thread, hot, arg, 2_000);
        ops += 2_000;
        now = System.nanoTime();
      } while (now < end);
      if (sink == Long.MIN_VALUE) {
        System.out.print(""); // defeat dead-code elimination
      }
      return (double) (now - start) / ops;
    }
  }

  private static long callLoop(StarlarkThread thread, StarlarkCallable hot, StarlarkInt arg, int n)
      throws Exception {
    long sink = 0;
    for (int i = 0; i < n; i++) {
      sink += Starlark.positionalOnlyCall(thread, hot, arg).hashCode();
    }
    return sink;
  }
}
