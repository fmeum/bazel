// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link SkygraftExecutor}.
 *
 * <p>These tests verify that when only starlark build settings change, targets that don't
 * transitively depend on the changed settings are grafted (their analysis values are copied to new
 * keys) and thus aren't re-analyzed.
 */
@RunWith(JUnit4.class)
public final class SkygraftExecutorTest extends AnalysisTestCase {

  @Test
  public void skygraft_unaffectedTargetsNotReanalyzedWhenStarlarkOptionChanges() throws Exception {
    // Create a starlark flag
    scratch.file(
        "flags/BUILD",
        """
        load(":flag.bzl", "string_flag")
        string_flag(
            name = "my_flag",
            build_setting_default = "default_value",
        )
        """);
    scratch.file(
        "flags/flag.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);

    // Create a simple target that doesn't depend on the flag
    scratch.file(
        "pkg/BUILD",
        """
        genrule(
            name = "simple",
            outs = ["out.txt"],
            cmd = "echo hello > $@",
        )
        """);

    // First build - target is analyzed
    update("//pkg:simple");
    Set<ActionLookupKey> firstBuildKeys = getSkyframeEvaluatedTargetKeys();
    assertThat(firstBuildKeys.stream().anyMatch(k -> k.getLabel() != null && k.getLabel().toString().equals("//pkg:simple")))
        .isTrue();

    // Change the starlark option and rebuild
    // This should trigger skygraft which copies the unaffected target's value
    useConfiguration("--//flags:my_flag=new_value");
    update("//pkg:simple");

    ConfiguredTargetKey newKey =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//pkg:simple"))
            .setConfigurationKey(getTargetConfiguration().getKey())
            .build();
    assertThat(skyframeExecutor.getEvaluator().getExistingValue(newKey)).isNotNull();

    // Verify that //pkg:simple was NOT re-analyzed (it should be a cache hit from grafting)
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder().put("//pkg:simple", 0).buildOrThrow());
  }

  @Test
  public void skygraft_affectedTargetsAreReanalyzedWhenStarlarkOptionChanges() throws Exception {
    // Create a starlark flag
    scratch.file(
        "flags/BUILD",
        """
        load(":flag.bzl", "string_flag")
        string_flag(
            name = "my_flag",
            build_setting_default = "default_value",
        )
        """);
    scratch.file(
        "flags/flag.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);

    // Create a config_setting that reads the flag - this IS affected by the flag
    scratch.file(
        "pkg/BUILD",
        """
        config_setting(
            name = "flag_is_set",
            flag_values = {
                "//flags:my_flag": "some_value",
            },
        )
        """);

    // First build
    update("//pkg:flag_is_set");

    // Change the starlark option and rebuild
    useConfiguration("--//flags:my_flag=new_value");
    update("//pkg:flag_is_set");

    // Verify that //pkg:flag_is_set WAS re-analyzed (it's affected by the flag)
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder().put("//pkg:flag_is_set", 1).buildOrThrow());
  }

  @Test
  public void skygraft_transitivelyAffectedTargetsAreReanalyzed() throws Exception {
    // Create a starlark flag
    scratch.file(
        "flags/BUILD",
        """
        load(":flag.bzl", "string_flag")
        string_flag(
            name = "my_flag",
            build_setting_default = "default_value",
        )
        """);
    scratch.file(
        "flags/flag.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);

    // Create targets: unaffected -> affected (config_setting)
    scratch.file(
        "pkg/BUILD",
        """
        config_setting(
            name = "affected",
            flag_values = {
                "//flags:my_flag": "some_value",
            },
        )

        genrule(
            name = "depends_on_affected",
            outs = ["out.txt"],
            cmd = select({
                ":affected": "echo yes > $@",
                "//conditions:default": "echo no > $@",
            }),
        )

        genrule(
            name = "unaffected",
            outs = ["other.txt"],
            cmd = "echo hello > $@",
        )
        """);

    // First build
    update("//pkg:depends_on_affected", "//pkg:unaffected");

    // Change the starlark option and rebuild
    useConfiguration("--//flags:my_flag=new_value");
    update("//pkg:depends_on_affected", "//pkg:unaffected");

    // Verify:
    // - //pkg:unaffected was NOT re-analyzed (grafted)
    // - //pkg:depends_on_affected WAS re-analyzed (transitively affected through config_setting)
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//pkg:unaffected", 0)
            .put("//pkg:depends_on_affected", 1)
            .buildOrThrow());
  }
}
