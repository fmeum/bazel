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
import com.google.devtools.build.lib.cmdline.Label;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;

/** The {@code cmd} module: a portable, shell-free way to describe command pipelines. */
@StarlarkBuiltin(
    name = "cmd",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc =
        "A module for describing commands and pipelines that are executed by <a"
            + " href=\"../builtins/actions.html#run_script\">ctx.actions.run_script</a> without a"
            + " shell. It is meant as a portable replacement for <code>genrule</code> and"
            + " <code>ctx.actions.run_shell</code>: the same script runs on Linux, macOS and"
            + " Windows and on execution platforms that provide neither a shell nor coreutils,"
            + " since the most common tools are available as builtins."
            + "<p>Calling <code>cmd(program, *args)</code> creates a <a"
            + " href=\"../builtins/Command.html\">Command</a> running an external program. The"
            + " methods of this module create commands running builtins such as"
            + " <code>cmd.cat</code> or <code>cmd.grep</code>. Commands are combined with"
            + " operators:"
            + "<pre class=\"language-python\">"
            + "ctx.actions.run_script(\n"
            + "    cmd.cat(ctx.files.srcs) | cmd.grep(\"TODO\") | cmd.sort(unique = True) &gt; out,\n"
            + "    mnemonic = \"CollectTodos\",\n"
            + ")\n"
            + "ctx.actions.run_script(\n"
            + "    [\n"
            + "        cmd.mkdir(gen_dir),\n"
            + "        cmd(ctx.executable.generator, \"--out\", gen_dir, ctx.file.spec)\n"
            + "            .env(LANG = \"C\"),\n"
            + "        cmd.ls(gen_dir, recursive = True) &gt; manifest,\n"
            + "    ],\n"
            + "    outputs = [gen_dir],\n"
            + "    mnemonic = \"Generate\",\n"
            + ")</pre>"
            + "<p>Files referenced anywhere in a script are automatically inputs of the action,"
            + " except for files that a script writes to via redirection or builtins such as"
            + " <code>cp</code> and <code>touch</code>, which are automatically outputs. Outputs"
            + " written by external programs must be listed in the <code>outputs</code> parameter"
            + " of <code>run_script</code>."
            + "<p>Unless noted otherwise, builtins that read files accept any number of <a"
            + " href=\"../builtins/File.html\">File</a>s, string paths relative to the execution"
            + " root, or lists of those, and read standard input if no file is given. Regular"
            + " expressions use ECMAScript syntax; capturing groups are referenced as"
            + " <code>$1</code> in replacements."
            + "<p>Requires <code>--experimental_starlark_cmd</code>.")
public interface CmdModuleApi extends StarlarkValue {

  String FILES_DOC =
      "<a href=\"../builtins/File.html\">File</a>s, string paths relative to the execution root,"
          + " or lists of those. Standard input is read if no file is given.";

  String ARGS_DOC =
      "Arguments to pass to the program. Each argument may be a string, a <a"
          + " href=\"../builtins/File.html\">File</a> (passed as its path and inferred to be an"
          + " input), a <a href=\"../builtins/Label.html\">Label</a> (passed as a string), a"
          + " <a href=\"../builtins/Command.html\">Command</a> or <a"
          + " href=\"../builtins/Pipeline.html\">Pipeline</a> (whose output is substituted at"
          + " execution time), or a list of any of these, which is flattened.";

