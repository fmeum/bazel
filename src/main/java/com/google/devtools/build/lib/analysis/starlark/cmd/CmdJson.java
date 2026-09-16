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
import com.google.errorprone.annotations.FormatMethod;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.lib.json.Json;

/**
 * Reconstructs commands from the JSON produced by {@code json.encode}, resolving strings to files.
 *
 * <p>The encoding is produced by {@link CmdElement#objectForEncoding} and {@link
 * CmdArg#objectForEncoding}.
 */
final class CmdJson {

  private final Map<String, Object> files;

  private CmdJson(Map<String, Object> files) {
    this.files = files;
  }

  /** Decodes a command, pipeline or list of those. */
  static Object decode(String json, Map<String, Object> files, StarlarkThread thread)
      throws EvalException {
    Object decoded = Json.INSTANCE.decode(json, Starlark.UNBOUND, thread);
    CmdJson decoder = new CmdJson(files);
    if (decoded instanceof Sequence<?> sequence) {
      ImmutableList.Builder<CmdElement> elements = ImmutableList.builder();
      for (Object item : sequence) {
        elements.add(decoder.element(item));
      }
      return StarlarkList.immutableCopyOf(elements.build());
    }
    return decoder.element(decoded);
  }

  private CmdElement element(Object value) throws EvalException {
    Map<?, ?> map = map(value, "command");
    String kind = string(map, "kind");
    if (kind.equals("pipe")) {
      ImmutableList.Builder<Command> commands = ImmutableList.builder();
      for (Object item : sequence(map, "commands")) {
        commands.add(command(item));
      }
      ImmutableList<Command> list = commands.build();
      if (list.size() < 2) {
        throw error("a pipeline needs at least two commands");
      }
      return new Pipeline(list);
    }
    if (kind.equals("cmd")) {
      return command(value);
    }
    throw error("unknown element kind '%s'", kind);
  }

