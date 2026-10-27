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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RegistrationDependencyFinder}. */
@RunWith(JUnit4.class)
public class RegistrationDependencyFinderTest extends BuildViewTestCase {

  private static final PackageIdentifier HEAVY = PackageIdentifier.createInMainRepo("heavy");

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
    scratch.file(
        "pkg/BUILD",
        """
        load("//rules:rules.bzl", "my_rule")

        my_rule(name = "target")
        """);
    scratch.file(
        "heavy/BUILD",
        """
        filegroup(name = "impl")

        platform(name = "platform")

        constraint_setting(name = "setting")

        constraint_value(
            name = "value",
            constraint_setting = ":setting",
        )
        """);
  }

  private ImmutableMap<SkyKey, String> findRegistrationsRequiringHeavy() {
    return RegistrationDependencyFinder.findRegistrationsRequiring(
        getSkyframeExecutor().getEvaluator().getInMemoryGraph(),
        /* mainRepoMapping= */ null,
        ImmutableSet.of(HEAVY));
  }

  private void writeToolchain(String pkg, String toolchainType, String extraAttributes)
      throws Exception {
    scratch.appendFile(
        pkg + "/BUILD",
        """
        toolchain(
            name = "tc",
            toolchain = "//heavy:impl",
            toolchain_type = "//types:%s",
            %s
        )
        """
            .formatted(toolchainType, extraAttributes));
  }

  @Test
  public void unusedToolchainInPackage() throws Exception {
    writeToolchain("heavy", "unused", "");
    useConfiguration("--extra_toolchains=//heavy:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy()).containsExactly(HEAVY, "toolchain //heavy:tc");
  }

  @Test
  public void usedToolchainInPackage() throws Exception {
    writeToolchain("heavy", "used", "");
    useConfiguration("--extra_toolchains=//heavy:tc");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy()).containsExactly(HEAVY, "toolchain //heavy:tc");
  }

  @Test
  public void registeredTargetPattern() throws Exception {
    useConfiguration("--extra_toolchains=//heavy:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy())
        .containsExactly(HEAVY, "toolchain target pattern //heavy:all");
  }

  @Test
  public void constraintOfToolchain() throws Exception {
    writeToolchain("light", "unused", "target_compatible_with = ['//heavy:value'],");
    useConfiguration("--extra_toolchains=//light:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy()).containsExactly(HEAVY, "toolchain //light:tc");
  }

  @Test
  public void implementationOfUnusedToolchain() throws Exception {
    writeToolchain("light", "unused", "");
    useConfiguration("--extra_toolchains=//light:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy()).isEmpty();
  }

  @Test
  public void implementationOfUsedToolchain() throws Exception {
    writeToolchain("light", "used", "");
    useConfiguration("--extra_toolchains=//light:all");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    // The implementation is analyzed, but not because of registration.
    assertThat(findRegistrationsRequiringHeavy()).isEmpty();
  }

  @Test
  public void executionPlatform() throws Exception {
    useConfiguration("--extra_execution_platforms=//heavy:platform");

    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy())
        .containsExactly(HEAVY, "execution platform //heavy:platform");
  }

  @Test
  public void notRegistered() throws Exception {
    assertThat(getConfiguredTarget("//pkg:target")).isNotNull();
    assertThat(getConfiguredTarget("//heavy:impl")).isNotNull();

    assertThat(findRegistrationsRequiringHeavy()).isEmpty();
  }
}
