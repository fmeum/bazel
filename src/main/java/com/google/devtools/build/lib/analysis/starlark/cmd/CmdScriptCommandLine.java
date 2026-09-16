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

package com.google.devtools.build.lib.analysis.starlark.cmd;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractCommandLine;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.PathMapper;
import javax.annotation.Nullable;

/**
 * The command line of a {@code run_script} action: the runner script, which is always written to a
 * params file by the action.
 */
public final class CmdScriptCommandLine extends AbstractCommandLine {

  private final ImmutableList<CmdElement> elements;

  public CmdScriptCommandLine(ImmutableList<CmdElement> elements) {
    this.elements = elements;
  }

  /** Renders the script for the runner with the given path mapper. */
  public ImmutableList<String> render(PathMapper pathMapper) {
    ScriptWriter writer = new ScriptWriter(pathMapper);
    writer.line("cmd_runner", ScriptWriter.FORMAT_VERSION);
    for (CmdElement element : elements) {
      element.serialize(writer);
    }
    writer.line("end");
    return writer.build();
  }

  @Override
  public ImmutableList<String> arguments() {
    return render(PathMapper.NOOP);
  }

  @Override
  public ImmutableList<String> arguments(
      @Nullable InputMetadataProvider inputMetadataProvider, PathMapper pathMapper) {
    return render(pathMapper);
  }
}
