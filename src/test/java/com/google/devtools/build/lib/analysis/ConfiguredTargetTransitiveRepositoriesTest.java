// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test that checks the collected transitive repositories of configured targets. */
@RunWith(JUnit4.class)
public final class ConfiguredTargetTransitiveRepositoriesTest extends AnalysisTestCase {

  @Before
  public void setUpToolsConfigMock() throws Exception {
    MockProtoSupport.setup(mockToolsConfig);
  }

  @Override
  protected boolean allowExternalRepositories() {
    // Transitive repositories are only stored when external repositories are enabled.
    return true;
  }

  private void assertTransitiveClosureOfTargetContainsRepositories(
      String target, BuildConfigurationValue config, String... repositories) throws Exception {
    ConfiguredTargetValue ctValue =
        SkyframeExecutorTestUtils.getExistingConfiguredTargetValue(
            skyframeExecutor, Label.parseCanonical(target), config);
    ImmutableSet<String> repositoryNames =
        ctValue.getTransitiveRepositories().toList().stream()
            .map(repoMetadata -> repoMetadata.repository().getName())
            .collect(toImmutableSet());
    assertThat(repositoryNames).containsAtLeastElementsIn(Sets.newHashSet(repositories));
  }

  private void assertTransitiveClosureOfTargetContainsTopLevelDirs(
      String target, BuildConfigurationValue config, String... topLevelDirs) throws Exception {
    ConfiguredTargetValue ctValue =
        SkyframeExecutorTestUtils.getExistingConfiguredTargetValue(
            skyframeExecutor, Label.parseCanonical(target), config);
    assertThat(ctValue.getTransitiveTopLevelDirs().toList())
        .containsAtLeastElementsIn(Sets.newHashSet(topLevelDirs));
  }

  @Test
  public void testSimpleConfiguredTarget() throws Exception {
    scratch.file("a/BUILD", "filegroup(name = 'a', srcs = [ '//a/b:b' ])");
    scratch.file("a/b/BUILD", "filegroup(name = 'b', srcs = [ '//c:c', '//d:d'] )");
    scratch.file("c/BUILD", "filegroup(name = 'c')");
    scratch.file("d/BUILD", "filegroup(name = 'd')");

    ConfiguredTarget target = Iterables.getOnlyElement(update("//a:a").getTargetsToBuild());
    BuildConfigurationValue config = getConfiguration(target);

    assertTransitiveClosureOfTargetContainsRepositories("//a:a", config, "");
    assertTransitiveClosureOfTargetContainsRepositories("//a/b:b", config, "");
    assertTransitiveClosureOfTargetContainsRepositories("//c:c", config, "");
    assertTransitiveClosureOfTargetContainsRepositories("//d:d", config, "");
    assertTransitiveClosureOfTargetContainsTopLevelDirs("//a:a", config, "a", "c", "d");
    assertTransitiveClosureOfTargetContainsTopLevelDirs("//a/b:b", config, "a", "c", "d");
    assertTransitiveClosureOfTargetContainsTopLevelDirs("//c:c", config, "c");
    assertTransitiveClosureOfTargetContainsTopLevelDirs("//d:d", config, "d");
  }

  @Test
  public void testPackagesFromAspects() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.EXTRA_ATTRIBUTE_ASPECT_RULE);
    scratch.file("extra/BUILD", "base(name = 'extra')");
    scratch.file(
        "a/c/BUILD",
        """
        rule_with_extra_deps_aspect(
            name = "foo",
            foo = [":bar"],
        )

        base(name = "bar")
        """);

    ConfiguredTarget target = Iterables.getOnlyElement(update("//a/c:foo").getTargetsToBuild());
    BuildConfigurationValue config = getConfiguration(target);

    assertTransitiveClosureOfTargetContainsRepositories("//a/c:foo", config, "");
  }

  @Test
  public void testTargetsWithConfiguration() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
        "cc_library(name = 'a', srcs = [ 'some.cpp' ])");

    ConfiguredTarget target = Iterables.getOnlyElement(update("//a:a").getTargetsToBuild());
    BuildConfigurationValue config = getConfiguration(target);

    // We expect to get the mock crosstool in transitive dependencies, because it's required for c++
    // configuration.
    assertTransitiveClosureOfTargetContainsRepositories(
        "//a:a", config, "", TestConstants.TOOLS_REPOSITORY.getName());
  }
}
