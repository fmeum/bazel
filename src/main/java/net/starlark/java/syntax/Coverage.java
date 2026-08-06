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
package net.starlark.java.syntax;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The coverage instrumentation table of a single compiled {@link Program}.
 *
 * <p>A Program is instrumented (has a non-null {@link Program#getCoverage}) or not, as decided once
 * at compile time; see {@link Program#compileFile}. This is deliberately a property of the compiled
 * program rather than of the executing thread, for two reasons:
 *
 * <ul>
 *   <li>The evaluator can decide whether to record coverage once per call frame instead of once per
 *       statement, so an uninstrumented program pays nothing at all. See {@code Eval.exec}.
 *   <li>Files excluded by the instrumentation filter run at full speed even during a coverage
 *       build.
 * </ul>
 *
 * <p>A Coverage holds only static, immutable information derived from the syntax tree: which source
 * offsets the evaluator will step over, what line each of them is on, and where the file's functions
 * are declared. Execution counts are not held here -- a Program is shared by all threads that
 * execute it (and, in Bazel, cached across builds), so hit sets live in the thread instead. See
 * {@code net.starlark.java.eval.CoverageRecorder}.
 *
 * <p>Granularity is one line per statement, keyed on the statement's start line. A statement
 * spanning several lines marks only its first. This matches what the tree-walking evaluator can
 * observe without extra bookkeeping, and matches how lcov consumers render Python-like languages.
 */
public final class Coverage {

  /** A function declaration, for lcov {@code FN}/{@code FNDA} records. */
  public static final class Function {
    private final String name;
    private final int line;

    private Function(String name, int line) {
      this.name = name;
      this.line = line;
    }

    /** Returns the function's name; lambdas are named {@code lambda}. */
    public String getName() {
      return name;
    }

    /** Returns the 1-based line on which the function is declared. */
    public int getLine() {
      return line;
    }
  }

  private final String file;
  private final int fileSize;

  // Start offsets of every statement the evaluator will step over, sorted ascending, with the line
  // each one falls on in the parallel array. Two arrays rather than an array of pairs: this is
  // retained for as long as the Program is, which in Bazel means for the lifetime of the analysis
  // cache.
  private final int[] stmtOffsets;
  private final int[] stmtLines;

  // Sorted ascending by line. Parallel to the boolean[] of call flags that a recorder produces.
  private final ImmutableList<Function> functions;

  // Identity map: Resolver.Function does not override equals.
  private final Map<Resolver.Function, Integer> functionIndex;

  private final int[] executableLines; // sorted, deduplicated; derived from stmtLines

  private Coverage(
      String file,
      int fileSize,
      int[] stmtOffsets,
      int[] stmtLines,
      ImmutableList<Function> functions,
      Map<Resolver.Function, Integer> functionIndex) {
    this.file = file;
    this.fileSize = fileSize;
    this.stmtOffsets = stmtOffsets;
    this.stmtLines = stmtLines;
    this.functions = functions;
    this.functionIndex = functionIndex;
    this.executableLines = sortedDedup(stmtLines);
  }

  /** Returns the source file name, as it appears in {@link Location}s of this program. */
  public String getFile() {
    return file;
  }

  /**
   * Returns the size of the source file in UTF-16 chars. Recorders use this to size the bit set in
   * which they accumulate hit statement offsets.
   */
  public int getFileSize() {
    return fileSize;
  }

  /**
   * Returns every line of this file that the evaluator could step over, sorted and deduplicated.
   *
   * <p>This is the denominator of the coverage ratio: a file that is loaded but whose functions are
   * never called still reports these lines, with a hit count of zero.
   *
   * <p>The returned array must not be modified. It is shared by every recorder that reports on this
   * program, so that per-node coverage data costs nothing to carry the baseline around.
   */
  public int[] getExecutableLines() {
    return executableLines;
  }

  /** Returns this file's function declarations, sorted by line. */
  public ImmutableList<Function> getFunctions() {
    return functions;
  }

  /**
   * Returns the index into {@link #getFunctions} of the given resolved function, or -1 if it does
   * not belong to this program (or is the file's top level, which is not a declared function).
   */
  public int indexOfFunction(Resolver.Function rfn) {
    Integer i = functionIndex.get(rfn);
    return i == null ? -1 : i;
  }

  /**
   * Converts a set of hit statement start offsets, as accumulated by a recorder, into the sorted,
   * deduplicated set of covered lines.
   */
  public int[] toHitLines(BitSet hitOffsets) {
    int[] lines = new int[stmtOffsets.length];
    int n = 0;
    for (int i = 0; i < stmtOffsets.length; i++) {
      if (hitOffsets.get(stmtOffsets[i])) {
        lines[n++] = stmtLines[i];
      }
    }
    return sortedDedup(Arrays.copyOf(lines, n));
  }

  private static int[] sortedDedup(int[] values) {
    int[] sorted = values.clone();
    Arrays.sort(sorted);
    int n = 0;
    for (int i = 0; i < sorted.length; i++) {
      if (i == 0 || sorted[i] != sorted[i - 1]) {
        sorted[n++] = sorted[i];
      }
    }
    return n == sorted.length ? sorted : Arrays.copyOf(sorted, n);
  }

  /**
   * Builds the instrumentation table for a resolved file. Called from {@link Program#compileFile}
   * when the file passes the instrumentation filter.
   */
  static Coverage instrument(StarlarkFile file) {
    Collector collector = new Collector();
    collector.visit(file);
    return collector.build(file.locs);
  }

  /**
   * Walks the resolved syntax tree recording the start offset of every statement, and every function
   * declaration.
   *
   * <p>The set of recorded offsets must agree with the set of nodes that {@code Eval.exec} is
   * called on -- that is what the probe keys on. Overriding the generic {@link NodeVisitor#visit}
   * entry point rather than the per-type overloads keeps the two in step as statement types are
   * added.
   */
  private static final class Collector extends NodeVisitor {
    private final BitSet offsets = new BitSet();
    private final Map<Resolver.Function, Integer> declared = new IdentityHashMap<>();

    @Override
    public void visit(Node node) {
      if (node instanceof Statement) {
        offsets.set(node.getStartOffset());
      } else if (node instanceof LambdaExpression lambda) {
        declare(lambda.getResolvedFunction(), lambda.getStartOffset());
      }
      super.visit(node);
    }

    @Override
    public void visit(DefStatement node) {
      declare(node.getResolvedFunction(), node.getStartOffset());
      super.visit(node);
    }

    private void declare(@Nullable Resolver.Function rfn, int offset) {
      if (rfn == null) {
        return; // unresolved; the file did not compile, and we will not be executed
      }
      declared.put(rfn, offset);
    }

    Coverage build(FileLocations locs) {
      int[] stmtOffsets = offsets.stream().toArray(); // BitSet.stream is ascending
      int[] stmtLines = new int[stmtOffsets.length];
      for (int i = 0; i < stmtOffsets.length; i++) {
        stmtLines[i] = locs.getLocation(stmtOffsets[i]).line();
      }

      // Sort declarations by line so that lcov FN records come out in source order, then rebuild
      // the resolved-function index against the sorted positions.
      ImmutableList<Map.Entry<Resolver.Function, Integer>> sorted =
          ImmutableList.sortedCopyOf(
              (a, b) -> Integer.compare(a.getValue(), b.getValue()), declared.entrySet());
      ImmutableList.Builder<Function> functions = ImmutableList.builder();
      Map<Resolver.Function, Integer> index = new IdentityHashMap<>();
      for (int i = 0; i < sorted.size(); i++) {
        Resolver.Function rfn = sorted.get(i).getKey();
        functions.add(new Function(rfn.getName(), locs.getLocation(sorted.get(i).getValue()).line()));
        index.put(rfn, i);
      }

      return new Coverage(
          locs.file(), locs.size(), stmtOffsets, stmtLines, functions.build(), index);
    }
  }
}
