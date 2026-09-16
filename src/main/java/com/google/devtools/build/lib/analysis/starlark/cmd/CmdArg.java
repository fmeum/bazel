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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkValue;

/**
 * A single argument, redirection target or working directory of a {@link Command}.
 *
 * <p>Arguments are also exposed to Starlark as the result of {@code cmd.dirname}, which is why this
 * is a {@link StarlarkValue}.
 */
@StarlarkBuiltin(name = "cmd_path", documented = false)
public final class CmdArg implements StarlarkValue {

  /** The kind of an argument, which determines how it is rendered for the runner. */
  enum Kind {
    /** A literal string. */
    STRING,
    /** A string that denotes a path relative to the execution root. */
    PATH,
    /** An artifact, rendered as its (possibly path-mapped) exec path. */
    FILE,
    /** An executable with runfiles, rendered as the exec path of its executable. */
    TOOL,
    /** A command whose standard output is substituted at execution time. */
    SUBSTITUTION,
    /** The parent directory of another argument. */
    DIRNAME,
  }

  private final Kind kind;
  @Nullable private final String string;
  @Nullable private final Artifact artifact;
  @Nullable private final FilesToRunProvider tool;
  @Nullable private final CmdElement substitution;
  @Nullable private final CmdArg inner;
  // Whether a file denoted by this argument is written by the command rather than read.
  private final boolean output;

  private CmdArg(
      Kind kind,
      @Nullable String string,
      @Nullable Artifact artifact,
      @Nullable FilesToRunProvider tool,
      @Nullable CmdElement substitution,
      @Nullable CmdArg inner,
      boolean output) {
    this.kind = kind;
    this.string = string;
    this.artifact = artifact;
    this.tool = tool;
    this.substitution = substitution;
    this.inner = inner;
    this.output = output;
  }

  static CmdArg string(String value) {
    return new CmdArg(Kind.STRING, value, null, null, null, null, false);
  }

  static CmdArg path(String value, boolean output) {
    return new CmdArg(Kind.PATH, value, null, null, null, null, output);
  }

  static CmdArg file(Artifact artifact, boolean output) {
    return new CmdArg(Kind.FILE, null, artifact, null, null, null, output);
  }

  static CmdArg tool(FilesToRunProvider tool) {
    return new CmdArg(Kind.TOOL, null, null, tool, null, null, false);
  }

  static CmdArg substitution(CmdElement element) {
    return new CmdArg(Kind.SUBSTITUTION, null, null, null, element, null, false);
  }

  static CmdArg dirname(CmdArg inner) {
    return new CmdArg(Kind.DIRNAME, null, null, null, null, inner, inner.output);
  }

  Kind kind() {
    return kind;
  }

  boolean isOutput() {
    return output;
  }

  /** Returns a copy marked as denoting a file that is written rather than read. */
  CmdArg asOutput(boolean output) {
    if (kind == Kind.DIRNAME) {
      return dirname(inner.asOutput(output));
    }
    return new CmdArg(kind, string, artifact, tool, substitution, inner, output);
  }

  /** Whether this argument may be used where a path (rather than an arbitrary string) is needed. */
  boolean isPathLike() {
    return switch (kind) {
      case PATH, FILE, TOOL, DIRNAME -> true;
      case STRING, SUBSTITUTION -> false;
    };
  }

  @Nullable
  String string() {
    return string;
  }

  @Nullable
  Artifact artifact() {
    return artifact;
  }

  @Nullable
  FilesToRunProvider tool() {
    return tool;
  }

  @Nullable
  CmdElement substitution() {
    return substitution;
  }

  @Nullable
  CmdArg inner() {
    return inner;
  }

  /** Reports the artifacts and tools referenced by this argument. */
  void collect(CmdElement.Collector collector) {
    switch (kind) {
      case FILE -> {
        if (output) {
          collector.output(artifact);
        } else {
          collector.input(artifact);
        }
      }
      case TOOL -> collector.tool(tool);
      case SUBSTITUTION -> substitution.collect(collector);
      case DIRNAME -> inner.collect(collector);
      case STRING, PATH -> {}
    }
  }

  /** Renders this argument as a single string, which is not possible for substitutions. */
  String render(ScriptWriter writer) {
    return switch (kind) {
      case STRING, PATH -> string;
      case FILE -> writer.pathMapper().getMappedExecPathString(artifact);
      case TOOL -> writer.pathMapper().getMappedExecPathString(tool.getExecutable());
      case DIRNAME -> {
        PathFragment parent = PathFragment.create(inner.render(writer)).getParentDirectory();
        yield parent == null || parent.isEmpty() ? "." : parent.getPathString();
      }
      case SUBSTITUTION -> throw new IllegalStateException("cannot render a substitution");
    };
  }

  /** Writes this argument as a command argument. */
  void serialize(ScriptWriter writer) {
    switch (kind) {
      case STRING -> writer.line("arg", string);
      case PATH, FILE, TOOL, DIRNAME -> writer.line("path", render(writer));
      case SUBSTITUTION -> {
        writer.line("sub");
        substitution.serialize(writer);
      }
    }
  }

  /** Returns the JSON-encodable representation used by {@code json.encode}. */
  Object objectForEncoding(StarlarkSemantics semantics) throws EvalException {
    ImmutableMap.Builder<String, Object> map = ImmutableMap.builder();
    String kindName =
        switch (kind) {
          case STRING -> "str";
          case PATH -> "path";
          case FILE -> "file";
          case TOOL -> "tool";
          case SUBSTITUTION -> "sub";
          case DIRNAME -> "dirname";
        };
    map.put("kind", kindName);
    map.put(
        "value",
        switch (kind) {
          case STRING, PATH -> string;
          case FILE -> artifact.getExecPathString();
          case TOOL -> tool.getExecutable().getExecPathString();
          case SUBSTITUTION -> substitution.objectForEncoding(semantics);
          case DIRNAME -> inner.objectForEncoding(semantics);
        });
    if (output) {
      map.put("output", true);
    }
    return Dict.immutableCopyOf(map.buildOrThrow());
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    switch (kind) {
      case STRING, PATH -> printer.repr(string, semantics);
      case FILE -> printer.repr(artifact, semantics);
      case TOOL -> printer.repr(tool.getExecutable(), semantics);
      case SUBSTITUTION -> printer.repr(substitution, semantics);
      case DIRNAME -> {
        printer.append("cmd.dirname(");
        inner.repr(printer, semantics);
        printer.append(")");
      }
    }
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CmdArg that)) {
      return false;
    }
    return kind == that.kind
        && output == that.output
        && Objects.equals(string, that.string)
        && Objects.equals(artifact, that.artifact)
        && Objects.equals(tool, that.tool)
        && Objects.equals(substitution, that.substitution)
        && Objects.equals(inner, that.inner);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, output, string, artifact, tool, substitution, inner);
  }

  @Override
  public String toString() {
    return Starlark.repr(this, StarlarkSemantics.DEFAULT);
  }
}
