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

package com.google.devtools.build.lib.starlark;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the {@code cmd} module and {@code ctx.actions.run_script}. */
@RunWith(JUnit4.class)
public final class StarlarkCmdTest extends BuildViewTestCase {

  @Before
  public void enableCmd() throws Exception {
    setBuildLanguageOptions("--experimental_starlark_cmd");
  }

  /** Defines a rule whose implementation body is given, with srcs, tool and out attributes. */
  private void defineRule(String... implBody) throws Exception {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    lines.add("def _impl(ctx):");
    lines.add("    out = ctx.outputs.out");
    for (String line : implBody) {
      lines.add("    " + line);
    }
    lines.add("    return [DefaultInfo(files = depset([out]))]");
    lines.add("");
    lines.add("cmd_rule = rule(");
    lines.add("    implementation = _impl,");
    lines.add("    attrs = {");
    lines.add("        'srcs': attr.label_list(allow_files = True),");
    lines.add("        'tool': attr.label(cfg = 'exec', executable = True, allow_files = True),");
    lines.add("        'out': attr.output(),");
    lines.add("    },");
    lines.add("    toolchains = [cmd.toolchain_type],");
    lines.add(")");
    scratch.overwriteFile("pkg/rule.bzl", lines.build().toArray(new String[0]));
    scratch.overwriteFile(
        "pkg/BUILD",
        """
        load(":rule.bzl", "cmd_rule")

        cmd_rule(
            name = "gen",
            srcs = ["a.txt", "b.txt"],
            tool = ":tool",
            out = "gen.out",
        )

        filegroup(name = "tool", srcs = ["tool.sh"])
        """);
  }

  private SpawnAction getGenAction() throws Exception {
    ConfiguredTarget target = getConfiguredTarget("//pkg:gen");
    List<ActionAnalysisMetadata> actions =
        ((RuleConfiguredTarget) target)
            .getActions().stream().filter(a -> a instanceof SpawnAction).toList();
    return (SpawnAction) Iterables.getOnlyElement(actions);
  }

  private String binPath(String name, String label) throws Exception {
    return getBinArtifact(name, getConfiguredTarget(label)).getExecPathString();
  }

