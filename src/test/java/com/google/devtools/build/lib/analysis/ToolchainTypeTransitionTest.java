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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for toolchain types with a configuration transition, declared via {@code
 * config_common.toolchain_type(..., cfg = ...)}.
 */
@RunWith(JUnit4.class)
public final class ToolchainTypeTransitionTest extends BuildViewTestCase {

  @Before
  public void setUpFlagsPlatformsAndToolchains() throws Exception {
    scratch.file(
        "flags/defs.bzl",
        """
        ModeInfo = provider(fields = ["mode"])

        def _string_flag_impl(ctx):
            return [ModeInfo(mode = ctx.build_setting_value)]

        string_flag = rule(
            implementation = _string_flag_impl,
            build_setting = config.string(flag = True),
        )

        def _to_b_impl(settings, attr):
            return {"//flags:mode": "b"}

        to_b = transition(
            implementation = _to_b_impl,
            inputs = [],
            outputs = ["//flags:mode"],
        )

        def _to_c_impl(settings, attr):
            return {"//flags:mode": "c"}

        to_c = transition(
            implementation = _to_c_impl,
            inputs = [],
            outputs = ["//flags:mode"],
        )

        def _from_attr_impl(settings, attr):
            return {"//flags:mode": attr.mode}

        from_attr = transition(
            implementation = _from_attr_impl,
            inputs = [],
            outputs = ["//flags:mode"],
        )

        def _split_impl(settings, attr):
            return [{"//flags:mode": "a"}, {"//flags:mode": "b"}]

        split = transition(
            implementation = _split_impl,
            inputs = [],
            outputs = ["//flags:mode"],
        )

        def _failing_impl(settings, attr):
            fail("toolchain transition failed on purpose")

        failing = transition(
            implementation = _failing_impl,
            inputs = [],
            outputs = ["//flags:mode"],
        )
        """);
    scratch.file(
        "flags/BUILD",
        """
        load("//flags:defs.bzl", "string_flag")

        string_flag(
            name = "mode",
            build_setting_default = "a",
        )

        config_setting(
            name = "mode_b",
            flag_values = {":mode": "b"},
        )
        """);
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "kind")

        constraint_value(
            name = "p1_kind",
            constraint_setting = ":kind",
        )

        constraint_value(
            name = "p2_kind",
            constraint_setting = ":kind",
        )

        platform(
            name = "p1",
            constraint_values = [":p1_kind"],
        )

        platform(
            name = "p2",
            constraint_values = [":p2_kind"],
        )
        """);
    scratch.file(
        "tc/defs.bzl",
        """
        load("//flags:defs.bzl", "ModeInfo")

        def _mode_toolchain_impl(ctx):
            return [platform_common.ToolchainInfo(mode = ctx.attr._mode[ModeInfo].mode)]

        mode_toolchain = rule(
            implementation = _mode_toolchain_impl,
            attrs = {"_mode": attr.label(default = "//flags:mode")},
        )
        """);
    scratch.file(
        "tc/BUILD",
        """
        load(":defs.bzl", "mode_toolchain")

        toolchain_type(name = "plain_type")

        toolchain_type(name = "transitioned_type")

        # The same toolchain target implements both toolchain types.
        mode_toolchain(name = "impl")

        # The plain type is available on all execution platforms.
        toolchain(
            name = "plain",
            toolchain = ":impl",
            toolchain_type = ":plain_type",
        )

