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
"""The toolchain rule providing the runner for `ctx.actions.run_script`."""

def _cmd_toolchain_impl(ctx):
    runner = ctx.attr.runner[DefaultInfo]
    return [
        platform_common.ToolchainInfo(
            runner = runner.files_to_run if runner.files_to_run.executable else ctx.file.runner,
        ),
    ]

cmd_toolchain = rule(
    implementation = _cmd_toolchain_impl,
    doc = """Declares a toolchain for `ctx.actions.run_script`.

Register a `toolchain` wrapping this rule to provide a runner binary for an
execution platform, for example a prebuilt `cmd_runner` from a remote
repository when cross-compiling.
""",
    attrs = {
        "runner": attr.label(
            doc = "The `cmd_runner` binary that executes scripts on the execution platform.",
            allow_single_file = True,
            cfg = "exec",
            mandatory = True,
        ),
    },
)
