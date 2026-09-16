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
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.starlarkbuildapi.CommandApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Tuple;

/** A single program invocation. Instances are immutable. */
public final class Command extends CmdElement implements CommandApi {

  /** What a command runs. */
  static final class Program {
    enum Kind {
      /** An executable artifact, run from the execution root. */
      FILE,
      /** An executable with runfiles. */
      TOOL,
      /** A program looked up in PATH. */
      PROGRAM,
      /** One of the runner's builtins. */
      BUILTIN,
    }

    final Kind kind;
    @Nullable final Artifact artifact;
    @Nullable final FilesToRunProvider tool;
    @Nullable final String name;

    private Program(
        Kind kind,
        @Nullable Artifact artifact,
        @Nullable FilesToRunProvider tool,
        @Nullable String name) {
      this.kind = kind;
      this.artifact = artifact;
      this.tool = tool;
      this.name = name;
    }

    static Program file(Artifact artifact) {
      return new Program(Kind.FILE, artifact, null, null);
    }

    static Program tool(FilesToRunProvider tool) {
      return new Program(Kind.TOOL, null, tool, null);
    }

    static Program program(String name) {
      return new Program(Kind.PROGRAM, null, null, name);
    }

    static Program builtin(String name) {
      return new Program(Kind.BUILTIN, null, null, name);
    }