  private static ImmutableList<String> execPaths(Iterable<Artifact> artifacts) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (Artifact artifact : artifacts) {
      result.add(artifact.getExecPathString());
    }
    return result.build();
  }

  @Test
  public void pipelineWithRedirect_infersInputsAndOutputsAndSerializesScript() throws Exception {
    defineRule(
        "script = cmd.cat(ctx.files.srcs) | cmd.grep('TODO', ignore_case = True) > out",
        "ctx.actions.run_script(script, mnemonic = 'Todos')");

    SpawnAction action = getGenAction();

    assertThat(action.getMnemonic()).isEqualTo("Todos");
    assertThat(execPaths(action.getInputs().toList())).containsAtLeast("pkg/a.txt", "pkg/b.txt");
    assertThat(execPaths(action.getOutputs()))
        .containsExactly(binPath("gen.out", "//pkg:gen"));
    // The runner comes from the toolchain and the script is written to a params file.
    List<String> args = action.getArguments();
    assertThat(args.get(0)).endsWith("tools/cmd/cmd_runner");
    assertThat(args)
        .containsAtLeast(
            "cmd_runner 1",
            "pipe",
            "cmd",
            "builtin cat",
            "arg --",
            "path pkg/a.txt",
            "path pkg/b.txt",
            "end",
            "builtin grep",
            "arg --ignore-case",
            "arg TODO",
            "stdout " + binPath("gen.out", "//pkg:gen"))
        .inOrder();
    assertThat(action.getCommandLines().unpack()).hasSize(2);
    assertThat(action.getCommandLines().unpack().get(1).paramFileInfo).isNotNull();
  }

  @Test
  public void externalTool_isAddedAsToolAndSubstitutionsAreNested() throws Exception {
    defineRule(
        "version = cmd(ctx.executable.tool, '--version')",
        "script = cmd(ctx.executable.tool, '--out', out, '--tag', version, ctx.files.srcs[0])",
        "ctx.actions.run_script(script, outputs = [out], mnemonic = 'Gen')");

    SpawnAction action = getGenAction();

    assertThat(execPaths(action.getTools().toList())).contains("pkg/tool.sh");
    assertThat(execPaths(action.getInputs().toList())).containsAtLeast("pkg/tool.sh", "pkg/a.txt");
    assertThat(execPaths(action.getOutputs()))
        .containsExactly(binPath("gen.out", "//pkg:gen"));
    assertThat(action.getArguments())
        .containsAtLeast(
            "tool pkg/tool.sh",
            "arg --out",
            "path " + binPath("gen.out", "//pkg:gen"),
            "arg --tag",
            "sub",
            "cmd",
            "tool pkg/tool.sh",
            "arg --version",
            "end",
            "path pkg/a.txt",
            "end")
        .inOrder();
  }

  @Test
  public void redirectsEnvAndCwd_areSerialized() throws Exception {
    defineRule(
        "out2 = ctx.actions.declare_file(ctx.label.name + '.second')",
        "out_dir = ctx.actions.declare_directory(ctx.label.name + '.dir')",
        "script = [",
        "    cmd.mkdir(out_dir),",
        "    (cmd('python3', 'gen.py').env(LANG = 'C', FOO = 'a\\nb').cwd(out_dir)",
        "        << 'input text' >> out2).stderr_to_stdout(),",
        "    cmd.touch(out) < ctx.files.srcs[0],",
        "]",
        "ctx.actions.run_script(script, mnemonic = 'Gen', use_default_shell_env = True)");

    SpawnAction action = getGenAction();

    String outDir = binPath("gen.dir", "//pkg:gen");
    assertThat(execPaths(action.getOutputs()))
        .containsExactly(
            outDir,
            binPath("gen.second", "//pkg:gen"),
            binPath("gen.out", "//pkg:gen"));
    assertThat(action.getArguments())
        .containsAtLeast(
            "builtin mkdir",
            "arg --",
            "path " + outDir,
            "end",
            "cmd",
            "prog python3",
            "arg gen.py",
            "env LANG=C",
            "env FOO=a\\nb",
            "cwd " + outDir,
            "stdin_text input text",
            "stdout_append " + binPath("gen.second", "//pkg:gen"),
            "stderr_to_stdout",
            "end",
            "cmd",
            "builtin touch",
            "arg --",
            "path " + binPath("gen.out", "//pkg:gen"),
            "stdin pkg/a.txt",
            "end")
        .inOrder();
  }

  @Test
  public void fileOnLeftOfPipe_readsStdinFromFile() throws Exception {
    defineRule(
        "script = ctx.files.srcs[0] | cmd.sort(unique = True) | cmd.head(lines = 3) > out",
        "ctx.actions.run_script(script, mnemonic = 'Gen')");

    SpawnAction action = getGenAction();

    assertThat(action.getArguments())
        .containsAtLeast("builtin sort", "arg --unique", "arg --", "stdin pkg/a.txt", "end")
        .inOrder();
    assertThat(action.getArguments()).containsAtLeast("builtin head", "arg --lines=3").inOrder();
  }

  @Test
  public void repr_isReadable() throws Exception {
    defineRule(
        "script = (ctx.files.srcs[0] | cmd.grep('x', fixed = True)"
            + " | cmd('tool', 'a', 1).env(A = 'b')) > out",
        "print(repr(script))",
        "ctx.actions.run_script(script, mnemonic = 'Gen')");

    getGenAction();

    assertContainsEvent(
        "cmd.grep(\"x\") < <source file pkg/a.txt> | cmd(\"tool\", \"a\", \"1\").env(A ="
            + " \"b\") > <generated file pkg/gen.out>");
  }

  @Test
  public void redirectingToCommand_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule("script = cmd.cat(out) > cmd.sort()", "ctx.actions.run_script(script)");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("unsupported binary operation: Command > Command (use | to connect");
  }

  @Test
  public void duplicateRedirect_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule(
        "out2 = ctx.actions.declare_file(ctx.label.name + '.second')",
        "script = (cmd.cat(out) > out2) > out",
        "ctx.actions.run_script(script)");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("the standard output of cmd.cat(<generated file pkg/gen.out>) >");
    assertContainsEvent("is already redirected");
  }

  @Test
  public void pipingRedirectedOutput_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule(
        "out2 = ctx.actions.declare_file(ctx.label.name + '.second')",
        "script = (cmd.cat(out) > out2) | cmd.sort()",
        "ctx.actions.run_script(script)");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("cannot pipe the output of");
    assertContainsEvent("it is already redirected to a file");
  }

  @Test
  public void noOutputs_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule("ctx.actions.run_script(cmd.cat(ctx.files.srcs), mnemonic = 'Gen')");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("the script has no outputs");
  }

  @Test
  public void writingToSourceFile_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule(
        "ctx.actions.run_script(cmd.echo('x') > ctx.files.srcs[0], mnemonic = 'Gen')");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("the script writes to pkg/a.txt, which is a source file");
  }

  @Test
  public void cwdOnBuiltin_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineRule(
        "out_dir = ctx.actions.declare_directory(ctx.label.name + '.dir')",
        "ctx.actions.run_script(cmd.cat(out_dir).cwd(out_dir) > out, mnemonic = 'Gen')");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("cwd is not supported for builtins");
  }

  @Test
  public void missingToolchain_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "other/rule.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file("out")
            ctx.actions.run_script(cmd.echo("hi") > out, mnemonic = "Gen")
            return [DefaultInfo(files = depset([out]))]

        no_toolchain_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "other/BUILD",
        """
        load(":rule.bzl", "no_toolchain_rule")

        no_toolchain_rule(name = "gen")
        """);

    getConfiguredTarget("//other:gen");

    assertContainsEvent(
        "actions.run_script requires the rule to declare the toolchain type"
            + " @@bazel_tools//tools/cmd:toolchain_type");
  }

  @Test
  public void flagDisabled_cmdIsUnavailable() throws Exception {
    setBuildLanguageOptions("--noexperimental_starlark_cmd");
    reporter.removeHandler(failFastHandler);
    defineRule("ctx.actions.run_script(cmd.echo('hi') > out, mnemonic = 'Gen')");

    getConfiguredTarget("//pkg:gen");

    assertContainsEvent("--experimental_starlark_cmd");
  }

  @Test
  public void scriptRule_resolvesLabelsAndInfersOutputs() throws Exception {
    scratch.file("app/main.cc", "// TODO: something");
    scratch.file("app/gen.sh", "");
    scratch.file(
        "app/BUILD",
        """
        load("@bazel_tools//tools/cmd:defs.bzl", "script")

        script(
            name = "todos",
            srcs = ["main.cc", ":lib"],
            outs = ["todos.txt", "gen/header.h"],
            tools = [":gen"],
            cmd = [
                cmd.grep("TODO", "main.cc") | cmd.sort() > "todos.txt",
                cmd(":gen", "--out-dir", cmd.dirname("gen/header.h"), ":lib", "--verbose"),
            ],
        )

        filegroup(name = "lib", srcs = ["lib1.h", "lib2.h"])

        filegroup(name = "gen", srcs = ["gen.sh"])
        """);

    ConfiguredTarget target = getConfiguredTarget("//app:todos");
    SpawnAction action =
        (SpawnAction)
            Iterables.getOnlyElement(
                ((RuleConfiguredTarget) target)
                    .getActions().stream().filter(a -> a instanceof SpawnAction).toList());

    String todos = binPath("todos.txt", "//app:todos");
    String header = binPath("gen/header.h", "//app:todos");
    assertThat(execPaths(action.getOutputs())).containsExactly(todos, header);
    assertThat(execPaths(action.getInputs().toList()))
        .containsAtLeast("app/main.cc", "app/lib1.h", "app/lib2.h", "app/gen.sh");
    assertThat(action.getMnemonic()).isEqualTo("Script");
    assertThat(action.getArguments())
        .containsAtLeast(
            "builtin grep",
            "arg --",
            "arg TODO",
            "path app/main.cc",
            "end",
            "cmd",
            "builtin sort",
            "arg --",
            "stdout " + todos,
            "end",
            "end",
            "cmd",
            "tool app/gen.sh",
            "arg --out-dir",
            "path " + header.substring(0, header.lastIndexOf('/')),
            "path app/lib1.h",
            "path app/lib2.h",
            "arg --verbose",
            "end")
        .inOrder();
  }

  @Test
  public void scriptRule_unknownPathString_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "app/BUILD",
        """
        load("@bazel_tools//tools/cmd:defs.bzl", "script")

        script(
            name = "todos",
            outs = ["todos.txt"],
            cmd = cmd.cat("missing.txt") > "todos.txt",
        )
        """);

    getConfiguredTarget("//app:todos");

    assertContainsEvent(
        "cmd.decode: 'missing.txt' is used as a file but does not refer to a known file");
  }
}