  private Command command(Object value) throws EvalException {
    Map<?, ?> map = map(value, "command");
    if (!string(map, "kind").equals("cmd")) {
      throw error("expected a command, got '%s'", string(map, "kind"));
    }
    Map<?, ?> program = map(map.get("program"), "program");
    String programKind = string(program, "kind");
    String programValue = string(program, "value");
    Command.Program prog =
        switch (programKind) {
          case "builtin" -> Command.Program.builtin(programValue);
          case "prog" -> {
            Object resolved = files.get(programValue);
            if (resolved == null) {
              yield Command.Program.program(programValue);
            }
            yield switch (resolved) {
              case FilesToRunProvider tool -> {
                if (tool.getExecutable() == null) {
                  throw error("'%s' is not executable", programValue);
                }
                yield Command.Program.tool(tool);
              }
              case Artifact artifact -> Command.Program.file(artifact);
              default -> {
                ImmutableList<Artifact> artifacts = artifacts(resolved, programValue);
                if (artifacts.size() != 1) {
                  throw error("'%s' must resolve to a single executable file", programValue);
                }
                yield Command.Program.file(artifacts.get(0));
              }
            };
          }
          case "file", "tool" ->
              throw error(
                  "cannot decode a reference to the file %s; only commands built from strings can"
                      + " be decoded",
                  programValue);
          default -> throw error("unknown program kind '%s'", programKind);
        };

    ImmutableList.Builder<CmdArg> args = ImmutableList.builder();
    for (Object item : sequence(map, "args")) {
      args.addAll(arg(item, /* single= */ false));
    }
    Command command = new Command(prog, args.build());

    Map<?, ?> env = map(map.get("env"), "env");
    ImmutableMap.Builder<String, String> envMap = ImmutableMap.builder();
    for (Map.Entry<?, ?> entry : env.entrySet()) {
      if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String v)) {
        throw error("env must map strings to strings");
      }
      envMap.put(key, v);
    }
    if (!env.isEmpty()) {
      command = command.withEnv(envMap.buildOrThrow());
    }
    CmdArg cwd = optionalArg(map.get("cwd"));
    if (cwd != null) {
      command = command.withCwd(cwd);
    }
    CmdArg stdin = optionalArg(map.get("stdin"));
    if (stdin != null) {
      command = command.withStdin(stdin);
    } else if (map.get("stdin_text") instanceof String text) {
      command = command.withStdinText(text);
    }
    CmdArg stdout = optionalArg(map.get("stdout"));
    if (stdout != null) {
      command = command.withStdout(stdout, bool(map, "stdout_append"));
    }
    CmdArg stderr = optionalArg(map.get("stderr"));
    if (stderr != null) {
      command = command.withStderr(stderr, bool(map, "stderr_append"));
    } else if (bool(map, "stderr_to_stdout")) {
      command = command.withStderrToStdout();
    }
    return command;
  }

  @Nullable
  private CmdArg optionalArg(@Nullable Object value) throws EvalException {
    if (value == null || value == Starlark.NONE) {
      return null;
    }
    ImmutableList<CmdArg> args = arg(value, /* single= */ true);
    return args.get(0);
  }

  /**
   * Decodes an argument. A string that is a key of {@code files} expands to the corresponding
   * file(s); {@code single} requires exactly one.
   */
  private ImmutableList<CmdArg> arg(Object value, boolean single) throws EvalException {
    Map<?, ?> map = map(value, "argument");
    String kind = string(map, "kind");
    boolean output = map.get("output") instanceof Boolean b && b;
    switch (kind) {
      case "str", "path" -> {
        String s = string(map, "value");
        Object resolved = files.get(s);
        if (resolved == null) {
          if (kind.equals("path")) {
            throw error(
                "'%s' is used as a file but does not refer to a known file; it must be listed in"
                    + " the attributes the rule resolves files from (e.g. srcs, outs or tools)",
                s);
          }
          return ImmutableList.of(CmdArg.string(s));
        }
        if (resolved instanceof FilesToRunProvider tool && !kind.equals("path")) {
          if (tool.getExecutable() == null) {
            throw error("'%s' is not executable", s);
          }
          return ImmutableList.of(CmdArg.tool(tool));
        }
        ImmutableList<Artifact> artifacts = artifacts(resolved, s);
        if (single && artifacts.size() != 1) {
          throw error("'%s' must refer to a single file here, but refers to %d", s, artifacts.size());
        }
        ImmutableList.Builder<CmdArg> result = ImmutableList.builder();
        for (Artifact artifact : artifacts) {
          result.add(CmdArg.file(artifact, output));
        }
        return result.build();
      }
      case "sub" -> {
        if (single) {
          throw error("a command cannot be used as a file or path");
        }
        return ImmutableList.of(CmdArg.substitution(element(map.get("value"))));
      }
      case "dirname" -> {
        ImmutableList<CmdArg> inner = arg(map.get("value"), /* single= */ true);
        return ImmutableList.of(CmdArg.dirname(inner.get(0)));
      }
      case "file", "tool" ->
          throw error(
              "cannot decode a reference to the file %s; only commands built from strings can be"
                  + " decoded",
              string(map, "value"));
      default -> throw error("unknown argument kind '%s'", kind);
    }
  }

  private ImmutableList<Artifact> artifacts(Object resolved, String key) throws EvalException {
    return switch (resolved) {
      case Artifact artifact -> ImmutableList.of(artifact);
      case FilesToRunProvider tool -> {
        if (tool.getExecutable() == null) {
          throw error("'%s' is not executable", key);
        }
        yield ImmutableList.of(tool.getExecutable());
      }
      case Sequence<?> sequence ->
          ImmutableList.copyOf(Sequence.cast(sequence, Artifact.class, "files[" + key + "]"));
      default ->
          throw error(
              "files['%s'] must be a File, a FilesToRunProvider or a list of Files, got %s",
              key, Starlark.type(resolved));
    };
  }

  private static Map<?, ?> map(@Nullable Object value, String what) throws EvalException {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw error("expected a JSON object for %s, got %s", what, Starlark.type(value));
  }

  private static Sequence<?> sequence(Map<?, ?> map, String key) throws EvalException {
    if (map.get(key) instanceof Sequence<?> sequence) {
      return sequence;
    }
    throw error("expected a JSON array for '%s'", key);
  }

  private static String string(Map<?, ?> map, String key) throws EvalException {
    if (map.get(key) instanceof String s) {
      return s;
    }
    throw error("expected a JSON string for '%s'", key);
  }

  private static boolean bool(Map<?, ?> map, String key) {
    return map.get(key) instanceof Boolean b && b;
  }

  @FormatMethod
  private static EvalException error(String format, Object... args) {
    return new EvalException("cmd.decode: " + String.format(format, args));
  }
}
