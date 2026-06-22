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
 * Multi-kernel microbenchmark of the tree-walking vs Truffle Starlark engine, covering the constructs
 * real {@code .bzl}/BUILD code uses (arithmetic, strings, lists, dicts, generic iteration, function
 * calls, method calls, comprehensions). For each kernel it reports steady-state us/call and, for the
 * Truffle engine, whether the top function compiled natively or fell back to {@link Eval}.
 *
 * <p>Run with one engine per JVM (arg "" = tree-walking, "truffle" = Truffle), so the two never
 * cross-contaminate one JVM's JIT state. Run the Truffle JVM on a Graal/JVMCI-capable configuration
 * (jargraal: {@code -XX:+EnableJVMCI --upgrade-module-path=<graal compiler>}) to measure the JIT.
 */
public final class TruffleStarlarkBenchmark {

  /** A workload: a Starlark function {@code hot(n)} and the loop size n to call it with. */
  private record Kernel(String name, String construct, int n, String src) {}

  private static final Kernel[] KERNELS = {
    new Kernel(
        "int_loop",
        "int arithmetic in a range loop",
        2000,
        "def hot(n):\n    s = 0\n    for i in range(n):\n        s = s + i * i\n    return s\n"),
    new Kernel(
        "func_call",
        "calls a helper function per iteration",
        2000,
        "def helper(x):\n    return x * x + 1\n"
            + "def hot(n):\n    s = 0\n    for i in range(n):\n        s = s + helper(i)\n    return s\n"),
    new Kernel(
        "builtin_call",
        "len()/str() builtins per iteration",
        2000,
        "def hot(n):\n    c = 0\n    for i in range(n):\n        c = c + len(str(i))\n    return c\n"),
    new Kernel(
        "dict_build",
        "dict literal + d[i]= in a loop",
        2000,
        "def hot(n):\n    d = {}\n    for i in range(n):\n        d[i] = i * 2\n    return len(d)\n"),
    new Kernel(
        "generic_iter",
        "for x in <list> (non-range iteration)",
        2000,
        "def hot(n):\n    lst = []\n    for i in range(n):\n        lst = lst + [i]\n"
            + "    t = 0\n    for x in lst:\n        t = t + x\n    return t\n"),
    new Kernel(
        "string_concat",
        "string + in a loop (O(n^2))",
        200,
        "def hot(n):\n    s = \"\"\n    for i in range(n):\n        s = s + \"x\"\n    return len(s)\n"),
    new Kernel(
        "list_concat",
        "list + [x] in a loop (O(n^2))",
        200,
        "def hot(n):\n    xs = []\n    for i in range(n):\n        xs = xs + [i]\n    return len(xs)\n"),
    new Kernel(
        "method_call",
        "obj.method() per iteration",
        2000,
        "def hot(n):\n    c = 0\n    for i in range(n):\n        c = c + \"hello world\".count(\"o\")\n    return c\n"),
    new Kernel(
        "comprehension",
        "list comprehension",
        2000,
        "def hot(n):\n    return len([i * i for i in range(n)])\n"),
  };

  public static void main(String[] args) throws Exception {
    String engine = args.length > 0 ? args[0] : "";
    System.out.printf(
        Locale.ROOT,
        "engine=%-12s JVM=%s%n%-15s %-38s %10s %8s%n",
        engine.isEmpty() ? "tree-walking" : engine,
        System.getProperty("java.vm.version"),
        "kernel",
        "construct",
        "us/call",
        "native");
    for (Kernel k : KERNELS) {
      Result r = benchKernel(engine, k);
      System.out.printf(
          Locale.ROOT,
          "%-15s %-38s %10.2f %8s%n",
          k.name(),
          k.construct(),
          r.nsPerCall() / 1000.0,
          r.nativeFlag());
    }
  }

  private record Result(double nsPerCall, String nativeFlag) {}

  private static Result benchKernel(String engine, Kernel k) throws Exception {
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .set(StarlarkSemantics.EXPERIMENTAL_STARLARK_ENGINE, engine)
            .build();
    // Varying inputs around k.n so the compiler can't constant-fold the pure function.
    StarlarkInt[] inputs = new StarlarkInt[64];
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = StarlarkInt.of(k.n() + i);
    }
    try (Mutability mu = Mutability.create("bench")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      Module module = Module.create();
      Starlark.execFile(
          ParserInput.fromString(k.src(), "bench.star"), FileOptions.DEFAULT, module, thread);
      StarlarkCallable hot = (StarlarkCallable) module.getGlobal("hot");

      long sink = callLoop(thread, hot, inputs, 30_000); // warmup: compile the CallTarget
      int measured = 50_000;
      long t0 = System.nanoTime();
      sink += callLoop(thread, hot, inputs, measured);
      long dt = System.nanoTime() - t0;
      if (sink == Long.MIN_VALUE) {
        System.out.print(""); // defeat dead-code elimination
      }
      return new Result((double) dt / measured, nativeFlag(engine, hot));
    }
  }

  private static String nativeFlag(String engine, StarlarkCallable hot) {
    if (engine.isEmpty()) {
      return "-";
    }
    StarlarkEngine e = StarlarkEngine.forName(engine);
    return e instanceof TruffleStarlarkEngine t && t.isNativelyCompiled((StarlarkFunction) hot)
        ? "yes"
        : "FALLBACK";
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
