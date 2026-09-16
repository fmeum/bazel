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
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.starlarkbuildapi.CmdModuleApi;
import java.util.List;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/** Implementation of the {@code cmd} module. */
public final class CmdModule implements CmdModuleApi {

  public static final CmdModule INSTANCE = new CmdModule();

  /** The toolchain type that provides the runner executing scripts. */
  public static final Label TOOLCHAIN_TYPE =
      Label.parseCanonicalUnchecked("@@bazel_tools//tools/cmd:toolchain_type");

  private CmdModule() {}

  // ---------------------------------------------------------------------------------------------
  // Conversions from Starlark values.
  // ---------------------------------------------------------------------------------------------

  /**
   * Converts arguments of an external program or builtin, flattening lists and depsets. Strings
   * are literal arguments, files are passed as paths and commands are substituted.
   */
  static ImmutableList<CmdArg> toArgs(Iterable<?> values, String what) throws EvalException {
    ImmutableList.Builder<CmdArg> result = ImmutableList.builder();
    for (Object value : values) {
      convertArg(value, what, /* pathPosition= */ false, /* output= */ false, result);
    }
    return result.build();
  }

  /**
   * Converts values used where files or paths are expected, flattening lists and depsets. Strings
   * are paths relative to the execution root.
   */
  static ImmutableList<CmdArg> toPaths(Iterable<?> values, String what, boolean output)
      throws EvalException {
    ImmutableList.Builder<CmdArg> result = ImmutableList.builder();
    for (Object value : values) {
      convertArg(value, what, /* pathPosition= */ true, output, result);
    }
    return result.build();
  }

  /** Converts a single value used as a redirection target or working directory. */
  static CmdArg toRedirectTarget(Object value, String what, boolean output) throws EvalException {
    ImmutableList<CmdArg> args = toPaths(ImmutableList.of(value), what, output);
    if (args.size() != 1) {
      throw Starlark.errorf("%s: expected a single file or path, got %d", what, args.size());
    }
    return args.get(0);
  }

