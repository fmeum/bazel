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

package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Tuple;

/** A single command created by the {@code cmd} module. */
@StarlarkBuiltin(
    name = "Command",
    category = DocCategory.BUILTIN,
    doc =
        "A single program invocation created by <a href=\"../toplevel/cmd.html\">cmd</a> or one of"
            + " its builtins, to be executed by <a"
            + " href=\"../builtins/actions.html#run_script\">ctx.actions.run_script</a>."
            + " Commands are immutable: every method returns a modified copy."
            + "<p>Commands support the following operators:"
            + "<ul>"
            + "<li><code>a | b</code>: pipe the standard output of <code>a</code> into"
            + " <code>b</code>, producing a <a href=\"../builtins/Pipeline.html\">Pipeline</a>."
            + " <code>file | b</code> reads the standard input of <code>b</code> from"
            + " <code>file</code>."
            + "<li><code>a &gt; f</code> and <code>a &gt;&gt; f</code>: write (or append) the"
            + " standard output of <code>a</code> to the <a href=\"../builtins/File.html\">File</a>"
            + " or path <code>f</code>."
            + "<li><code>a &lt; f</code>: read the standard input of <code>a</code> from the <a"
            + " href=\"../builtins/File.html\">File</a> or path <code>f</code>."
            + "<li><code>a &lt;&lt; \"text\"</code>: use the literal string as the standard input of"
            + " <code>a</code>."
            + "</ul>"
            + "<p>Note that <code>&gt;</code> and <code>&lt;</code> bind less tightly than"
            + " <code>|</code> in Starlark, so <code>a | b &gt; f</code> redirects the output of the"
            + " whole pipeline, whereas <code>&gt;&gt;</code> and <code>&lt;&lt;</code> bind more"
            + " tightly and apply to the command they are written next to."
            + "<p>Commands can also be used as arguments of other commands, in which case their"
            + " standard output, with trailing newlines removed, is substituted as a single argument"
            + " at execution time (like <code>$(...)</code> in a shell, but without word"
            + " splitting).")
public interface CommandApi extends CmdElementApi {

  @StarlarkMethod(
      name = "env",
      doc =
          "Returns a copy with additional environment variables set for this command only. Use"
              + " the <code>env</code> parameter of <code>ctx.actions.run_script</code> to set"
              + " variables for the whole action.",
      extraKeywords =
          @Param(name = "kwargs", doc = "The environment variables to set, as keyword arguments."))
  CommandApi env(Dict<String, Object> kwargs) throws EvalException;

  @StarlarkMethod(
      name = "cwd",
      doc =
          "Returns a copy that runs in the given directory instead of the execution root. File"
              + " arguments of the command are automatically passed as absolute paths so that they"
              + " remain valid. Not supported for builtins.",
      parameters = {
        @Param(
            name = "directory",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc =
                "A directory <a href=\"../builtins/File.html\">File</a> (for example one declared"
                    + " with <code>ctx.actions.declare_directory</code>) or a path relative to the"
                    + " execution root."),
      })
  CommandApi cwd(Object directory) throws EvalException;

  @StarlarkMethod(
      name = "args",
      doc = "Returns a copy with the given additional arguments appended.",
      extraPositionals = @Param(name = "args", doc = CmdModuleApi.ARGS_DOC))
  CommandApi args(Tuple args) throws EvalException;
}
