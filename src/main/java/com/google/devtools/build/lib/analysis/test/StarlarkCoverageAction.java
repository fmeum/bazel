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

import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.DeterministicWriter;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import net.starlark.java.eval.CoverageRecorder;

/**
 * Writes the Starlark coverage of a target's analysis as an lcov tracefile.
 *
 * <p>This is the same shape as {@link BaselineCoverageAction}: the content is fully determined by
 * analysis-time data, and the action exists only to materialise it as a file that the rest of the
 * coverage pipeline can consume. It has no inputs.
 *
 * <p>Starlark that contributes to a build runs during loading and analysis, not inside the test
 * action, so its coverage is already known by the time actions are created. That is why this cannot
 * be collected the way C++ or Java coverage is.
 */
@Immutable
public final class StarlarkCoverageAction extends AbstractFileWriteAction {

  private final NestedSet<CoverageRecorder.FileCoverage> coverage;

  private StarlarkCoverageAction(
      ActionOwner owner,
      NestedSet<CoverageRecorder.FileCoverage> coverage,
      Artifact primaryOutput) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), primaryOutput);
    this.coverage = coverage;
  }

  @Override
  public String getMnemonic() {
    return "StarlarkCoverage";
  }

  @Override
  public void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    // The rendered tracefile is a pure function of the coverage data, so fingerprinting the
    // rendering is both correct and simple. It is not large: a few tens of bytes per covered line,
    // for the .bzl files the instrumentation filter selected.
    fp.addString(render());
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    String lcov = render();
    return out -> {
      PrintWriter writer = new PrintWriter(out, false, StandardCharsets.UTF_8);
      writer.write(lcov);
      writer.flush();
    };
  }

  private String render() {
    return StarlarkCoverageLcov.toLcov(coverage.toList());
  }

  /**
   * Creates the action, writing next to the target's baseline coverage so that it is picked up by
   * the same report machinery.
   */
  static StarlarkCoverageAction create(
      RuleContext ruleContext, NestedSet<CoverageRecorder.FileCoverage> coverage) {
    Artifact output =
        ruleContext.getPackageRelativeArtifact(
            PathFragment.create(ruleContext.getTarget().getName())
                .getChild("starlark_coverage.dat"),
            ruleContext.getTestLogsDirectory());
    return new StarlarkCoverageAction(ruleContext.getActionOwner(), coverage, output);
  }
}
