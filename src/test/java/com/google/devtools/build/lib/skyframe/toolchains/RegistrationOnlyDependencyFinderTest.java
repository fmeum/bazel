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

package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RegistrationOnlyDependencyFinder}. */
@RunWith(JUnit4.class)
public class RegistrationOnlyDependencyFinderTest extends BuildViewTestCase {

  @Before
  public void setUpTargets() throws Exception {
    scratch.file(
        "rules/rules.bzl",
        """
        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            toolchains = [config_common.toolchain_type("//types:used", mandatory = False)],
        )
        """);
    scratch.file("rules/BUILD");
    scratch.file(
        "types/BUILD",
        """
        toolchain_type(name = "used")

        toolchain_type(name = "unused")
        """);
    scratch.file("platforms/BUILD", "platform(name = 'good')");
    scratch.file(
        "pkg/BUILD",
        """
        load("//rules:rules.bzl", "my_rule")

        my_rule(name = "target")
        """);
  }

  private void writeHeavyToolchain(String toolchainType) throws Exception {
    scratch.file(
        "heavy/BUILD",
        """
        toolchain(
            name = "tc",
            toolchain = ":impl",
            toolchain_type = "//types:%s",
        )

        filegroup(name = "impl")

        platform(name = "platform")
        """
            .formatted(toolchainType));
  }

  private ImmutableSortedSet<String> findRegistrationsRequiringHeavyPackage() {
    return new RegistrationOnlyDependencyFinder(
            getSkyframeExecutor().getEvaluator().getInMemoryGraph(),
            /* mainRepoMapping= */ null)
        .findRegistrationsRequiring(PackageIdentifier.createInMainRepo("heavy"));
  }

  @Test
  public void unusedToolchain() throws Exception {
    writeHeavyToolchain("unused");
    useConfiguration("--extra_toolchains=//heavy:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage())
        .containsExactly("toolchain //heavy:tc", "toolchain target pattern //heavy:all");
  }

  @Test
  public void usedToolchain() throws Exception {
    writeHeavyToolchain("used");
    useConfiguration("--extra_toolchains=//heavy:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage()).isEmpty();
  }

  @Test
  public void unusedToolchain_packageAlsoRequestedDirectly() throws Exception {
    writeHeavyToolchain("unused");
    useConfiguration("--extra_toolchains=//heavy:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();
    assertThat(getConfiguredTarget("//heavy:impl")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage()).isEmpty();
  }

  @Test
  public void unselectedExecutionPlatform() throws Exception {
    writeHeavyToolchain("unused");
    useConfiguration("--extra_execution_platforms=//platforms:good,//heavy:platform");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage())
        .containsExactly(
            "execution platform //heavy:platform",
            "execution platform target pattern //heavy:platform");
  }

  @Test
  public void selectedExecutionPlatform() throws Exception {
    writeHeavyToolchain("unused");
    useConfiguration("--extra_execution_platforms=//heavy:platform,//platforms:good");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage()).isEmpty();
  }

  @Test
  public void unselectedExecutionPlatformThatIsTargetPlatform() throws Exception {
    writeHeavyToolchain("unused");
    useConfiguration(
        "--extra_execution_platforms=//platforms:good,//heavy:platform",
        "--platforms=//heavy:platform");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavyPackage()).isEmpty();
  }
}
