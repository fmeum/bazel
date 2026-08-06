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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Coverage;

/**
 * Accumulates which parts of which instrumented {@link net.starlark.java.syntax.Program}s a single
 * {@link StarlarkThread} executed.
 *
 * <p>Install one with {@link StarlarkThread#setCoverageRecorder} before executing, and read the
 * result with {@link #snapshot} afterwards. A recorder belongs to one thread and is not
 * thread-safe.
 *
 * <p>Hit sets live here rather than in the Program because a Program is immutable, shared by every
 * thread that executes it, and -- in Bazel -- cached across builds. Keeping counts per thread is
 * what lets the caller attribute coverage to the unit of work it is evaluating (in Bazel, a
 * Skyframe node) and store it alongside that unit's cached result.
 *
 * <p>Only programs compiled with instrumentation are recorded; everything else is invisible here
 * and pays nothing at run time. See {@link Coverage}.
 */
public final class CoverageRecorder {

  /** What one thread executed of one instrumented file. */
  public static final class FileCoverage {
    private final Coverage coverage;
    private final int[] hitLines;
    private final boolean[] calledFunctions;

    private FileCoverage(Coverage coverage, int[] hitLines, boolean[] calledFunctions) {
      this.coverage = coverage;
      this.hitLines = hitLines;
      this.calledFunctions = calledFunctions;
    }

    /** Returns the source file name. */
    public String getFile() {
      return coverage.getFile();
    }

    /** Returns the covered lines, sorted and deduplicated. Must not be modified. */
    public int[] getHitLines() {
      return hitLines;
    }

    /**
     * Returns every line of the file that could have been covered, sorted and deduplicated -- the
     * denominator of the coverage ratio. Must not be modified.
     *
     * <p>This array is shared with the compiled Program, so carrying the baseline around with each
     * unit of coverage data costs no additional memory.
     */
    public int[] getExecutableLines() {
      return coverage.getExecutableLines();
    }

    /** Returns the file's function declarations, sorted by line. */
    public ImmutableList<Coverage.Function> getFunctions() {
      return coverage.getFunctions();
    }

    /** Returns whether the i'th function of {@link #getFunctions} was called. */
    public boolean isFunctionCalled(int i) {
      return calledFunctions[i];
    }
  }

  private static final class FileHits {
    final Coverage coverage;
    final BitSet statements;
    final boolean[] functions;

    FileHits(Coverage coverage) {
      this.coverage = coverage;
      // Keyed by statement start offset. Sized to the file so that no statement forces a resize.
      this.statements = new BitSet(coverage.getFileSize());
      this.functions = new boolean[coverage.getFunctions().size()];
    }
  }

  // Keyed by Coverage identity: one entry per instrumented file this thread touched. Threads are
  // short-lived relative to the programs they execute, so this is usually tiny.
  private final Map<Coverage, FileHits> byProgram = new IdentityHashMap<>();

  // Single-entry cache. Calls cluster within a file, so consecutive frames usually repeat.
  @Nullable private Coverage lastCoverage;
  @Nullable private FileHits lastHits;

  /**
   * Called on function entry. Records the call and returns the bit set in which the frame should
   * mark executed statements, or null if the function's program is not instrumented.
   */
  @Nullable
  BitSet enter(StarlarkCallable fn) {
    if (!(fn instanceof StarlarkFunction sfn)) {
      return null; // a builtin; it has no Starlark source to attribute
    }
    Coverage coverage = sfn.getCoverage();
    if (coverage == null) {
      return null;
    }

    FileHits hits;
    if (coverage == lastCoverage) {
      hits = lastHits;
    } else {
      hits = byProgram.computeIfAbsent(coverage, FileHits::new);
      lastCoverage = coverage;
      lastHits = hits;
    }

    int i = coverage.indexOfFunction(sfn.rfn);
    if (i >= 0) {
      hits.functions[i] = true;
    }
    return hits.statements;
  }

  /**
   * Returns what this thread has executed so far, one entry per instrumented file it touched.
   *
   * <p>The result is a snapshot: it is unaffected by subsequent execution on this thread. Callers
   * that evaluate the same unit of work more than once (in Bazel, a Skyframe restart) should
   * discard the recorder and start a fresh one rather than snapshot twice.
   */
  public ImmutableList<FileCoverage> snapshot() {
    List<FileCoverage> result = new ArrayList<>(byProgram.size());
    for (FileHits hits : byProgram.values()) {
      result.add(
          new FileCoverage(
              hits.coverage,
              hits.coverage.toHitLines(hits.statements),
              hits.functions.clone()));
    }
    // Deterministic order: IdentityHashMap iteration order is not stable across JVM runs, and this
    // data ends up in cached Skyframe values and in build outputs.
    result.sort((a, b) -> a.getFile().compareTo(b.getFile()));
    return ImmutableList.copyOf(result);
  }

  /** Returns whether this recorder has observed any execution of instrumented code. */
  public boolean isEmpty() {
    return byProgram.isEmpty();
  }
}