    String displayName() {
      return switch (kind) {
        case FILE -> artifact.getExecPathString();
        case TOOL -> tool.getExecutable().getExecPathString();
        case PROGRAM, BUILTIN -> name;
      };
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Program that
          && kind == that.kind
          && Objects.equals(artifact, that.artifact)
          && Objects.equals(tool, that.tool)
          && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, artifact, tool, name);
    }
  }

  private final Program program;
  private final ImmutableList<CmdArg> args;
  private final ImmutableMap<String, String> env;
  @Nullable private final CmdArg cwd;
  @Nullable private final CmdArg stdin;
  @Nullable private final String stdinText;
  @Nullable private final CmdArg stdout;
  private final boolean stdoutAppend;
  @Nullable private final CmdArg stderr;
  private final boolean stderrAppend;
  private final boolean stderrToStdout;

  Command(Program program, ImmutableList<CmdArg> args) {
    this(program, args, ImmutableMap.of(), null, null, null, null, false, null, false, false);
  }

  private Command(
      Program program,
      ImmutableList<CmdArg> args,
      ImmutableMap<String, String> env,
      @Nullable CmdArg cwd,
      @Nullable CmdArg stdin,
      @Nullable String stdinText,
      @Nullable CmdArg stdout,
      boolean stdoutAppend,
      @Nullable CmdArg stderr,
      boolean stderrAppend,
      boolean stderrToStdout) {
    this.program = program;
    this.args = args;
    this.env = env;
    this.cwd = cwd;
    this.stdin = stdin;
    this.stdinText = stdinText;
    this.stdout = stdout;
    this.stdoutAppend = stdoutAppend;
    this.stderr = stderr;
    this.stderrAppend = stderrAppend;
    this.stderrToStdout = stderrToStdout;
  }

  /** A mutable copy of all fields, used to derive modified commands. */
  private final class Builder {
    ImmutableList<CmdArg> args = Command.this.args;
    ImmutableMap<String, String> env = Command.this.env;
    CmdArg cwd = Command.this.cwd;
    CmdArg stdin = Command.this.stdin;
    String stdinText = Command.this.stdinText;
    CmdArg stdout = Command.this.stdout;
    boolean stdoutAppend = Command.this.stdoutAppend;
    CmdArg stderr = Command.this.stderr;
    boolean stderrAppend = Command.this.stderrAppend;
    boolean stderrToStdout = Command.this.stderrToStdout;

    Command build() {
      return new Command(
          program,
          args,
          env,
          cwd,
          stdin,
          stdinText,
          stdout,
          stdoutAppend,
          stderr,
          stderrAppend,
          stderrToStdout);
    }
  }

  Program program() {
    return program;
  }

  ImmutableList<CmdArg> args() {
    return args;
  }

  ImmutableMap<String, String> env() {
    return env;
  }

  @Nullable
  CmdArg cwd() {
    return cwd;
  }

  @Nullable
  CmdArg stdin() {
    return stdin;
  }

  @Nullable
  String stdinText() {
    return stdinText;
  }

  @Nullable
  CmdArg stdout() {
    return stdout;
  }

  boolean stdoutAppend() {
    return stdoutAppend;
  }

  @Nullable
  CmdArg stderr() {
    return stderr;
  }

  boolean stderrAppend() {
    return stderrAppend;
  }

  boolean isStderrToStdout() {
    return stderrToStdout;
  }

  boolean stdinRedirected() {
    return stdin != null || stdinText != null;
  }

  boolean stdoutRedirected() {
    return stdout != null;
  }

  @Override
  ImmutableList<Command> commandList() {
    return ImmutableList.of(this);
  }

  @Override
  CmdElement withCommands(ImmutableList<Command> commands) {
    return commands.size() == 1 ? commands.get(0) : new Pipeline(commands);
  }

  Command withStdin(CmdArg source) throws EvalException {
    if (stdinRedirected()) {
      throw Starlark.errorf(
          "the standard input of %s is already redirected", Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.stdin = source;
    return builder.build();
  }

  Command withStdinText(String text) throws EvalException {
    if (stdinRedirected()) {
      throw Starlark.errorf(
          "the standard input of %s is already redirected", Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.stdinText = text;
    return builder.build();
  }

  Command withStdout(CmdArg target, boolean append) throws EvalException {
    if (stdoutRedirected()) {
      throw Starlark.errorf(
          "the standard output of %s is already redirected", Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.stdout = target;
    builder.stdoutAppend = append;
    return builder.build();
  }

  Command withStderr(CmdArg target, boolean append) throws EvalException {
    if (stderr != null || stderrToStdout) {
      throw Starlark.errorf(
          "the standard error of %s is already redirected", Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.stderr = target;
    builder.stderrAppend = append;
    return builder.build();
  }

  Command withStderrToStdout() throws EvalException {
    if (stderr != null || stderrToStdout) {
      throw Starlark.errorf(
          "the standard error of %s is already redirected", Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.stderrToStdout = true;
    return builder.build();
  }

  Command withEnv(Map<String, String> additions) {
    Builder builder = new Builder();
    LinkedHashMap<String, String> merged = new LinkedHashMap<>(env);
    merged.putAll(additions);
    builder.env = ImmutableMap.copyOf(merged);
    return builder.build();
  }

  Command withCwd(CmdArg directory) throws EvalException {
    if (program.kind == Program.Kind.BUILTIN) {
      throw Starlark.errorf(
          "cwd is not supported for builtins such as %s; pass paths relative to the execution"
              + " root instead",
          Starlark.repr(this, StarlarkSemantics.DEFAULT));
    }
    Builder builder = new Builder();
    builder.cwd = directory;
    return builder.build();
  }

  Command withArgs(ImmutableList<CmdArg> additional) {
    Builder builder = new Builder();
    builder.args = ImmutableList.<CmdArg>builder().addAll(args).addAll(additional).build();
    return builder.build();
  }

  @Override
  public Command env(Dict<String, Object> kwargs) throws EvalException {
    return withEnv(Dict.cast(kwargs, String.class, String.class, "env"));
  }

  @Override
  public Command cwd(Object directory) throws EvalException {
    return withCwd(CmdModule.toRedirectTarget(directory, "cwd", false));
  }

  @Override
  public Command args(Tuple args) throws EvalException {
    return withArgs(CmdModule.toArgs(args, "args"));
  }

  void collectCommand(Collector collector) {
    switch (program.kind) {
      case FILE -> collector.executable(program.artifact);
      case TOOL -> collector.tool(program.tool);
      case PROGRAM, BUILTIN -> {}
    }
    for (CmdArg arg : args) {
      arg.collect(collector);
    }
    for (CmdArg arg : new CmdArg[] {cwd, stdin, stdout, stderr}) {
      if (arg != null) {
        arg.collect(collector);
      }
    }
  }

  @Override
  void serialize(ScriptWriter writer) {
    writer.line("cmd");
    switch (program.kind) {
      case FILE -> writer.line("tool", writer.pathMapper().getMappedExecPathString(program.artifact));
      case TOOL ->
          writer.line(
              "tool", writer.pathMapper().getMappedExecPathString(program.tool.getExecutable()));
      case PROGRAM -> writer.line("prog", program.name);
      case BUILTIN -> writer.line("builtin", program.name);
    }
    for (CmdArg arg : args) {
      arg.serialize(writer);
    }
    for (Map.Entry<String, String> entry : env.entrySet()) {
      writer.line("env", entry.getKey() + "=" + entry.getValue());
    }
    if (cwd != null) {
      writer.line("cwd", cwd.render(writer));
    }
    if (stdin != null) {
      writer.line("stdin", stdin.render(writer));
    } else if (stdinText != null) {
      writer.line("stdin_text", stdinText);
    }
    if (stdout != null) {
      writer.line(stdoutAppend ? "stdout_append" : "stdout", stdout.render(writer));
    }
    if (stderr != null) {
      writer.line(stderrAppend ? "stderr_append" : "stderr", stderr.render(writer));
    } else if (stderrToStdout) {
      writer.line("stderr_to_stdout");
    }
    writer.line("end");
  }

  @Override
  public Object objectForEncoding(StarlarkSemantics semantics) {
    try {
      ImmutableMap.Builder<String, Object> map = ImmutableMap.builder();
      map.put("kind", "cmd");
      String programKind =
          switch (program.kind) {
            case FILE -> "file";
            case TOOL -> "tool";
            case PROGRAM -> "prog";
            case BUILTIN -> "builtin";
          };
      map.put(
          "program",
          Dict.immutableCopyOf(
              ImmutableMap.of("kind", programKind, "value", program.displayName())));
      ImmutableList.Builder<Object> encodedArgs = ImmutableList.builder();
      for (CmdArg arg : args) {
        encodedArgs.add(arg.objectForEncoding(semantics));
      }
      map.put("args", StarlarkList.immutableCopyOf(encodedArgs.build()));
      map.put("env", Dict.immutableCopyOf(env));
      map.put("cwd", cwd == null ? Starlark.NONE : cwd.objectForEncoding(semantics));
      map.put("stdin", stdin == null ? Starlark.NONE : stdin.objectForEncoding(semantics));
      map.put("stdin_text", stdinText == null ? Starlark.NONE : stdinText);
      map.put("stdout", stdout == null ? Starlark.NONE : stdout.objectForEncoding(semantics));
      map.put("stdout_append", stdoutAppend);
      map.put("stderr", stderr == null ? Starlark.NONE : stderr.objectForEncoding(semantics));
      map.put("stderr_append", stderrAppend);
      map.put("stderr_to_stdout", stderrToStdout);
      return Dict.immutableCopyOf(map.buildOrThrow());
    } catch (EvalException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    switch (program.kind) {
      case FILE -> {
        printer.append("cmd(");
        printer.repr(program.artifact, semantics);
      }
      case TOOL -> {
        printer.append("cmd(");
        printer.repr(program.tool.getExecutable(), semantics);
      }
      case PROGRAM -> {
        printer.append("cmd(");
        printer.repr(program.name, semantics);
      }
      case BUILTIN -> printer.append("cmd.").append(program.name).append("(");
    }
    boolean first = program.kind == Program.Kind.BUILTIN;
    for (CmdArg arg : args) {
      if (program.kind == Program.Kind.BUILTIN
          && arg.kind() == CmdArg.Kind.STRING
          && arg.string().startsWith("--")) {
        // Options are an implementation detail of the runner protocol.
        continue;
      }
      if (!first) {
        printer.append(", ");
      }
      first = false;
      arg.repr(printer, semantics);
    }
    printer.append(")");
    if (!env.isEmpty()) {
      printer.append(".env(");
      boolean firstEnv = true;
      for (Map.Entry<String, String> entry : env.entrySet()) {
        if (!firstEnv) {
          printer.append(", ");
        }
        firstEnv = false;
        printer.append(entry.getKey()).append(" = ");
        printer.repr(entry.getValue(), semantics);
      }
      printer.append(")");
    }
    if (cwd != null) {
      printer.append(".cwd(");
      cwd.repr(printer, semantics);
      printer.append(")");
    }
    if (stdin != null) {
      printer.append(" < ");
      stdin.repr(printer, semantics);
    } else if (stdinText != null) {
      printer.append(" << ");
      printer.repr(stdinText, semantics);
    }
    if (stdout != null) {
      printer.append(stdoutAppend ? " >> " : " > ");
      stdout.repr(printer, semantics);
    }
    if (stderr != null) {
      printer.append(stderrAppend ? " 2>> " : " 2> ");
      stderr.repr(printer, semantics);
    } else if (stderrToStdout) {
      printer.append(" 2>&1");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Command that)) {
      return false;
    }
    return program.equals(that.program)
        && args.equals(that.args)
        && env.equals(that.env)
        && Objects.equals(cwd, that.cwd)
        && Objects.equals(stdin, that.stdin)
        && Objects.equals(stdinText, that.stdinText)
        && Objects.equals(stdout, that.stdout)
        && stdoutAppend == that.stdoutAppend
        && Objects.equals(stderr, that.stderr)
        && stderrAppend == that.stderrAppend
        && stderrToStdout == that.stderrToStdout;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        program,
        args,
        env,
        cwd,
        stdin,
        stdinText,
        stdout,
        stdoutAppend,
        stderr,
        stderrAppend,
        stderrToStdout);
  }
}
