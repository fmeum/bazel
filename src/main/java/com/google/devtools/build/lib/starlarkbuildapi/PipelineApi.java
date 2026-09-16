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
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Sequence;

/** A pipeline of commands created with the {@code |} operator. */
@StarlarkBuiltin(
    name = "Pipeline",
    category = DocCategory.BUILTIN,
    doc =
        "A sequence of <a href=\"../builtins/Command.html\">Command</a>s connected by pipes,"
            + " created with the <code>|</code> operator. All stages run concurrently; the pipeline"
            + " fails if any stage fails. Pipelines support the same operators and redirection"
            + " methods as commands: <code>&lt;</code> and <code>stdin</code> apply to the first"
            + " stage, <code>&gt;</code> and <code>stdout</code> to the last one.")
public interface PipelineApi extends CmdElementApi {

  @StarlarkMethod(
      name = "commands",
      doc = "The commands making up this pipeline, in order.",
      structField = true)
  Sequence<? extends CommandApi> commands();
}