  private static void convertArg(
      Object value,
      String what,
      boolean pathPosition,
      boolean output,
      ImmutableList.Builder<CmdArg> result)
      throws EvalException {
    switch (value) {
      case String s -> result.add(pathPosition ? CmdArg.path(s, output) : CmdArg.string(s));
      case Artifact artifact -> result.add(CmdArg.file(artifact, output));
      case FilesToRunProvider tool -> {
        if (tool.getExecutable() == null) {
          throw Starlark.errorf("%s: %s is not executable", what, Starlark.repr(tool, StarlarkSemantics.DEFAULT));
        }
        result.add(CmdArg.tool(tool));
      }
      case CmdArg arg -> {
        if (pathPosition && !arg.isPathLike()) {
          throw Starlark.errorf("%s: expected a file or path, got %s", what, Starlark.repr(arg, StarlarkSemantics.DEFAULT));
        }
        result.add(pathPosition ? arg.asOutput(output) : arg);
      }
      case CmdElement element -> {
        if (pathPosition) {
          throw Starlark.errorf(
              "%s: expected a file or path, got the command %s", what, Starlark.repr(element, StarlarkSemantics.DEFAULT));
        }
        result.add(CmdArg.substitution(element));
      }
      case Label label -> {
        if (pathPosition) {
          throw Starlark.errorf(
              "%s: expected a file or path, got the label %s; pass a File instead",
              what, Starlark.repr(label, StarlarkSemantics.DEFAULT));
        }
        result.add(CmdArg.string(label.getCanonicalForm()));
      }
      case StarlarkInt i -> {
        if (pathPosition) {
          throw Starlark.errorf("%s: expected a file or path, got int", what);
        }
        result.add(CmdArg.string(i.toString()));
      }
      case Sequence<?> sequence -> {
        for (Object element : sequence) {
          convertArg(element, what, pathPosition, output, result);
        }
      }
      case Depset depset -> {
        for (Object element : depset.toList()) {
          convertArg(element, what, pathPosition, output, result);
        }
      }
      default ->
          throw Starlark.errorf(
              "%s: expected %s, got %s",
              what,
              pathPosition
                  ? "a File, a path, or a list of those"
                  : "a string, File, Label, Command or list of those",
              Starlark.type(value));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Commands running external programs.
  // ---------------------------------------------------------------------------------------------

  @Override
  public Command cmd(Object program, Tuple args) throws EvalException {
    Command.Program prog =
        switch (program) {
          case Artifact artifact -> Command.Program.file(artifact);
          case FilesToRunProvider tool -> {
            if (tool.getExecutable() == null) {
              throw Starlark.errorf("cmd: %s is not executable", Starlark.repr(tool, StarlarkSemantics.DEFAULT));
            }
            yield Command.Program.tool(tool);
          }
          case String name -> {
            if (name.isEmpty()) {
              throw Starlark.errorf("cmd: the program name must not be empty");
            }
            yield Command.Program.program(name);
          }
          default -> throw new IllegalStateException(); // Enforced by the annotation.
        };
    return new Command(prog, toArgs(args, "cmd"));
  }

  // ---------------------------------------------------------------------------------------------
  // Builtins. Their arguments follow the runner protocol: options first, then "--", then
  // positional arguments.
  // ---------------------------------------------------------------------------------------------

  private static final class BuiltinBuilder {
    private final String name;
    private final ImmutableList.Builder<CmdArg> args = ImmutableList.builder();
    private boolean separated;

    BuiltinBuilder(String name) {
      this.name = name;
    }

    BuiltinBuilder option(String option, boolean enabled) {
      if (enabled) {
        args.add(CmdArg.string("--" + option));
      }
      return this;
    }

    BuiltinBuilder option(String option, String value) {
      args.add(CmdArg.string("--" + option + "=" + value));
      return this;
    }

    private void separate() {
      if (!separated) {
        args.add(CmdArg.string("--"));
        separated = true;
      }
    }

    BuiltinBuilder args(Iterable<?> values) throws EvalException {
      separate();
      args.addAll(toArgs(values, name));
      return this;
    }

    BuiltinBuilder paths(Iterable<?> values, boolean output) throws EvalException {
      separate();
      args.addAll(toPaths(values, name, output));
      return this;
    }

    BuiltinBuilder path(Object value, boolean output) throws EvalException {
      separate();
      args.add(toRedirectTarget(value, name, output));
      return this;
    }

    Command build() {
      separate();
      return new Command(Command.Program.builtin(name), args.build());
    }
  }

  @Override
  public Command echo(String sep, String end, Tuple args) throws EvalException {
    return new BuiltinBuilder("echo").option("sep", sep).option("end", end).args(args).build();
  }

  @Override
  public Command cat(Tuple files) throws EvalException {
    return new BuiltinBuilder("cat").paths(files, false).build();
  }

  @Override
  public Command grep(
      String pattern,
      boolean fixed,
      boolean ignoreCase,
      boolean invert,
      boolean count,
      boolean lineNumber,
      Tuple files)
      throws EvalException {
    return new BuiltinBuilder("grep")
        .option("fixed", fixed)
        .option("ignore-case", ignoreCase)
        .option("invert", invert)
        .option("count", count)
        .option("line-number", lineNumber)
        .args(ImmutableList.of(pattern))
        .paths(files, false)
        .build();
  }

  @Override
  public Command replace(
      String oldText, String newText, boolean regex, boolean ignoreCase, Tuple files)
      throws EvalException {
    return new BuiltinBuilder("replace")
        .option("regex", regex)
        .option("ignore-case", ignoreCase)
        .args(ImmutableList.of(oldText, newText))
        .paths(files, false)
        .build();
  }

  private static String lineCount(StarlarkInt lines, String what) throws EvalException {
    int n = lines.toInt(what);
    if (n < 0) {
      throw Starlark.errorf("%s: lines must not be negative, got %d", what, n);
    }
    return Integer.toString(n);
  }

  @Override
  public Command head(StarlarkInt lines, Tuple files) throws EvalException {
    return new BuiltinBuilder("head")
        .option("lines", lineCount(lines, "head"))
        .paths(files, false)
        .build();
  }

  @Override
  public Command tail(StarlarkInt lines, Tuple files) throws EvalException {
    return new BuiltinBuilder("tail")
        .option("lines", lineCount(lines, "tail"))
        .paths(files, false)
        .build();
  }

  @Override
  public Command sort(boolean reverse, boolean unique, boolean numeric, Tuple files)
      throws EvalException {
    return new BuiltinBuilder("sort")
        .option("reverse", reverse)
        .option("unique", unique)
        .option("numeric", numeric)
        .paths(files, false)
        .build();
  }

  @Override
  public Command uniq(boolean count, Tuple files) throws EvalException {
    return new BuiltinBuilder("uniq").option("count", count).paths(files, false).build();
  }

  @Override
  public Command wc(boolean lines, boolean words, boolean bytes, Tuple files)
      throws EvalException {
    return new BuiltinBuilder("wc")
        .option("lines", lines)
        .option("words", words)
        .option("bytes", bytes)
        .paths(files, false)
        .build();
  }

  @Override
  public Command cp(Object src, Object dest) throws EvalException {
    ImmutableList<CmdArg> sources = toPaths(ImmutableList.of(src), "cp", false);
    if (sources.isEmpty()) {
      throw Starlark.errorf("cp: expected at least one source");
    }
    return new BuiltinBuilder("cp").paths(ImmutableList.of(src), false).path(dest, true).build();
  }

  @Override
  public Command mv(Object src, Object dest) throws EvalException {
    return new BuiltinBuilder("mv").path(src, true).path(dest, true).build();
  }

  @Override
  public Command mkdir(Tuple dirs) throws EvalException {
    return new BuiltinBuilder("mkdir").paths(nonEmpty(dirs, "mkdir"), true).build();
  }

  @Override
  public Command rm(Tuple paths) throws EvalException {
    return new BuiltinBuilder("rm").paths(nonEmpty(paths, "rm"), true).build();
  }

  @Override
  public Command touch(Tuple files) throws EvalException {
    return new BuiltinBuilder("touch").paths(nonEmpty(files, "touch"), true).build();
  }

  @Override
  public Command chmod(String mode, Tuple files) throws EvalException {
    return new BuiltinBuilder("chmod")
        .args(ImmutableList.of(mode))
        .paths(nonEmpty(files, "chmod"), true)
        .build();
  }

  @Override
  public Command tee(boolean append, Tuple files) throws EvalException {
    return new BuiltinBuilder("tee").option("append", append).paths(files, true).build();
  }

  @Override
  public Command ls(boolean recursive, Tuple dirs) throws EvalException {
    return new BuiltinBuilder("ls").option("recursive", recursive).paths(dirs, false).build();
  }

  @Override
  public Command fail(Tuple message) throws EvalException {
    return new BuiltinBuilder("fail").args(message).build();
  }

  private static Tuple nonEmpty(Tuple values, String what) throws EvalException {
    if (values.isEmpty()) {
      throw Starlark.errorf("%s: expected at least one file or path", what);
    }
    return values;
  }

  // ---------------------------------------------------------------------------------------------
  // Miscellaneous.
  // ---------------------------------------------------------------------------------------------

  @Override
  public CmdArg dirname(Object file) throws EvalException {
    return CmdArg.dirname(toRedirectTarget(file, "dirname", false));
  }

  @Override
  public Object decode(String json, Dict<?, ?> files, StarlarkThread thread)
      throws EvalException {
    return CmdJson.decode(json, Dict.cast(files, String.class, Object.class, "files"), thread);
  }

  @Override
  public Label toolchainType() {
    return TOOLCHAIN_TYPE;
  }

  /** Converts the {@code script} parameter of {@code run_script} into a list of elements. */
  public static ImmutableList<CmdElement> toScript(Object script) throws EvalException {
    if (script instanceof CmdElement element) {
      return ImmutableList.of(element);
    }
    if (script instanceof Sequence<?> sequence) {
      List<CmdElement> elements = Sequence.cast(sequence, CmdElement.class, "script");
      if (elements.isEmpty()) {
        throw Starlark.errorf("script: expected at least one command");
      }
      return ImmutableList.copyOf(elements);
    }
    throw Starlark.errorf(
        "script: expected a Command, a Pipeline or a list of those, got %s",
        Starlark.type(script));
  }
}
