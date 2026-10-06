// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.test.InstrumentedFilesInfoApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/** An implementation class for the InstrumentedFilesProvider interface. */
public final class InstrumentedFilesInfo extends NativeInfo implements InstrumentedFilesInfoApi {
  /** Singleton provider instance for {@link InstrumentedFilesInfo}. */
  public static final InstrumentedFilesProvider STARLARK_CONSTRUCTOR =
      new InstrumentedFilesProvider();

  public static final InstrumentedFilesInfo EMPTY =
      new InstrumentedFilesInfo(
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          ImmutableMap.of(),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER));

  private final NestedSet<Artifact> instrumentedFiles;
  private final NestedSet<Artifact> instrumentationMetadataFiles;
  private final NestedSet<Artifact> baselineCoverageArtifacts;
  private final NestedSet<Artifact> coverageSupportFiles;
  private final ImmutableMap<String, CoverageEnvironmentValue> coverageEnvironment;
  private final NestedSet<Tuple> reportedToActualSources;

  /**
   * The value of an environment variable that needs to be set for tests collecting code coverage.
   *
   * @param value the value of the variable
   * @param toolPlatform the execution platform of the target that specified the variable, which is
   *     the platform that the coverage tools referenced by the variable have been built for, or
   *     null if not known
   */
  public record CoverageEnvironmentValue(String value, @Nullable PlatformInfo toolPlatform) {}

  InstrumentedFilesInfo(
      NestedSet<Artifact> instrumentedFiles,
      NestedSet<Artifact> instrumentationMetadataFiles,
      NestedSet<Artifact> baselineCoverageArtifacts,
      NestedSet<Artifact> coverageSupportFiles,
      ImmutableMap<String, CoverageEnvironmentValue> coverageEnvironment,
      NestedSet<Tuple> reportedToActualSources) {
    this.instrumentedFiles = instrumentedFiles;
    this.instrumentationMetadataFiles = instrumentationMetadataFiles;
    this.baselineCoverageArtifacts = baselineCoverageArtifacts;
    this.coverageSupportFiles = coverageSupportFiles;
    this.coverageEnvironment = coverageEnvironment;
    this.reportedToActualSources = reportedToActualSources;
  }

  @Override
  public InstrumentedFilesProvider getProvider() {
    return STARLARK_CONSTRUCTOR;
  }

  /** The transitive closure of instrumented source files. */
  public NestedSet<Artifact> getInstrumentedFiles() {
    return instrumentedFiles;
  }

  @Override
  public Depset getInstrumentedFilesForStarlark() {
    return Depset.of(Artifact.class, instrumentedFiles);
  }

  /** Returns a collection of instrumentation metadata files. */
  public NestedSet<Artifact> getInstrumentationMetadataFiles() {
    return instrumentationMetadataFiles;
  }

  @Override
  public Depset getInstrumentationMetadataFilesForStarlark() {
    return Depset.of(Artifact.class, instrumentationMetadataFiles);
  }

  /**
   * Returns the output artifacts of the {@link BaselineCoverageAction}s for the transitive closure
   * of source files.
   */
  public NestedSet<Artifact> getBaselineCoverageArtifacts() {
    return baselineCoverageArtifacts;
  }

  /**
   * Extra files that are needed on the inputs of test actions for coverage collection to happen,
   * for example, {@code gcov}.
   *
   * <p>They aren't mentioned in the instrumented files manifest.
   */
  public NestedSet<Artifact> getCoverageSupportFiles() {
    return coverageSupportFiles;
  }

  @Override
  public Depset getCoverageSupportFilesForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return Depset.of(Artifact.class, coverageSupportFiles);
  }

  /** Environment variables that need to be set for tests collecting code coverage. */
  public ImmutableMap<String, CoverageEnvironmentValue> getCoverageEnvironment() {
    return coverageEnvironment;
  }

  @Override
  public ImmutableMap<String, String> getCoverageEnvironmentForStarlark(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return ImmutableMap.copyOf(
        Maps.transformValues(coverageEnvironment, CoverageEnvironmentValue::value));
  }

  /**
   * A set of pairs of reported source file path and the actual source file path, relative to the
   * workspace directory, if the two values are different. If the reported source file is the same
   * as the actual source path it will not be included in this set.
   *
   * <p>This is useful for virtual include paths in C++, which get reported at the include location
   * and not the real source path. For example, the reported include source file can be
   * "bazel-out/k8-fastbuild/bin/include/common/_virtual_includes/strategy/strategy.h", but its
   * actual source path is "include/common/strategy.h".
   */
  NestedSet<Tuple> getReportedToActualSources() {
    return reportedToActualSources;
  }

  /** Provider implementation for {@link InstrumentedFilesInfo}. */
  public static class InstrumentedFilesProvider extends BuiltinProvider<InstrumentedFilesInfo> {

    public InstrumentedFilesProvider() {
      super("InstrumentedFilesInfo", InstrumentedFilesInfo.class);
    }
  }
}
