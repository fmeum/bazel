# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""The `script` rule: a portable, shell-free replacement for `genrule`.

Requires `--experimental_starlark_cmd`.
"""

def _label_keys(label, package_label):
    """Returns the strings that may be used to refer to `label` in a script."""
    keys = [str(label), "//%s:%s" % (label.package, label.name)]
    if label.repo_name:
        keys.append("@%s//%s:%s" % (label.repo_name, label.package, label.name))
    if label.package == package_label.package and label.repo_name == package_label.repo_name:
        keys.append(":" + label.name)
        keys.append(label.name)
    return keys

def _script_impl(ctx):
    files = {}

    def add(label, value):
        for key in _label_keys(label, ctx.label):
            files[key] = value

    for target in ctx.attr.srcs:
        target_files = target[DefaultInfo].files.to_list()
        add(target.label, target_files[0] if len(target_files) == 1 else target_files)
    tools = []
    for target in ctx.attr.tools:
        files_to_run = target[DefaultInfo].files_to_run
        if files_to_run and files_to_run.executable:
            add(target.label, files_to_run)
            tools.append(files_to_run)
        else:
            target_files = target[DefaultInfo].files.to_list()
            add(target.label, target_files[0] if len(target_files) == 1 else target_files)
            tools.append(target[DefaultInfo].files)
    for label, out in zip(ctx.attr.outs, ctx.outputs.outs):
        add(label, out)

    script = cmd.decode(ctx.attr.cmd, files = files)
    ctx.actions.run_script(
        script,
        outputs = ctx.outputs.outs,
        tools = tools,
        mnemonic = ctx.attr.mnemonic or "Script",
        progress_message = ctx.attr.message or "Running script for %{label}",
        env = ctx.attr.env,
        use_default_shell_env = True,
        execution_requirements = {tag: "" for tag in ctx.attr.tags if tag in ["local", "no-cache", "no-remote", "no-sandbox", "requires-network"]},
    )
    return [DefaultInfo(files = depset(ctx.outputs.outs))]

_script = rule(
    implementation = _script_impl,
    attrs = {
        "cmd": attr.string(
            doc = "The JSON encoding of the command to run.",
            mandatory = True,
        ),
        "srcs": attr.label_list(
            doc = "Inputs of the script.",
            allow_files = True,
        ),
        "outs": attr.output_list(
            doc = "Files created by the script.",
            mandatory = True,
        ),
        "tools": attr.label_list(
            doc = "Tools run by the script, built for the execution platform.",
            allow_files = True,
            cfg = "exec",
        ),
        "env": attr.string_dict(
            doc = "Environment variables set for the whole script.",
        ),
        "message": attr.string(
            doc = "The progress message to show while the script runs.",
        ),
        "mnemonic": attr.string(
            doc = "The mnemonic of the action.",
        ),
    },
    toolchains = ["//tools/cmd:toolchain_type"],
)

def script(name, cmd, **kwargs):
    """A portable, shell-free replacement for `genrule`.

    The command is built with the `cmd` module directly in the BUILD file.
    Strings in the command that match entries of `srcs`, `outs` or `tools`
    (by label or, within the same package, by name) are replaced with the
    corresponding files:

    ```python
    load("@bazel_tools//tools/cmd:defs.bzl", "script")

    script(
        name = "todos",
        srcs = ["main.cc"],
        outs = ["todos.txt"],
        cmd = cmd.grep("TODO", "main.cc") | cmd.sort() > "todos.txt",
    )

    script(
        name = "generated",
        srcs = ["spec.json"],
        outs = ["gen/a.h", "gen/b.h"],
        tools = [":generator"],
        cmd = cmd(":generator", "--out-dir", cmd.dirname("gen/a.h"), "spec.json"),
    )
    ```

    Args:
      name: The name of the target.
      cmd: A `Command`, a `Pipeline` or a list of those, built with the `cmd` module.
        Files written by external tools must be listed in `outs`; files written by
        redirections or builtins such as `cmd.cp` are inferred automatically.
      **kwargs: `srcs`, `outs`, `tools`, `env`, `message`, `mnemonic` and common attributes.
    """
    _script(
        name = name,
        cmd = json.encode(cmd),
        **kwargs
    )
