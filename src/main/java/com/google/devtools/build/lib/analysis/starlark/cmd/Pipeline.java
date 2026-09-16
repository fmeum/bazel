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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.starlarkbuildapi.PipelineApi;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;

/** Two or more commands connected by pipes. Instances are immutable. */
public final class Pipeline extends CmdElement implements PipelineApi {

  private final ImmutableList<Command> commands;

  Pipeline(ImmutableList<Command> commands) {
    if (commands.size() < 2) {
      throw new IllegalArgumentException("a pipeline needs at least two commands");
    }
    this.commands = commands;
  }

  @Override
  ImmutableList<Command> commandList() {
    return commands;
  }

  @Override
  public Sequence<Command> commands() {
    return StarlarkList.immutableCopyOf(commands);
  }

  @Override
  CmdElement withCommands(ImmutableList<Command> commands) {
    return new Pipeline(commands);
  }

  @Override
  void serialize(ScriptWriter writer) {
    writer.line("pipe");
    for (Command command : commands) {
      command.serialize(writer);
    }
    writer.line("end");
  }

  @Override
  public Object objectForEncoding(StarlarkSemantics semantics) {
    ImmutableList.Builder<Object> encoded = ImmutableList.builder();
    for (Command command : commands) {
      encoded.add(command.objectForEncoding(semantics));
    }
    return Dict.immutableCopyOf(
        ImmutableMap.of("kind", "pipe", "commands", StarlarkList.immutableCopyOf(encoded.build())));
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    boolean first = true;
    for (Command command : commands) {
      if (!first) {
        printer.append(" | ");
      }
      first = false;
      command.repr(printer, semantics);
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Pipeline that && commands.equals(that.commands);
  }

  @Override
  public int hashCode() {
    return commands.hashCode();
  }
}