        # The transitioned type is only available on p2 and only if the mode flag is set to "b".
        toolchain(
            name = "transitioned",
            exec_compatible_with = ["//platforms:p2_kind"],
            target_settings = ["//flags:mode_b"],
            toolchain = ":impl",
            toolchain_type = ":transitioned_type",
        )
        """);
    rewriteModuleDotBazel(
        """
        register_toolchains("//tc:all")
        register_execution_platforms("//platforms:p1", "//platforms:p2")
        """);
  }

  private static Object getResultField(ConfiguredTarget target, String provider, String field)
      throws Exception {
    StructImpl result =
        (StructImpl)
            target.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//rules:defs.bzl")), provider));
    assertThat(result).isNotNull();
    return result.getValue(field);
  }

  @Test
  public void toolchainResolvedAndBuiltInTransitionedConfiguration() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "ModeInfo", "to_b")

        Result = provider(fields = ["own_mode", "plain_mode", "transitioned_mode"])

        def _impl(ctx):
            return [Result(
                own_mode = ctx.attr._mode[ModeInfo].mode,
                plain_mode = ctx.toolchains["//tc:plain_type"].mode,
                transitioned_mode = ctx.toolchains["//tc:transitioned_type"].mode,
            )]

        my_rule = rule(
            implementation = _impl,
            attrs = {"_mode": attr.label(default = "//flags:mode")},
            toolchains = [
                "//tc:plain_type",
                config_common.toolchain_type("//tc:transitioned_type", cfg = to_b),
            ],
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "r")
        """);

    ConfiguredTarget target = getConfiguredTarget("//rules:r");

    assertThat(getResultField(target, "Result", "own_mode")).isEqualTo("a");
    // The plain type resolves to the toolchain built in the rule's own configuration...
    assertThat(getResultField(target, "Result", "plain_mode")).isEqualTo("a");
    // ...while the transitioned type resolves to the same toolchain target, but built in the
    // transitioned configuration in which its target_settings match.
    assertThat(getResultField(target, "Result", "transitioned_mode")).isEqualTo("b");
    // Both types share the execution platform, which has to be p2 for the transitioned type.
    assertThat(
            getRuleContext(target)
                .getToolchainContexts()
                .getDefaultToolchainContext()
                .executionPlatform()
                .label())
        .isEqualTo(Label.parseCanonical("//platforms:p2"));
  }

  @Test
  public void transitionReadsAttributes_optionalToolchainType() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "from_attr")

        Result = provider(fields = ["transitioned_mode"])

        def _impl(ctx):
            toolchain = ctx.toolchains["//tc:transitioned_type"]
            return [Result(transitioned_mode = toolchain.mode if toolchain else None)]

        my_rule = rule(
            implementation = _impl,
            attrs = {"mode": attr.string()},
            toolchains = [
                config_common.toolchain_type(
                    "//tc:transitioned_type",
                    mandatory = False,
                    cfg = from_attr,
                ),
            ],
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(
            name = "a",
            mode = "a",
        )

        my_rule(
            name = "b",
            mode = "b",
        )
        """);

    // There is no toolchain for the transitioned type in the configuration with mode "a".
    assertThat(getResultField(getConfiguredTarget("//rules:a"), "Result", "transitioned_mode"))
        .isEqualTo(Starlark.NONE);
    assertThat(getResultField(getConfiguredTarget("//rules:b"), "Result", "transitioned_mode"))
        .isEqualTo("b");
  }

  @Test
  public void execGroupToolchainTypeTransition() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct");
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "to_b")

        Result = provider(fields = ["plain_mode", "transitioned_mode"])

        def _impl(ctx):
            return [Result(
                plain_mode = ctx.toolchains["//tc:plain_type"].mode,
                transitioned_mode = ctx.exec_groups["eg"].toolchains["//tc:transitioned_type"].mode,
            )]

        my_rule = rule(
            implementation = _impl,
            toolchains = ["//tc:plain_type"],
            exec_groups = {
                "eg": exec_group(
                    toolchains = [
                        config_common.toolchain_type("//tc:transitioned_type", cfg = to_b),
                    ],
                ),
            },
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "r")
        """);

    ConfiguredTarget target = getConfiguredTarget("//rules:r");

    assertThat(getResultField(target, "Result", "plain_mode")).isEqualTo("a");
    assertThat(getResultField(target, "Result", "transitioned_mode")).isEqualTo("b");
    ToolchainCollection<ResolvedToolchainContext> toolchainContexts =
        getRuleContext(target).getToolchainContexts();
    // Each exec group selects its own execution platform.
    assertThat(toolchainContexts.getDefaultToolchainContext().executionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:p1"));
    assertThat(toolchainContexts.getToolchainContext("eg").executionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:p2"));
    assertThat(target.getProvider(RequiredConfigFragmentsProvider.class).starlarkOptions())
        .contains(Label.parseCanonical("//flags:mode"));
  }

  @Test
  public void aspectToolchainTypeTransition() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct");
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "to_b")

        AspectResult = provider(fields = ["transitioned_mode"])

        def _aspect_impl(target, ctx):
            return [AspectResult(
                transitioned_mode = ctx.toolchains["//tc:transitioned_type"].mode,
            )]

        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains = [config_common.toolchain_type("//tc:transitioned_type", cfg = to_b)],
        )

        def _dep_impl(ctx):
            return []

        dep_rule = rule(implementation = _dep_impl)

        Result = provider(fields = ["transitioned_mode"])

        def _consumer_impl(ctx):
            return [Result(transitioned_mode = ctx.attr.dep[AspectResult].transitioned_mode)]

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"dep": attr.label(aspects = [my_aspect])},
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "consumer", "dep_rule")

        dep_rule(name = "dep")

        consumer(
            name = "c",
            dep = ":dep",
        )
        """);

    assertThat(getResultField(getConfiguredTarget("//rules:c"), "Result", "transitioned_mode"))
        .isEqualTo("b");
    assertThat(
            getAspect("//rules:defs.bzl%my_aspect")
                .getProvider(RequiredConfigFragmentsProvider.class)
                .starlarkOptions())
        .contains(Label.parseCanonical("//flags:mode"));
  }

  @Test
  public void aspectPropagatesToToolchainInTransitionedConfiguration() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "from_attr")

        AspectResult = provider(fields = ["mode"])

        def _aspect_impl(target, ctx):
            if platform_common.ToolchainInfo in target:
                return [AspectResult(mode = target[platform_common.ToolchainInfo].mode)]
            return [ctx.rule.toolchains["//tc:transitioned_type"][AspectResult]]

        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["//tc:transitioned_type"],
        )

        def _dep_impl(ctx):
            return []

        dep_rule = rule(
            implementation = _dep_impl,
            attrs = {"mode": attr.string()},
            toolchains = [
                config_common.toolchain_type("//tc:transitioned_type", cfg = from_attr),
            ],
        )

        Result = provider(fields = ["transitioned_mode"])

        def _consumer_impl(ctx):
            return [Result(transitioned_mode = ctx.attr.dep[AspectResult].mode)]

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"dep": attr.label(aspects = [my_aspect])},
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "consumer", "dep_rule")

        dep_rule(
            name = "dep",
            mode = select({
                "//flags:mode_b": "c",
                "//conditions:default": "b",
            }),
        )

        consumer(
            name = "c",
            dep = ":dep",
        )
        """);

    // Both rule and aspect must evaluate select() in the rule's original configuration, and the
    // aspect must propagate to the toolchain configured with the resulting mode.
    assertThat(getResultField(getConfiguredTarget("//rules:c"), "Result", "transitioned_mode"))
        .isEqualTo("b");
  }

  @Test
  public void aspectToolchainTypeTransition_cannotReadAttributes() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "from_attr")

        def _aspect_impl(target, ctx):
            return []

        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains = [
                config_common.toolchain_type("//tc:transitioned_type", cfg = from_attr),
            ],
        )

        def _dep_impl(ctx):
            return []

        dep_rule = rule(
            implementation = _dep_impl,
            attrs = {"mode": attr.string()},
        )

        def _consumer_impl(ctx):
            return []

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"dep": attr.label(aspects = [my_aspect])},
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "consumer", "dep_rule")

        dep_rule(
            name = "dep",
            mode = "b",
        )

        consumer(
            name = "c",
            dep = ":dep",
        )
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//rules:c")).isNull();
    assertContainsEvent(
        "Error applying the 'cfg' transition of toolchain type //tc:transitioned_type:");
    assertContainsEvent("No attribute 'mode' in attr");
  }

  @Test
  public void noMatchingToolchainInTransitionedConfiguration_fails() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "to_c")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            toolchains = [config_common.toolchain_type("//tc:transitioned_type", cfg = to_c)],
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "r")
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//rules:r")).isNull();
    assertContainsEvent("No matching toolchains found for types:");
    assertContainsEvent("//tc:transitioned_type");
  }

  @Test
  public void failingTransition_reportsStarlarkError() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "failing")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            toolchains = [
                config_common.toolchain_type("//tc:transitioned_type", cfg = failing),
            ],
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "r")
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//rules:r")).isNull();
    assertContainsEvent("toolchain transition failed on purpose");
    assertContainsEvent(
        "Error applying the 'cfg' transition of toolchain type //tc:transitioned_type:");
  }

  @Test
  public void splitTransition_fails() throws Exception {
    scratch.file(
        "rules/defs.bzl",
        """
        load("//flags:defs.bzl", "split")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            toolchains = [config_common.toolchain_type("//tc:transitioned_type", cfg = split)],
        )
        """);
    scratch.file(
        "rules/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "r")
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//rules:r")).isNull();
    assertContainsEvent(
        "Error applying the 'cfg' transition of toolchain type //tc:transitioned_type: the"
            + " transition must not be a split transition, but it produced 2 configurations");
  }
}
