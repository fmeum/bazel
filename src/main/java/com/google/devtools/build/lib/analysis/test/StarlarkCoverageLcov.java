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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.starlark.java.eval.CoverageRecorder;
import net.starlark.java.syntax.Coverage;

/**
 * Renders Starlark coverage, as recorded per Skyframe node, into the lcov tracefile format that the
 * rest of Bazel's coverage pipeline already consumes.
 *
 * <p>Input is the union of the coverage of every node evaluated for the targets under test. The
 * same .bzl file generally appears many times -- once per node that executed any of its code -- so
 * the first job here is to merge those records per file.
 *
 * <p>Execution counts are not tracked, only whether a line ran, so every {@code DA} record carries a
 * count of 1 or 0. That is enough for genhtml and for the line-coverage percentages Bazel reports;
 * tracking real counts would mean an increment rather than a bit set on the interpreter's hot path,
 * which is not worth it.
 */
public final class StarlarkCoverageLcov {

  private StarlarkCoverageLcov() {}

  /** Accumulates the records for one source file across all the nodes that executed its code. */
  private static final class FileRecord {
    final BitSet executableLines = new BitSet();
    final BitSet hitLines = new BitSet();
    // Keyed by "line:name" so that two functions with the same name at different lines stay
    // distinct, and so that the same function seen via different nodes merges.
    final Map<String, FunctionRecord> functions = new TreeMap<>();
  }

  private static final class FunctionRecord {
    final String name;
    final int line;
    boolean called;

    FunctionRecord(String name, int line) {
      this.name = name;
      this.line = line;
    }
  }

  /**
   * Renders the given per-node coverage as an lcov tracefile.
   *
   * <p>Files are emitted in sorted order, and records within a file in line order, so that the
   * output is deterministic and diffable.
   */
  public static String toLcov(Iterable<CoverageRecorder.FileCoverage> coverage) {
    Map<String, FileRecord> byFile = new TreeMap<>();

    for (CoverageRecorder.FileCoverage fc : coverage) {
      FileRecord record = byFile.computeIfAbsent(fc.getFile(), unused -> new FileRecord());
      for (int line : fc.getExecutableLines()) {
        record.executableLines.set(line);
      }
      for (int line : fc.getHitLines()) {
        record.hitLines.set(line);
      }
      List<Coverage.Function> functions = fc.getFunctions();
      for (int i = 0; i < functions.size(); i++) {
        Coverage.Function fn = functions.get(i);
        FunctionRecord fr =
            record.functions.computeIfAbsent(
                functionKey(fn), unused -> new FunctionRecord(fn.getName(), fn.getLine()));
        fr.called |= fc.isFunctionCalled(i);
      }
    }

    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, FileRecord> e : byFile.entrySet()) {
      appendRecord(out, e.getKey(), e.getValue());
    }
    return out.toString();
  }

  private static String functionKey(Coverage.Function fn) {
    // Zero-padded so that TreeMap order is line order, not lexicographic order of the number.
    return String.format("%09d:%s", fn.getLine(), fn.getName());
  }

  private static void appendRecord(StringBuilder out, String file, FileRecord record) {
    out.append("SF:").append(file).append('\n');

    List<FunctionRecord> functions = new ArrayList<>(record.functions.values());
    for (FunctionRecord fn : functions) {
      out.append("FN:").append(fn.line).append(',').append(fn.name).append('\n');
    }
    int functionsHit = 0;
    for (FunctionRecord fn : functions) {
      if (fn.called) {
        functionsHit++;
      }
      out.append("FNDA:").append(fn.called ? 1 : 0).append(',').append(fn.name).append('\n');
    }
    if (!functions.isEmpty()) {
      out.append("FNF:").append(functions.size()).append('\n');
      out.append("FNH:").append(functionsHit).append('\n');
    }

    int linesHit = 0;
    for (int line = record.executableLines.nextSetBit(0);
        line >= 0;
        line = record.executableLines.nextSetBit(line + 1)) {
      boolean hit = record.hitLines.get(line);
      if (hit) {
        linesHit++;
      }
      out.append("DA:").append(line).append(',').append(hit ? 1 : 0).append('\n');
    }
    out.append("LF:").append(record.executableLines.cardinality()).append('\n');
    out.append("LH:").append(linesHit).append('\n');
    out.append("end_of_record\n");
  }

  /**
   * Returns, per source file, the fraction of executable lines that ran. Intended for logging and
   * tests; the tracefile is the real output.
   */
  public static Map<String, Double> lineCoverageRatios(
      Iterable<CoverageRecorder.FileCoverage> coverage) {
    Map<String, BitSet[]> byFile = new LinkedHashMap<>();
    for (CoverageRecorder.FileCoverage fc : coverage) {
      BitSet[] sets = byFile.computeIfAbsent(fc.getFile(), u -> new BitSet[] {new BitSet(), new BitSet()});
      for (int line : fc.getExecutableLines()) {
        sets[0].set(line);
      }
      for (int line : fc.getHitLines()) {
        sets[1].set(line);
      }
    }
    Map<String, Double> ratios = new TreeMap<>();
    for (Map.Entry<String, BitSet[]> e : byFile.entrySet()) {
      int total = e.getValue()[0].cardinality();
      ratios.put(e.getKey(), total == 0 ? 1.0 : ((double) e.getValue()[1].cardinality()) / total);
    }
    return ratios;
  }
}
