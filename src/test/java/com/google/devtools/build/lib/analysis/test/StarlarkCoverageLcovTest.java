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
package com.google.devtools.build.lib.analysis.test;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.starlark.java.eval.CoverageRecorder;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link StarlarkCoverageLcov}. */
@RunWith(JUnit4.class)
public final class StarlarkCoverageLcovTest {

  /**
   * Compiles and runs {@code src} with instrumentation, once per element of {@code calls}, and
   * returns the union of what each run recorded -- standing in for the per-Skyframe-node coverage
   * that the real pipeline unions.
   */
  private static ImmutableList<CoverageRecorder.FileCoverage> runAll(
      String name, String src, String... calls) throws Exception {
    StarlarkFile file = StarlarkFile.parse(ParserInput.fromString(src, name), FileOptions.DEFAULT);
    Module compileEnv = Module.create();
    Program prog =
        Program.compileFile(file, compileEnv, /* loader= */ null, /* instrumentForCoverage= */ true);

    List<CoverageRecorder.FileCoverage> all = new ArrayList<>();
    for (String call : calls) {
      CoverageRecorder recorder = new CoverageRecorder();
      Module module = Module.create();
      try (Mutability mu = Mutability.create("test")) {
        StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
        thread.setCoverageRecorder(recorder);
        Starlark.execFileProgram(prog, module, thread);
        if (!call.isEmpty()) {
          Starlark.positionalOnlyCall(thread, (StarlarkCallable) module.getGlobal(call));
        }
      }
      all.addAll(recorder.snapshot());
    }
    return ImmutableList.copyOf(all);
  }

  @Test
  public void rendersLinesAndFunctions() throws Exception {
    // 1: def hit():
    // 2:     return 1
    // 3: def missed():
    // 4:     return 2
    // 5: x = hit()
    String lcov =
        StarlarkCoverageLcov.toLcov(
            runAll(
                "pkg/rules.bzl",
                "def hit():\n    return 1\ndef missed():\n    return 2\nx = hit()\n",
                ""));
    assertThat(lcov)
        .isEqualTo(
            "SF:pkg/rules.bzl\n"
                + "FN:1,hit\n"
                + "FN:3,missed\n"
                + "FNDA:1,hit\n"
                + "FNDA:0,missed\n"
                + "FNF:2\n"
                + "FNH:1\n"
                + "DA:1,1\n"
                + "DA:2,1\n"
                + "DA:3,1\n"
                + "DA:4,0\n"
                + "DA:5,1\n"
                + "LF:5\n"
                + "LH:4\n"
                + "end_of_record\n");
  }

  @Test
  public void unionsAcrossNodes() throws Exception {
    // Two separate runs each cover a different branch. The merged report must show both covered,
    // which is the whole point of unioning per-node coverage rather than reporting it separately.
    String src =
        "def a():\n"
            + "    return 1\n"
            + "def b():\n"
            + "    return 2\n"
            + "def unused():\n"
            + "    return 3\n";
    ImmutableList<CoverageRecorder.FileCoverage> coverage = runAll("pkg/rules.bzl", src, "a", "b");
    String lcov = StarlarkCoverageLcov.toLcov(coverage);

    assertThat(lcov).contains("FNDA:1,a\n");
    assertThat(lcov).contains("FNDA:1,b\n");
    assertThat(lcov).contains("FNDA:0,unused\n");
    assertThat(lcov).contains("DA:2,1\n"); // a's body, covered by the first run
    assertThat(lcov).contains("DA:4,1\n"); // b's body, covered by the second
    assertThat(lcov).contains("DA:6,0\n"); // unused's body, covered by neither
    assertThat(lcov).contains("LF:6\n");
    assertThat(lcov).contains("LH:5\n");
    // One record per file, however many nodes contributed.
    assertThat(lcov.split("SF:", -1)).hasLength(2);
  }

  @Test
  public void emptyInputProducesEmptyOutput() {
    assertThat(StarlarkCoverageLcov.toLcov(ImmutableList.of())).isEmpty();
  }

  @Test
  public void filesAreSortedForDeterminism() throws Exception {
    List<CoverageRecorder.FileCoverage> all = new ArrayList<>();
    all.addAll(runAll("z/late.bzl", "x = 1\n", ""));
    all.addAll(runAll("a/early.bzl", "y = 1\n", ""));
    String lcov = StarlarkCoverageLcov.toLcov(all);
    assertThat(lcov.indexOf("SF:a/early.bzl")).isLessThan(lcov.indexOf("SF:z/late.bzl"));
  }

  @Test
  public void reportsRatios() throws Exception {
    // 1: def used():
    // 2:     return 1
    // 3: def unused():
    // 4:     return 2
    // 5: x = used()
    // 4 of 5 executable lines run.
    var ratios =
        StarlarkCoverageLcov.lineCoverageRatios(
            runAll(
                "pkg/rules.bzl",
                "def used():\n    return 1\ndef unused():\n    return 2\nx = used()\n",
                ""));
    assertThat(ratios).containsExactly("pkg/rules.bzl", 0.8);
  }
}
