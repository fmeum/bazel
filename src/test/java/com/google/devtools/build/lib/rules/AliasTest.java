// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the <code>alias</code> rule. */
@RunWith(JUnit4.class)
public class AliasTest extends BuildViewTestCase {
  @Test
  public void smoke() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            srcs = ["a.cc"],
        )

        alias(
            name = "b",
            actual = "a",
        )
        """);

    ConfiguredTarget b = getConfiguredTarget("//a:b");
    assertThat(CcInfo.get(b).getCcCompilationContext()).isNotNull();
  }

  @Test
  public void aliasToInputFile() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        exports_files(["a"])

        alias(
            name = "b",
            actual = "a",
        )
        """);

    ConfiguredTarget b = getConfiguredTarget("//a:b");
    assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(b))).containsExactly("a");
  }

  @Test
  public void visibilityIsOverriddenAndIsOkay() throws Exception {
    scratch.file("a/BUILD",
        "filegroup(name='a', visibility=['//b:__pkg__'])");
    scratch.file("b/BUILD",
        "alias(name='b', actual='//a:a', visibility=['//visibility:public'])");
    scratch.file("c/BUILD",
        "filegroup(name='c', srcs=['//b:b'])");

    getConfiguredTarget("//c:c");
  }

  @Test
  public void visibilityIsOverriddenAndIsError() throws Exception {
    scratch.file("a/BUILD",
        "filegroup(name='a', visibility=['//visibility:public'])");
    scratch.file("b/BUILD",
        "alias(name='b', actual='//a:a', visibility=['//visibility:private'])");
    scratch.file("c/BUILD",
        "filegroup(name='c', srcs=['//b:b'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//c:c");
    assertContainsEvent(
        "alias '//b:b' referring to target '//a:a' is not visible from\ntarget '//c:c'");
  }

  @Test
  public void visibilityIsOverriddenAndIsErrorAfterMultipleAliases() throws Exception {
    scratch.file("a/BUILD",
        "filegroup(name='a', visibility=['//visibility:public'])");
    scratch.file("b/BUILD",
        "alias(name='b', actual='//a:a', visibility=['//visibility:public'])");
    scratch.file("c/BUILD",
        "alias(name='c', actual='//b:b', visibility=['//visibility:private'])");
    scratch.file("d/BUILD",
        "filegroup(name='d', srcs=['//c:c'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//d:d");
    assertContainsEvent(
        "alias '//c:c' referring to target '//a:a' through '//b:b' "
            + "is not visible from\ntarget '//d:d'");
  }

  @Test
  public void testAliasWithPrivateVisibilityAccessibleFromSamePackage() throws Exception {
    scratch.file("a/BUILD", "exports_files(['af'])");
    scratch.file(
        "b/BUILD",
        """
        package(default_visibility = ["//visibility:private"])

        alias(
            name = "al",
            actual = "//a:af",
        )

        filegroup(
            name = "ta",
            srcs = [":al"],
        )
        """);

    getConfiguredTarget("//b:ta");
  }

  @Test
  public void testAliasCycle() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        alias(
            name = "a",
            actual = ":b",
        )

        alias(
            name = "b",
            actual = ":c",
        )

        alias(
            name = "c",
            actual = ":a",
        )

        filegroup(
            name = "d",
            srcs = [":c"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:d");
    assertContainsEvent("cycle in dependency graph");
  }

  @Test
  public void testAliasedInvalidDependency() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            deps = [":b"],
        )

        alias(
            name = "b",
            actual = ":c",
        )

        filegroup(name = "c")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:a");
    assertContainsEvent("alias '//a:b' referring to filegroup rule '//a:c' is misplaced here");
  }

  @Test
  public void testAspectPropagation() throws Exception {
    writeConfigTransitionTestFiles();
    scratch.file(
        "test/aspect.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(target, ctx):
            if not target[MyInfo]:
                fail("missing MyInfo")
            if target[MyInfo].config != ctx.configuration:
                fail("mismatched configs")
            return MyInfo(
                origin = "aspect",
                config = target[MyInfo].config,
            )

        MyAspect = aspect(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        String.format(
            """
            alias(
                name = "simple_alias",
                actual = "//test/starlark:test",
            )

            alias(
                name = "selecting_alias",
                actual = select(
                  {"%s": ":simple_alias"}
                ),
            )
            """,
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64"));

    // Set --platforms so we can test alias :selecting_alias that selects on the CPU.
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);

    // 1. Query "actual" target to establish reference values to compare to below. Make some basic
    // assertions that tie aspect's config to underlying target.
    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test/starlark:test"),
            ImmutableList.of("//test:aspect.bzl%MyAspect"),
            true,
            1,
            true,
            eventBus);
    assertThat(analysisResult.getTargetsToBuild()).hasSize(1);
    assertThat(analysisResult.getAspectsMap()).hasSize(1);

    ConfiguredTarget actualTarget = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    ConfiguredAspect aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    AspectKey actualKey = Iterables.getOnlyElement(analysisResult.getAspectsMap().keySet());
    assertThat(actualKey.getBaseConfiguredTargetKey().getConfigurationKey())
        .isEqualTo(actualTarget.getConfigurationKey());
    assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect");
    BuildConfigurationValue actualConfig =
        (BuildConfigurationValue) getMyInfoFromTarget(aspect).getValue("config");
    assertThat(actualKey.getBaseConfiguredTargetKey().getConfigurationKey().getOptions().checksum())
        .isEqualTo(actualConfig.checksum());

    // 2. Query :simple_alias and assert that its aspect value is the same as above.
    analysisResult =
        update(
            ImmutableList.of("//test:simple_alias"),
            ImmutableList.of("//test:aspect.bzl%MyAspect"),
            true,
            1,
            true,
            eventBus);
    assertThat(analysisResult.getTargetsToBuild()).hasSize(1);
    assertThat(analysisResult.getAspectsMap()).hasSize(1);

    ConfiguredTarget alias = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    assertThat(alias.getActual()).isEqualTo(actualTarget);
    // Alias and actual must have different configs for this test to be meaningful
    assertThat(alias.getConfigurationKey()).isNotEqualTo(alias.getActual().getConfigurationKey());
    AspectKey aspectKey = Iterables.getOnlyElement(analysisResult.getAspectsMap().keySet());
    assertThat(aspectKey.getBaseConfiguredTargetKey().getConfigurationKey())
        .isEqualTo(alias.getConfigurationKey());

    aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect");
    // We should be seeing actual's config here
    assertThat(getMyInfoFromTarget(aspect).getValue("config")).isEqualTo(actualConfig);

    // 3. Do the same with :selecting_alias, which is an indirect alias through :simple_alias.
    // This alias also uses a (non-trivial) select to resolve its actual.
    analysisResult =
        update(
            ImmutableList.of("//test:selecting_alias"),
            ImmutableList.of("//test:aspect.bzl%MyAspect"),
            true,
            1,
            true,
            eventBus);
    assertThat(analysisResult.getTargetsToBuild()).hasSize(1);
    assertThat(analysisResult.getAspectsMap()).hasSize(1);

    ConfiguredTarget indirectAlias = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    assertThat(indirectAlias.getActual()).isEqualTo(actualTarget);
    assertThat(indirectAlias.getConfigurationKey()).isEqualTo(alias.getConfigurationKey());

    aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect");
    assertThat(getMyInfoFromTarget(aspect).getValue("config")).isEqualTo(actualConfig);
  }

  private void writeAllowlistFile() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        """);
  }

  private static StructImpl getMyInfoFromTarget(ConfiguredAspect configuredAspect)
      throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) configuredAspect.get(key);
  }

  public void setupMyInfo() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");
    scratch.file("myinfo/BUILD");
  }

  private void writeConfigTransitionTestFiles() throws Exception {
    writeAllowlistFile();
    setupMyInfo();
    getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a");
    scratch.file(
        "test/starlark/my_rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                {"//command_line_option:cpu": "k8"},
                {"//command_line_option:cpu": "armeabi-v7a"},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            print(ctx.label, ctx.configuration)
            return MyInfo(
                config = ctx.configuration,
                attr_deps = ctx.split_attr.deps,
                attr_dep = ctx.split_attr.dep,
            )

        my_rule = rule(
            implementation = impl,
            attrs = {
                "deps": attr.label_list(cfg = my_transition),
                "dep": attr.label(cfg = my_transition),
            },
        )
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
            deps = [
                ":main1",
                ":main2",
            ],
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )

        cc_binary(
            name = "main2",
            srcs = ["main2.c"],
        )
        """);
  }

  private void writeTransitionedAliasFiles() throws Exception {
    writeAllowlistFile();
    setupMyInfo();
    scratch.file(
        "test/transitioned_alias.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//command_line_option:compilation_mode": attr.compilation_mode}

        _compilation_mode_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        transitioned_alias = rule(
            parent = native.alias,
            cfg = _compilation_mode_transition,
            attrs = {
                "compilation_mode": attr.string(mandatory = True),
            },
            doc = "An alias that transitions to the given compilation mode.",
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        AspectInfo = provider()

        def _aspect_impl(target, ctx):
            return [AspectInfo(
                label = target.label,
                compilation_mode = ctx.var["COMPILATION_MODE"],
            )]

        my_aspect = aspect(implementation = _aspect_impl)

        def _leaf_impl(ctx):
            return [MyInfo(
                label = ctx.label,
                compilation_mode = ctx.var["COMPILATION_MODE"],
            )]

        leaf = rule(implementation = _leaf_impl)

        def _consumer_impl(ctx):
            return [MyInfo(
                dep = ctx.attr.dep,
                dep_label = ctx.attr.dep.label,
                dep_compilation_mode = ctx.attr.dep[MyInfo].compilation_mode,
                aspect_info = ctx.attr.dep[AspectInfo] if AspectInfo in ctx.attr.dep else None,
                compilation_mode = ctx.var["COMPILATION_MODE"],
            )]

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"dep": attr.label()},
        )

        consumer_with_aspect = rule(
            implementation = _consumer_impl,
            attrs = {"dep": attr.label(aspects = [my_aspect])},
        )
        """);
  }

  private StructImpl getMyInfo(ConfiguredTarget target) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) target.get(key);
  }

  @Test
  public void transitionedAlias_forwardsProvidersOfActualInTransitionedConfiguration()
      throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "consumer", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        leaf(name = "leaf")

        transitioned_alias(
            name = "dbg_leaf",
            actual = ":leaf",
            compilation_mode = "dbg",
        )

        consumer(
            name = "consumer",
            dep = ":dbg_leaf",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    ConfiguredTarget consumer = getConfiguredTarget("//test:consumer");
    StructImpl consumerInfo = getMyInfo(consumer);
    assertThat(consumerInfo.getValue("compilation_mode")).isEqualTo("fastbuild");
    assertThat(consumerInfo.getValue("dep_compilation_mode")).isEqualTo("dbg");
    assertThat(consumerInfo.getValue("dep_label"))
        .isEqualTo(Label.parseCanonical("//test:leaf"));

    ConfiguredTarget dep = (ConfiguredTarget) consumerInfo.getValue("dep");
    assertThat(dep).isInstanceOf(AliasConfiguredTarget.class);
    assertThat(dep.getProvider(AliasProvider.class).getAliasChain())
        .containsExactly(Label.parseCanonical("//test:dbg_leaf"));
    assertThat(dep.getOriginalLabel()).isEqualTo(Label.parseCanonical("//test:dbg_leaf"));
    assertThat(dep.getActual().getLabel()).isEqualTo(Label.parseCanonical("//test:leaf"));
    // The alias itself is analyzed in the transitioned configuration, as is the actual target.
    assertThat(dep.getConfigurationKey()).isNotEqualTo(consumer.getConfigurationKey());
    assertThat(dep.getConfigurationKey()).isEqualTo(dep.getActual().getConfigurationKey());
    assertThat(getMyInfo(dep).getValue("compilation_mode")).isEqualTo("dbg");
  }

  @Test
  public void transitionedAlias_topLevel() throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        leaf(name = "leaf")

        transitioned_alias(
            name = "dbg_leaf",
            actual = ":leaf",
            compilation_mode = "dbg",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    ConfiguredTarget alias = getConfiguredTarget("//test:dbg_leaf");
    assertThat(alias).isInstanceOf(AliasConfiguredTarget.class);
    assertThat(getMyInfo(alias).getValue("compilation_mode")).isEqualTo("dbg");
    assertThat(getMyInfo(alias).getValue("label"))
        .isEqualTo(Label.parseCanonical("//test:leaf"));
  }

  @Test
  public void transitionedAlias_selectResolvedInTransitionedConfiguration() throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "consumer", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        config_setting(
            name = "is_dbg",
            values = {"compilation_mode": "dbg"},
        )

        leaf(name = "dbg_only")

        leaf(name = "other")

        transitioned_alias(
            name = "selecting",
            actual = select({
                ":is_dbg": ":dbg_only",
                "//conditions:default": ":other",
            }),
            compilation_mode = "dbg",
        )

        consumer(
            name = "consumer",
            dep = ":selecting",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    StructImpl consumerInfo = getMyInfo(getConfiguredTarget("//test:consumer"));
    assertThat(consumerInfo.getValue("dep_label"))
        .isEqualTo(Label.parseCanonical("//test:dbg_only"));
    assertThat(consumerInfo.getValue("dep_compilation_mode")).isEqualTo("dbg");
  }

  @Test
  public void transitionedAlias_chainedWithNativeAliases() throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "consumer", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        leaf(name = "leaf")

        alias(
            name = "inner_alias",
            actual = ":leaf",
        )

        transitioned_alias(
            name = "dbg_alias",
            actual = ":inner_alias",
            compilation_mode = "dbg",
        )

        alias(
            name = "outer_alias",
            actual = ":dbg_alias",
        )

        consumer(
            name = "consumer",
            dep = ":outer_alias",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    StructImpl consumerInfo = getMyInfo(getConfiguredTarget("//test:consumer"));
    assertThat(consumerInfo.getValue("dep_label")).isEqualTo(Label.parseCanonical("//test:leaf"));
    assertThat(consumerInfo.getValue("dep_compilation_mode")).isEqualTo("dbg");
    ConfiguredTarget dep = (ConfiguredTarget) consumerInfo.getValue("dep");
    assertThat(dep.getProvider(AliasProvider.class).getAliasChain())
        .containsExactly(
            Label.parseCanonical("//test:outer_alias"),
            Label.parseCanonical("//test:dbg_alias"),
            Label.parseCanonical("//test:inner_alias"))
        .inOrder();
  }

  @Test
  public void transitionedAlias_aspectPropagatesToActualInTransitionedConfiguration()
      throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "consumer_with_aspect", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        leaf(name = "leaf")

        transitioned_alias(
            name = "dbg_leaf",
            actual = ":leaf",
            compilation_mode = "dbg",
        )

        consumer_with_aspect(
            name = "consumer",
            dep = ":dbg_leaf",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    StructImpl consumerInfo = getMyInfo(getConfiguredTarget("//test:consumer"));
    StructImpl aspectInfo = (StructImpl) consumerInfo.getValue("aspect_info");
    assertThat(aspectInfo).isNotNull();
    assertThat(aspectInfo.getValue("label")).isEqualTo(Label.parseCanonical("//test:leaf"));
    assertThat(aspectInfo.getValue("compilation_mode")).isEqualTo("dbg");
  }

  @Test
  public void transitionedAlias_topLevelAspect() throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":rules.bzl", "leaf")
        load(":transitioned_alias.bzl", "transitioned_alias")

        leaf(name = "leaf")

        transitioned_alias(
            name = "dbg_leaf",
            actual = ":leaf",
            compilation_mode = "dbg",
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:dbg_leaf"),
            ImmutableList.of("//test:rules.bzl%my_aspect"),
            true,
            1,
            true,
            eventBus);
    ConfiguredAspect aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StructImpl aspectInfo =
        (StructImpl)
            aspect.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//test:rules.bzl")), "AspectInfo"));
    assertThat(aspectInfo.getValue("label")).isEqualTo(Label.parseCanonical("//test:leaf"));
    assertThat(aspectInfo.getValue("compilation_mode")).isEqualTo("dbg");
  }

  @Test
  public void transitionedAlias_toInputFile() throws Exception {
    writeTransitionedAliasFiles();
    scratch.file(
        "test/BUILD",
        """
        load(":transitioned_alias.bzl", "transitioned_alias")

        exports_files(["file.txt"])

        transitioned_alias(
            name = "dbg_file",
            actual = "file.txt",
            compilation_mode = "dbg",
        )

        filegroup(
            name = "files",
            srcs = [":dbg_file"],
        )
        """);

    ConfiguredTarget files = getConfiguredTarget("//test:files");
    assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(files)))
        .containsExactly("file.txt");
  }

  @Test
  public void transitionedAlias_starlarkTransitionAllowlistIsEnforced() throws Exception {
    writeTransitionedAliasFiles();
    scratch.overwriteFile(
        TestConstants.TOOLS_REPOSITORY_SCRATCH
            + "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//test/..."],
        )
        """);
    scratch.file("test/BUILD");
    scratch.file(
        "not_allowlisted/defs.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//command_line_option:compilation_mode": "dbg"}

        _dbg_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        dbg_alias = rule(
            parent = native.alias,
            cfg = _dbg_transition,
        )
        """);
    scratch.file(
        "not_allowlisted/BUILD",
        """
        load("//test:rules.bzl", "leaf")
        load(":defs.bzl", "dbg_alias")

        leaf(name = "leaf")

        dbg_alias(
            name = "dbg_leaf",
            actual = ":leaf",
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//not_allowlisted:dbg_leaf");
    assertContainsEvent("Non-allowlisted use of Starlark transition");
  }

  @Test
  public void transitionedAlias_implementationNotAllowed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            pass

        bad_alias = rule(
            implementation = _impl,
            parent = native.alias,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(":defs.bzl", "bad_alias")

        filegroup(name = "files")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:files");
    assertContainsEvent("implementation must be omitted when extending native.alias");
  }

  @Test
  public void transitionedAlias_unsupportedParameter() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        bad_alias = rule(
            parent = native.alias,
            toolchains = ["//test:toolchain_type"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(":defs.bzl", "bad_alias")

        filegroup(name = "files")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:files");
    assertContainsEvent(
        "toolchains is not supported when extending native.alias. Only attrs, cfg, doc and"
            + " initializer may be set.");
  }

  @Test
  public void transitionedAlias_cannotRedefineActual() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        bad_alias = rule(
            parent = native.alias,
            attrs = {"actual": attr.label()},
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(":defs.bzl", "bad_alias")

        filegroup(name = "files")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:files");
    assertContainsEvent("attribute `actual`: built-in attributes cannot be overridden.");
  }

  @Test
  public void transitionedAlias_cannotBeExtended() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        my_alias = rule(
            parent = native.alias,
        )

        def _impl(ctx):
            return ctx.super()

        my_alias_extension = rule(
            implementation = _impl,
            parent = my_alias,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(":defs.bzl", "my_alias_extension")

        filegroup(name = "files")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:files");
    assertContainsEvent("The rule 'my_alias' is not extendable.");
  }

  @Test
  public void assertNoLicensesAttribute() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        filegroup(name = "a")

        alias(
            name = "b",
            actual = ":a",
            licenses = ["unencumbered"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:b");
    assertContainsEvent("no such attribute 'licenses' in 'alias' rule");
  }

  @Test
  public void passesTargetTypeCheck() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            srcs = ["a.cc"],
            deps = [":b"],
        )

        alias(
            name = "b",
            actual = ":c",
        )

        cc_library(
            name = "c",
            srcs = ["c.cc"],
        )
        """);

    getConfiguredTarget("//a:a");
  }

  @Test
  public void packageGroupInAlias() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        package_group(
            name = "a",
            packages = ["//a"],
        )

        alias(
            name = "b",
            actual = ":a",
        )

        filegroup(
            name = "c",
            srcs = [":b"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:c");
    assertContainsEvent(
        "in actual attribute of alias rule //a:b: package group '//a:a' is misplaced here");
  }

  @Test
  public void aliasedFile() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        exports_files(["a"])

        alias(
            name = "b",
            actual = "a",
        )

        filegroup(
            name = "c",
            srcs = [":b"],
        )
        """);

    ConfiguredTarget c = getConfiguredTarget("//a:c");
    assertThat(ActionsTestUtil.baseArtifactNames(
        c.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("a");
  }

  @Test
  public void aliasedConfigSetting() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        filegroup(
            name = "a",
            srcs = select({
                ":b": ["f1"],
                "//conditions:default": ["f2"],
            }),
        )

        alias(
            name = "b",
            actual = ":c",
        )

        config_setting(
            name = "c",
            values = {"define": "foo=bar"},
        )
        """);

    useConfiguration("--define=foo=bar");
    getConfiguredTarget("//a");
  }

  @Test
  public void aliasedTestSuiteDep() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name='a', srcs=['a.sh'])");
    scratch.file(
        "b/BUILD",
        """
        alias(
            name = "b",
            testonly = 1,
            actual = "//a:a",
        )

        test_suite(
            name = "c",
            tests = [":b"],
        )
        """);

    ConfiguredTarget c = getConfiguredTarget("//b:c");
    NestedSet<Artifact> runfiles =
        c.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts();
    assertThat(ActionsTestUtil.baseArtifactNames(runfiles)).contains("a.sh");
  }

  @Test
  public void testRedirectChasing() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
        "alias(name='cc', actual='" + TestConstants.PLATFORM_LABEL + "')",
        "cc_library(name='a', srcs=['a.cc'])");

    useConfiguration("--platforms=" + "//a:cc");
    getConfiguredTarget("//a:a");
  }

  @Test
  public void testNoActual() throws Exception {
    checkError("a", "a", "missing value for mandatory attribute 'actual'", "alias(name='a')");
  }
}
