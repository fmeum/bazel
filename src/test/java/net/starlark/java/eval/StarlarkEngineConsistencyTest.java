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

import static com.google.common.truth.Truth.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Differential test for the {@link StarlarkEngine} seam.
 *
 * <p>For a corpus of programs it runs each one twice - once with the default {@link
 * StarlarkEngine#TREE_WALKING} engine, and once with an engine installed via {@link
 * StarlarkThread#setEngine} - and asserts the two runs produce identical observable behavior:
 * global bindings, {@code print} output, and thrown errors.
 *
 * <p>Today the "engine under test" is a thin wrapper that delegates back to the tree-walker, so this
 * verifies the seam itself is behavior-preserving and is actually exercised. When a second engine
 * (e.g. a bytecode or Truffle-based compiler) is added, point {@link #engineUnderTest} at it and the
 * same corpus becomes the conformance suite that guards the migration. New language features should
 * add a program to {@link #CORPUS}.
 */
@RunWith(JUnit4.class)
public final class StarlarkEngineConsistencyTest {

  /** Programs covering literals, arithmetic/bignum, strings, collections, comprehensions, control
   * flow, functions, recursion, closures (cells/free vars), and error paths. */
  private static final String[] CORPUS = {
    // arithmetic, including overflow into BigInteger
    "a = 2 + 3 * 4\n"
        + "b = 1000000 * 1000000 * 1000000\n"
        + "c = 7 // 2\n"
        + "d = 7 % 3\n"
        + "e = 2 ** 10\n",
    // strings and string methods
    "s = 'ab' + 'cd'\n" + "n = len(s)\n" + "u = s.upper()\n" + "parts = 'a,b,c'.split(',')\n",
    // lists, dicts, comprehensions, indexing, concatenation
    "xs = [i * i for i in range(6)]\n"
        + "ys = [x for x in xs if x % 2 == 0]\n"
        + "d = {i: str(i) for i in range(4)}\n"
        + "d['y'] = xs[0:2] + [99]\n"
        + "k = sorted(d.keys())\n",
    // function definition + recursion
    "def fib(n):\n"
        + "    if n < 2:\n"
        + "        return n\n"
        + "    return fib(n - 1) + fib(n - 2)\n"
        + "r = fib(15)\n",
    // closure capturing a free variable (cell)
    "def make_adder(n):\n"
        + "    def add(x):\n"
        + "        return x + n\n"
        + "    return add\n"
        + "add5 = make_adder(5)\n"
        + "result = add5(10)\n",
    // for loop with break/continue and augmented assignment
    "t = 0\n"
        + "for i in range(10):\n"
        + "    if i == 7:\n"
        + "        break\n"
        + "    if i % 2 == 0:\n"
        + "        continue\n"
        + "    t += i\n",
    // print output
    "for i in range(3):\n" + "    print('line', i)\n",
    // function with a for-loop, simple + augmented assignment (runs natively, not at top level)
    "def sumloop(n):\n"
        + "    s = 0\n"
        + "    for i in range(n):\n"
        + "        s += i\n"
        + "    return s\n"
        + "total = sumloop(100)\n",
    // function with break/continue/if and augmented assignment
    "def picky(n):\n"
        + "    t = 0\n"
        + "    for i in range(n):\n"
        + "        if i == 7:\n"
        + "            break\n"
        + "        if i % 2 == 0:\n"
        + "            continue\n"
        + "        t += i\n"
        + "    return t\n"
        + "picked = picky(20)\n",
    // function building a list, with list literals, indexing and a conditional/unary expression
    "def build(n):\n"
        + "    xs = []\n"
        + "    for i in range(n):\n"
        + "        xs = xs + [-i if i % 2 == 0 else i]\n"
        + "    return xs\n"
        + "built = build(6)\n"
        + "first = build(6)[0]\n",
    // function using string/list method calls (obj.method(args), compiled natively)
    "def methods(s):\n"
        + "    parts = s.split(',')\n"
        + "    joined = '-'.join(parts)\n"
        + "    return joined.upper() + ' #' + str(len(parts))\n"
        + "m = methods('a,b,c,d')\n",
    // function using dict method calls (.get/.keys) inside a loop
    "def dmethods(n):\n"
        + "    d = {}\n"
        + "    for i in range(n):\n"
        + "        k = i % 5\n"
        + "        d[k] = d.get(k, 0) + 1\n"
        + "    return sorted(d.keys()), len(d)\n"
        + "dm = dmethods(23)\n",
    // function with a dict literal, indexing and membership-style access
    "def freq():\n"
        + "    d = {}\n"
        + "    for w in ['a', 'b', 'a', 'c', 'a', 'b']:\n"
        + "        if w in d:\n"
        + "            d[w] = d[w] + 1\n"
        + "        else:\n"
        + "            d[w] = 1\n"
        + "    return d\n"
        + "counts = freq()\n",
    // dynamic error: division by zero
    "x = 1 // 0\n",
    // dynamic error: explicit fail()
    "fail('boom: ' + str(42))\n",
  };

  /**
   * The engine being validated against the tree-walker. Currently a transparent wrapper; swap in a
   * real alternative engine here once one exists.
   */
  private static StarlarkEngine engineUnderTest(int[] callCounter) {
    return (fr, statements) -> {
      callCounter[0]++;
      return StarlarkEngine.TREE_WALKING.execFunctionBody(fr, statements);
    };
  }

  @Test
  public void enginesAgreeAcrossCorpus() throws Exception {
    for (String program : CORPUS) {
      Result reference = run(program, /* engine= */ null);
      Result underTest = run(program, engineUnderTest(new int[1]));
      assertThat(underTest.globals).isEqualTo(reference.globals);
      assertThat(underTest.output).isEqualTo(reference.output);
      assertThat(underTest.error).isEqualTo(reference.error);
    }
  }

  @Test
  public void installedEngineIsActuallyUsed() throws Exception {
    int[] calls = new int[1];
    run("def f(x):\n    return x + 1\n\ny = f(41)\n", engineUnderTest(calls));
    // Both the file toplevel and f() are StarlarkFunction calls routed through the engine.
    assertThat(calls[0]).isAtLeast(2);
  }

  @Test
  public void engineSelectedViaSemanticsFlag() throws Exception {
    int[] calls = new int[1];
    StarlarkEngine.register("test-counting-engine", engineUnderTest(calls));
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .set(StarlarkSemantics.EXPERIMENTAL_STARLARK_ENGINE, "test-counting-engine")
            .build();
    // No setEngine() call: the engine is chosen purely from the semantics flag.
    runWithSemantics("def f(x):\n    return x + 1\n\ny = f(41)\n", semantics, /* engine= */ null);
    assertThat(calls[0]).isAtLeast(2);
  }

  @Test
  public void truffleEngineMatchesTreeWalkerOnCorpus() throws Exception {
    StarlarkSemantics truffle =
        StarlarkSemantics.builder()
            .set(StarlarkSemantics.EXPERIMENTAL_STARLARK_ENGINE, "truffle")
            .build();
    long nativeBefore = TruffleStarlarkEngine.NATIVE_CALLS.get();
    for (String program : CORPUS) {
      Result reference = run(program, /* engine= */ null); // tree-walker
      Result viaTruffle = runWithSemantics(program, truffle, /* engine= */ null);
      assertThat(viaTruffle.globals).isEqualTo(reference.globals);
      assertThat(viaTruffle.output).isEqualTo(reference.output);
      assertThat(viaTruffle.error).isEqualTo(reference.error);
    }
    // The recursive fib() (and the closure's add()) in the corpus are compiled and run natively,
    // not via the Eval fallback - proving the Truffle path actually executed Starlark.
    assertThat(TruffleStarlarkEngine.NATIVE_CALLS.get()).isGreaterThan(nativeBefore);
  }

  /** The observable outcome of executing a program: its globals, print output, and any error. */
  private static final class Result {
    final Map<String, String> globals;
    final String output;
    final String error;

    Result(Map<String, String> globals, String output, String error) {
      this.globals = globals;
      this.output = output;
      this.error = error;
    }
  }

  private static Result run(String src, StarlarkEngine engine) throws InterruptedException {
    return runWithSemantics(src, StarlarkSemantics.DEFAULT, engine);
  }

  private static Result runWithSemantics(
      String src, StarlarkSemantics semantics, StarlarkEngine engine) throws InterruptedException {
    StringBuilder out = new StringBuilder();
    Module module = Module.create();
    String error = null;
    try (Mutability mu = Mutability.create("diff")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      if (engine != null) {
        thread.setEngine(engine);
      }
      thread.setPrintHandler((th, msg) -> out.append(msg).append('\n'));
      Starlark.execFile(
          ParserInput.fromString(src, "diff.star"), FileOptions.DEFAULT, module, thread);
    } catch (SyntaxError.Exception | EvalException e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    Map<String, String> globals = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : module.getGlobals().entrySet()) {
      globals.put(e.getKey(), Starlark.repr(e.getValue(), StarlarkSemantics.DEFAULT));
    }
    return new Result(globals, out.toString(), error);
  }
}
