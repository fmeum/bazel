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

import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkValue;

/**
 * Methods shared by {@link CommandApi} and {@link PipelineApi}: everything that can be executed by
 * {@code ctx.actions.run_script} and have its standard streams redirected.
 */
@StarlarkBuiltin(
    name = "cmd_element",
    documented = false,
    doc = "Common methods of Command and Pipeline.")
public interface CmdElementApi extends StarlarkValue {

  String REDIRECT_TARGET_DOC =
      "A <a href=\"../builtins/File.html\">File</a> or a string path relative to the execution"
          + " root. Parent directories are created as needed. Files used here are inferred to"
          + " be outputs of the action.";

  @StarlarkMethod(
      name = "stdin",
      doc =
          "Returns a copy that reads its standard input from the given source. Equivalent to the"
              + " <code>&lt;</code> operator for files and to the <code>&lt;&lt;</code> operator"
              + " for strings.",
      parameters = {
        @Param(
            name = "source",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc =
                "A <a href=\"../builtins/File.html\">File</a> to read from (which is inferred to"
                    + " be an input of the action) or, if <code>text</code> is true, the literal"
                    + " text to feed to the command."),
        @Param(
            name = "text",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "If true, <code>source</code> is the literal text to use as standard input rather"
                    + " than a path."),
      })
  CmdElementApi stdin(Object source, boolean text) throws EvalException;

  @StarlarkMethod(
      name = "stdout",
      doc =
          "Returns a copy that writes its standard output to the given file. Equivalent to the"
              + " <code>&gt;</code> operator (or <code>&gt;&gt;</code> if <code>append</code> is"
              + " true).",
      parameters = {
        @Param(
            name = "target",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc = REDIRECT_TARGET_DOC),
        @Param(
            name = "append",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "If true, append to the file instead of truncating it."),
      })
  CmdElementApi stdout(Object target, boolean append) throws EvalException;

  @StarlarkMethod(
      name = "stderr",
      doc =
          "Returns a copy that writes its standard error to the given file. For a pipeline, this"
              + " applies to every stage.",
      parameters = {
        @Param(
            name = "target",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc = REDIRECT_TARGET_DOC),
        @Param(
            name = "append",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "If true, append to the file instead of truncating it."),
      })
  CmdElementApi stderr(Object target, boolean append) throws EvalException;

  @StarlarkMethod(
      name = "stderr_to_stdout",
      doc =
          "Returns a copy whose standard error is merged into its standard output, like"
              + " <code>2&gt;&amp;1</code> in a shell. For a pipeline, this applies to every"
              + " stage.")
  CmdElementApi stderrToStdout() throws EvalException;
}