  @StarlarkMethod(
      name = "cmd",
      doc =
          "Creates a <a href=\"../builtins/Command.html\">Command</a> that runs an external"
              + " program.",
      parameters = {
        @Param(
            name = "program",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = FilesToRunProviderApi.class),
              @ParamType(type = String.class),
            },
            doc =
                "The program to run: an executable <a href=\"../builtins/File.html\">File</a>"
                    + " (such as <code>ctx.executable.tool</code>), a <a"
                    + " href=\"../providers/FilesToRunProvider.html\">FilesToRunProvider</a>"
                    + " (whose runfiles are added to the action), or the name of a program to"
                    + " look up in <code>PATH</code>. The latter is not hermetic and should be"
                    + " avoided where possible."),
      },
      extraPositionals = @Param(name = "args", doc = ARGS_DOC),
      selfCall = true)
  CommandApi cmd(Object program, Tuple args) throws EvalException;

  @StarlarkMethod(
      name = "echo",
      doc = "Writes its arguments, separated by <code>sep</code> and followed by <code>end</code>.",
      extraPositionals = @Param(name = "args", doc = ARGS_DOC),
      parameters = {
        @Param(
            name = "sep",
            named = true,
            positional = false,
            defaultValue = "\" \"",
            doc = "The separator between arguments."),
        @Param(
            name = "end",
            named = true,
            positional = false,
            defaultValue = "\"\\n\"",
            doc = "The string written after the last argument."),
      })
  CommandApi echo(String sep, String end, Tuple args) throws EvalException;

  @StarlarkMethod(
      name = "cat",
      doc = "Concatenates the given files (or standard input) to standard output.",
      extraPositionals = @Param(name = "files", doc = FILES_DOC))
  CommandApi cat(Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "grep",
      doc =
          "Prints the lines of the given files (or standard input) matching a pattern. Unlike"
              + " the coreutils tool, this does not fail if no line matches. If more than one"
              + " file is given, lines are prefixed with the file name.",
      parameters = {
        @Param(
            name = "pattern",
            doc = "A regular expression, or a plain string if <code>fixed</code> is true."),
        @Param(
            name = "fixed",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to match the pattern literally rather than as a regular expression."),
        @Param(
            name = "ignore_case",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to match case-insensitively."),
        @Param(
            name = "invert",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to print the lines that do <em>not</em> match."),
        @Param(
            name = "count",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to print the number of matching lines instead of the lines."),
        @Param(
            name = "line_number",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to prefix each line with its line number."),
      },
      extraPositionals = @Param(name = "files", doc = FILES_DOC))
  CommandApi grep(
      String pattern,
      boolean fixed,
      boolean ignoreCase,
      boolean invert,
      boolean count,
      boolean lineNumber,
      Tuple files)
      throws EvalException;

  @StarlarkMethod(
      name = "replace",
      doc =
          "Copies the given files (or standard input) to standard output, replacing all"
              + " occurrences of <code>old</code> with <code>new</code>. A portable alternative to"
              + " the most common uses of <code>sed</code>.",
      parameters = {
        @Param(
            name = "old",
            doc = "The text to replace, or a regular expression if <code>regex</code> is true."),
        @Param(
            name = "new",
            doc =
                "The replacement. If <code>regex</code> is true, <code>$1</code>, <code>$2</code>,"
                    + " ... refer to capturing groups and <code>$&amp;</code> to the whole match."),
        @Param(
            name = "regex",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether <code>old</code> is a regular expression."),
        @Param(
            name = "ignore_case",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to match case-insensitively."),
      },
      extraPositionals = @Param(name = "files", doc = FILES_DOC))
  CommandApi replace(
      String oldText, String newText, boolean regex, boolean ignoreCase, Tuple files)
      throws EvalException;

  @StarlarkMethod(
      name = "head",
      doc = "Prints the first <code>lines</code> lines of each of the given files (or standard input).",
      extraPositionals = @Param(name = "files", doc = FILES_DOC),
      parameters = {
        @Param(
            name = "lines",
            named = true,
            positional = false,
            defaultValue = "10",
            doc = "The number of lines to print."),
      })
  CommandApi head(StarlarkInt lines, Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "tail",
      doc = "Prints the last <code>lines</code> lines of each of the given files (or standard input).",
      extraPositionals = @Param(name = "files", doc = FILES_DOC),
      parameters = {
        @Param(
            name = "lines",
            named = true,
            positional = false,
            defaultValue = "10",
            doc = "The number of lines to print."),
      })
  CommandApi tail(StarlarkInt lines, Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "sort",
      doc = "Sorts the lines of the given files (or standard input) by their byte values.",
      extraPositionals = @Param(name = "files", doc = FILES_DOC),
      parameters = {
        @Param(
            name = "reverse",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to sort in descending order."),
        @Param(
            name = "unique",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to drop duplicate lines."),
        @Param(
            name = "numeric",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to compare lines by the number they start with."),
      })
  CommandApi sort(boolean reverse, boolean unique, boolean numeric, Tuple files)
      throws EvalException;

  @StarlarkMethod(
      name = "uniq",
      doc = "Drops adjacent duplicate lines of the given files (or standard input).",
      extraPositionals = @Param(name = "files", doc = FILES_DOC),
      parameters = {
        @Param(
            name = "count",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to prefix each line with the number of its occurrences."),
      })
  CommandApi uniq(boolean count, Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "wc",
      doc =
          "Prints the number of lines, words and/or bytes of the given files (or standard"
              + " input). Counts are separated by spaces and followed by the file name if files"
              + " are given; a total is printed if more than one file is given. If none of the"
              + " options is set, all three counts are printed.",
      extraPositionals = @Param(name = "files", doc = FILES_DOC),
      parameters = {
        @Param(
            name = "lines",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to print the number of lines."),
        @Param(
            name = "words",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to print the number of words."),
        @Param(
            name = "bytes",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to print the number of bytes."),
      })
  CommandApi wc(boolean lines, boolean words, boolean bytes, Tuple files)
      throws EvalException;

  @StarlarkMethod(
      name = "cp",
      doc =
          "Copies files or directories. If <code>dest</code> is an existing directory or more"
              + " than one source is given, the sources are copied into it. Directories are copied"
              + " recursively and missing parent directories are created.",
      parameters = {
        @Param(
            name = "src",
            doc =
                "The <a href=\"../builtins/File.html\">File</a>(s) or path(s) to copy, which are"
                    + " inferred to be inputs of the action."),
        @Param(
            name = "dest",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc =
                "The destination <a href=\"../builtins/File.html\">File</a> or path, which is"
                    + " inferred to be an output of the action."),
      })
  CommandApi cp(Object src, Object dest) throws EvalException;

  @StarlarkMethod(
      name = "mv",
      doc = "Moves a file or directory. Missing parent directories are created.",
      parameters = {
        @Param(
            name = "src",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc = "The <a href=\"../builtins/File.html\">File</a> or path to move."),
        @Param(
            name = "dest",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc =
                "The destination <a href=\"../builtins/File.html\">File</a> or path, which is"
                    + " inferred to be an output of the action."),
      })
  CommandApi mv(Object src, Object dest) throws EvalException;

  @StarlarkMethod(
      name = "mkdir",
      doc = "Creates directories, including missing parents. Existing directories are kept.",
      extraPositionals =
          @Param(
              name = "dirs",
              doc =
                  "Directory <a href=\"../builtins/File.html\">File</a>s or paths, which are"
                      + " inferred to be outputs of the action."))
  CommandApi mkdir(Tuple dirs) throws EvalException;

  @StarlarkMethod(
      name = "rm",
      doc = "Removes files or directories recursively. Missing paths are ignored.",
      extraPositionals =
          @Param(
              name = "paths",
              doc = "<a href=\"../builtins/File.html\">File</a>s or paths to remove."))
  CommandApi rm(Tuple paths) throws EvalException;

  @StarlarkMethod(
      name = "touch",
      doc = "Creates empty files if they do not exist and updates their modification time.",
      extraPositionals =
          @Param(
              name = "files",
              doc =
                  "<a href=\"../builtins/File.html\">File</a>s or paths, which are inferred to be"
                      + " outputs of the action."))
  CommandApi touch(Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "chmod",
      doc =
          "Changes the permissions of files. On Windows, only removing (<code>-w</code>) or"
              + " restoring write permission has an effect.",
      parameters = {
        @Param(
            name = "mode",
            doc =
                "A symbolic mode such as <code>+x</code> or <code>-w</code> or an octal mode such"
                    + " as <code>755</code>."),
      },
      extraPositionals =
          @Param(
              name = "files",
              doc =
                  "<a href=\"../builtins/File.html\">File</a>s or paths, which are inferred to be"
                      + " outputs of the action."))
  CommandApi chmod(String mode, Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "tee",
      doc = "Copies standard input to standard output and to the given files.",
      extraPositionals =
          @Param(
              name = "files",
              doc =
                  "<a href=\"../builtins/File.html\">File</a>s or paths, which are inferred to be"
                      + " outputs of the action."),
      parameters = {
        @Param(
            name = "append",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to append to the files instead of truncating them."),
      })
  CommandApi tee(boolean append, Tuple files) throws EvalException;

  @StarlarkMethod(
      name = "ls",
      doc =
          "Lists the entries of directories in sorted order, one per line. Directories are"
              + " suffixed with <code>/</code>. If <code>recursive</code> is true, lists the paths"
              + " of all files below each directory, relative to it.",
      extraPositionals =
          @Param(
              name = "dirs",
              doc =
                  "Directory <a href=\"../builtins/File.html\">File</a>s or paths. Defaults to the"
                      + " execution root."),
      parameters = {
        @Param(
            name = "recursive",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = "Whether to list files in subdirectories."),
      })
  CommandApi ls(boolean recursive, Tuple dirs) throws EvalException;

  @StarlarkMethod(
      name = "fail",
      doc = "Prints a message to standard error and fails the action.",
      extraPositionals =
          @Param(name = "message", doc = "The message to print, joined by spaces."))
  CommandApi fail(Tuple message) throws EvalException;

  @StarlarkMethod(
      name = "dirname",
      doc =
          "Returns a value that stands for the directory containing the given file and can be"
              + " used wherever a path is expected, for example to run a tool that writes into the"
              + " directory of a declared output.",
      parameters = {
        @Param(
            name = "file",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = String.class)},
            doc = "A <a href=\"../builtins/File.html\">File</a> or path."),
      })
  Object dirname(Object file) throws EvalException;

  @StarlarkMethod(
      name = "decode",
      doc =
          "Reconstructs commands from their JSON encoding, as produced by"
              + " <code>json.encode</code>. This allows a macro to accept a command built in a BUILD"
              + " file and pass it to a rule through a string attribute. Strings in the encoded"
              + " command that appear as keys of <code>files</code> are replaced with the"
              + " corresponding value: a <a href=\"../builtins/File.html\">File</a>, a <a"
              + " href=\"../providers/FilesToRunProvider.html\">FilesToRunProvider</a> or a list"
              + " of files. Strings used where a path is required must be resolved this way.",
      parameters = {
        @Param(name = "json", doc = "The JSON encoding of a command, pipeline or list thereof."),
        @Param(
            name = "files",
            named = true,
            defaultValue = "{}",
            doc =
                "A dictionary mapping strings (typically labels) to the files they stand for."),
      },
      useStarlarkThread = true)
  Object decode(String json, Dict<?, ?> files, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "toolchain_type",
      doc =
          "The label of the toolchain type providing the script runner. Rules using"
              + " <code>ctx.actions.run_script</code> must declare it in their"
              + " <code>toolchains</code>.",
      structField = true)
  Label toolchainType();
}
