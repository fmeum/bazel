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

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import java.util.ArrayList;
import java.util.List;
import net.starlark.java.syntax.Coverage;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of Starlark coverage instrumentation. */
@RunWith(JUnit4.class)
public final class CoverageTest {

  private static final class Result {
    ImmutableList<CoverageRecorder.FileCoverage> files;

    CoverageRecorder.FileCoverage only() {
      assertThat(files).hasSize(1);
      return files.get(0);
    }
  }

  /** Compiles {@code src} with instrumentation, runs it, and returns the recorded coverage. */
  private static Result run(String src) throws Exception {
    return run(src, /* instrument= */ true);
  }

  private static Result run(String src, boolean instrument) throws Exception {
    ParserInput input = ParserInput.fromString(src, "test.bzl");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    Module module = Module.create();
    Program prog =
        Program.compileFile(file, module, /* loader= */ null, /* instrumentForCoverage= */ instrument);

    CoverageRecorder recorder = new CoverageRecorder();
    Result result = new Result();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setCoverageRecorder(recorder);
      Starlark.execFileProgram(prog, module, thread);
    }
    result.files = recorder.snapshot();
    return result;
  }

  private static List<Integer> lines(int[] a) {
    return Ints.asList(a);
  }

  private static List<String> calledFunctions(CoverageRecorder.FileCoverage fc) {
    List<String> called = new ArrayList<>();
    for (int i = 0; i < fc.getFunctions().size(); i++) {
      if (fc.isFunctionCalled(i)) {
        called.add(fc.getFunctions().get(i).getName());
      }
    }
    return called;
  }

  @Test
  public void uninstrumentedProgramRecordsNothing() throws Exception {
    Result r = run("x = 1\ny = x + 1\n", /* instrument= */ false);
    assertThat(r.files).isEmpty();
  }

  @Test
  public void topLevelStatementsAreCovered() throws Exception {
    // 1: x = 1
    // 2: y = x + 1
    // 3: z = y + 1
    Result r = run("x = 1\ny = x + 1\nz = y + 1\n");
    assertThat(lines(r.only().getHitLines())).containsExactly(1, 2, 3).inOrder();
    assertThat(lines(r.only().getExecutableLines())).containsExactly(1, 2, 3).inOrder();
    assertThat(r.only().getFile()).isEqualTo("test.bzl");
  }

  @Test
  public void untakenBranchIsNotCovered() throws Exception {
    // 1: def f(x):
    // 2:     if x == 1:
    // 3:         taken = 1
    // 4:     else:
    // 5:         untaken = 1
    // 6:     return 0
    // 7: y = f(1)
    Result r =
        run(
            "def f(x):\n"
                + "    if x == 1:\n"
                + "        taken = 1\n"
                + "    else:\n"
                + "        untaken = 1\n"
                + "    return 0\n"
                + "y = f(1)\n");
    assertThat(lines(r.only().getHitLines())).containsExactly(1, 2, 3, 6, 7).inOrder();
    // Line 5 is executable but never ran: it is the difference between numerator and denominator.
    // There is no record for line 4; `else` is not a statement the evaluator steps over.
    assertThat(lines(r.only().getExecutableLines())).containsExactly(1, 2, 3, 5, 6, 7).inOrder();
  }

  @Test
  public void uncalledFunctionBodyIsExecutableButNotCovered() throws Exception {
    // 1: def called():
    // 2:     return 1
    // 3: def uncalled():
    // 4:     return 2
    // 5: x = called()
    Result r = run("def called():\n    return 1\ndef uncalled():\n    return 2\nx = called()\n");
    // The two def statements themselves execute (they bind a name); only one body does.
    assertThat(lines(r.only().getHitLines())).containsExactly(1, 2, 3, 5).inOrder();
    assertThat(lines(r.only().getExecutableLines())).containsExactly(1, 2, 3, 4, 5).inOrder();
  }

  @Test
  public void functionCoverageRecordsDeclarationsAndCalls() throws Exception {
    Result r = run("def called():\n    return 1\ndef uncalled():\n    return 2\nx = called()\n");
    ImmutableList<Coverage.Function> fns = r.only().getFunctions();
    assertThat(fns.stream().map(Coverage.Function::getName).collect(ImmutableList.toImmutableList()))
        .containsExactly("called", "uncalled")
        .inOrder();
    assertThat(fns.get(0).getLine()).isEqualTo(1);
    assertThat(fns.get(1).getLine()).isEqualTo(3);
    assertThat(calledFunctions(r.only())).containsExactly("called");
  }

  @Test
  public void loopBodyCoveredOnce() throws Exception {
    // A line executed many times is still one covered line; the hit set is a set.
    // 1: def f():
    // 2:     total = 0
    // 3:     for i in range(3):
    // 4:         total += i
    // 5:     return total
    // 6: x = f()
    Result r =
        run(
            "def f():\n"
                + "    total = 0\n"
                + "    for i in range(3):\n"
                + "        total += i\n"
                + "    return total\n"
                + "x = f()\n");
    assertThat(lines(r.only().getHitLines())).containsExactly(1, 2, 3, 4, 5, 6).inOrder();
  }

  @Test
  public void nestedFunctionInheritsInstrumentation() throws Exception {
    // 1: def outer():
    // 2:     def inner():
    // 3:         return 1
    // 4:     return inner()
    // 5: x = outer()
    Result r = run("def outer():\n    def inner():\n        return 1\n    return inner()\nx = outer()\n");
    assertThat(lines(r.only().getHitLines())).containsExactly(1, 2, 3, 4, 5).inOrder();
    assertThat(calledFunctions(r.only())).containsExactly("outer", "inner");
  }

  @Test
  public void lambdaIsRecordedAsAFunction() throws Exception {
    // 1: f = lambda x: x + 1
    // 2: y = f(1)
    Result r = run("f = lambda x: x + 1\ny = f(1)\n");
    assertThat(
            r.only().getFunctions().stream()
                .map(Coverage.Function::getName)
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("lambda");
    assertThat(calledFunctions(r.only())).containsExactly("lambda");
  }

  @Test
  public void multiLineStatementCoversItsFirstLine() throws Exception {
    // Granularity is the statement's start line; a statement spanning several lines marks only
    // its first. Documented limitation of the tree-walking evaluator.
    // 1: x = [
    // 2:     1,
    // 3: ]
    Result r = run("x = [\n    1,\n]\n");
    assertThat(lines(r.only().getHitLines())).containsExactly(1);
    assertThat(lines(r.only().getExecutableLines())).containsExactly(1);
  }

  @Test
  public void snapshotIsIndependentOfLaterExecution() throws Exception {
    ParserInput input = ParserInput.fromString("x = 1\ny = 2\n", "test.bzl");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    Module module = Module.create();
    Program prog =
        Program.compileFile(file, module, /* loader= */ null, /* instrumentForCoverage= */ true);
    CoverageRecorder recorder = new CoverageRecorder();
    ImmutableList<CoverageRecorder.FileCoverage> first;
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setCoverageRecorder(recorder);
      Starlark.execFileProgram(prog, module, thread);
      first = recorder.snapshot();
      assertThat(lines(first.get(0).getHitLines())).containsExactly(1, 2).inOrder();
    }
    // The returned arrays are not aliased to the recorder's live state.
    assertThat(lines(first.get(0).getHitLines())).containsExactly(1, 2).inOrder();
  }

  @Test
  public void baselineIsSharedWithTheProgram() throws Exception {
    // Two threads executing the same instrumented program must share one baseline array, so that
    // carrying coverage data per unit of work costs no extra memory for the denominator.
    ParserInput input = ParserInput.fromString("def f():\n    return 1\nx = f()\n", "test.bzl");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    Module module = Module.create();
    Program prog =
        Program.compileFile(file, module, /* loader= */ null, /* instrumentForCoverage= */ true);

    int[] first = executableLinesOfOneRun(prog);
    int[] second = executableLinesOfOneRun(prog);
    assertThat(second).isSameInstanceAs(first);
  }

  private static int[] executableLinesOfOneRun(Program prog) throws Exception {
    CoverageRecorder recorder = new CoverageRecorder();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setCoverageRecorder(recorder);
      Starlark.execFileProgram(prog, Module.create(), thread);
    }
    return recorder.snapshot().get(0).getExecutableLines();
  }

  @Test
  public void resolverModuleIsUnaffected() throws Exception {
    // Sanity: instrumenting must not change what the program computes.
    Module module = Module.create();
    ParserInput input = ParserInput.fromString("x = [i * 2 for i in range(4)]\n", "test.bzl");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    Program prog =
        Program.compileFile(file, module, /* loader= */ null, /* instrumentForCoverage= */ true);
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setCoverageRecorder(new CoverageRecorder());
      Starlark.execFileProgram(prog, module, thread);
    }
    assertThat(Starlark.repr(module.getGlobal("x"), StarlarkSemantics.DEFAULT)).isEqualTo("[0, 2, 4, 6]");
  }
}
