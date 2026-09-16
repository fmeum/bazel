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
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.starlarkbuildapi.CmdElementApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.HasBinary;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.lib.StarlarkEncodable;
import net.starlark.java.syntax.TokenKind;

/**
 * Something that can be executed by {@code ctx.actions.run_script}: a {@link Command} or a {@link
 * Pipeline}.
 */
public abstract class CmdElement implements CmdElementApi, HasBinary, StarlarkEncodable {

  /** Receives the files referenced by a script. */
  public interface Collector {
    /** A file read by the script. */
    void input(Artifact artifact);

    /** A file written by the script. */
    void output(Artifact artifact);

    /** An executable with runfiles used as a program. */
    void tool(FilesToRunProvider tool);

    /** An executable file used as a program; its runfiles, if any, must be looked up. */
    void executable(Artifact artifact);
  }

  /** The commands of this element in pipeline order. */
  abstract ImmutableList<Command> commandList();

  /** Returns an element of the same kind with the given commands. */
  abstract CmdElement withCommands(ImmutableList<Command> commands) throws EvalException;

  /** Writes the runner script representation of this element. */
  abstract void serialize(ScriptWriter writer);

  /** Returns the JSON-encodable representation used by {@code json.encode}. */
  @Override
  public abstract Object objectForEncoding(StarlarkSemantics semantics);

  public final void collect(Collector collector) {
    for (Command command : commandList()) {
      command.collectCommand(collector);
    }
  }

  private Command first() {
    return commandList().get(0);
  }

  private Command last() {
    return commandList().get(commandList().size() - 1);
  }

  private CmdElement replaceFirst(Command command) throws EvalException {
    ImmutableList.Builder<Command> result = ImmutableList.builder();
    result.add(command);
    result.addAll(commandList().subList(1, commandList().size()));
    return withCommands(result.build());
  }

  private CmdElement replaceLast(Command command) throws EvalException {
    ImmutableList.Builder<Command> result = ImmutableList.builder();
    result.addAll(commandList().subList(0, commandList().size() - 1));
    result.add(command);
    return withCommands(result.build());
  }

  @Override
  public CmdElement stdin(Object source, boolean text) throws EvalException {
    if (text) {
      if (!(source instanceof String s)) {
        throw Starlark.errorf(
            "stdin: got %s for 'source', want string when 'text' is set", Starlark.type(source));
      }
      return replaceFirst(first().withStdinText(s));
    }
    return replaceFirst(first().withStdin(CmdModule.toRedirectTarget(source, "stdin", false)));
  }

  @Override
  public CmdElement stdout(Object target, boolean append) throws EvalException {
    return replaceLast(
        last().withStdout(CmdModule.toRedirectTarget(target, "stdout", true), append));
  }

  @Override
  public CmdElement stderr(Object target, boolean append) throws EvalException {
    CmdArg arg = CmdModule.toRedirectTarget(target, "stderr", true);
    ImmutableList.Builder<Command> result = ImmutableList.builder();
    for (Command command : commandList()) {
      result.add(command.withStderr(arg, append));
    }
    return withCommands(result.build());
  }

  @Override
  public CmdElement stderrToStdout() throws EvalException {
    ImmutableList.Builder<Command> result = ImmutableList.builder();
    for (Command command : commandList()) {
      result.add(command.withStderrToStdout());
    }
    return withCommands(result.build());
  }

  /** Implements {@code this | that}. */
  Pipeline pipeTo(CmdElement that) throws EvalException {
    if (last().stdoutRedirected()) {
      throw Starlark.errorf(
          "cannot pipe the output of %s into another command: it is already redirected to a file",
          Starlark.repr(last(), StarlarkSemantics.DEFAULT));
    }
    if (that.first().stdinRedirected()) {
      throw Starlark.errorf(
          "cannot pipe into %s: its input is already redirected", Starlark.repr(that.first(), StarlarkSemantics.DEFAULT));
    }
    return new Pipeline(
        ImmutableList.<Command>builder().addAll(commandList()).addAll(that.commandList()).build());
  }

  @Override
  @Nullable
  public Object binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
    switch (op) {
      case PIPE -> {
        if (thisLeft) {
          if (that instanceof CmdElement element) {
            return pipeTo(element);
          }
          throw Starlark.errorf(
              "unsupported binary operation: %s | %s (only commands and pipelines can be piped"
                  + " into)",
              Starlark.type(this), Starlark.type(that));
        }
        // file | command reads the command's standard input from the file.
        if (that instanceof Artifact || that instanceof CmdArg) {
          return stdin(that, false);
        }
        return null;
      }
      case GREATER, GREATER_GREATER -> {
        if (!thisLeft) {
          return null;
        }
        checkRedirectTarget(op, that);
        return stdout(that, op == TokenKind.GREATER_GREATER);
      }
      case LESS -> {
        if (!thisLeft) {
          return null;
        }
        checkRedirectTarget(op, that);
        return stdin(that, false);
      }
      case LESS_LESS -> {
        if (!thisLeft) {
          return null;
        }
        if (!(that instanceof String)) {
          throw Starlark.errorf(
              "unsupported binary operation: %s << %s (the right operand of << must be the string"
                  + " to use as standard input)",
              Starlark.type(this), Starlark.type(that));
        }
        return stdin(that, true);
      }
      default -> {
        return null;
      }
    }
  }

  private void checkRedirectTarget(TokenKind op, Object that) throws EvalException {
    if (that instanceof CmdElement) {
      throw Starlark.errorf(
          "unsupported binary operation: %s %s %s (use | to connect commands with a pipe)",
          Starlark.type(this), op, Starlark.type(that));
    }
    if (!(that instanceof Artifact || that instanceof String || that instanceof CmdArg)) {
      throw Starlark.errorf(
          "unsupported binary operation: %s %s %s (the right operand of %s must be a File or a"
              + " path)",
          Starlark.type(this), op, Starlark.type(that), op);
    }
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public String toString() {
    return Starlark.repr(this, StarlarkSemantics.DEFAULT);
  }
}
