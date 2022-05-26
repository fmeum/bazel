# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""
Import deps check action
"""

load(":common/rule_util.bzl", "create_dep")
load(":common/java/java_semantics.bzl", "semantics")

def _convert_error_flag(level):
    if level == "error":
        return "--checking_mode=error"
    elif level == "warning":
        return "--checking_mode=warning"
    elif level == "off":
        return "--checking_mode=silence"
    else:
        fail("Unhandled deps checking level: " + level)

def import_deps_check_action(
        ctx,
        jars_to_check,
        bootclasspath,
        declared_deps,
        transitive_deps,
        import_deps_checking_level,
        jdeps_artifact,
        rule_label,
        import_deps_checker):

    transitive_inputs = [
        jars_to_check,
        declared_deps,
        transitive_deps,
        bootclasspath,
    ]

    args = []
    transitive_tools = []

    if import_deps_checker:
        host_java_runtime_info = ctx.attr._host_java_runtime[java_common.JavaRuntimeInfo]
        executable = host_java_runtime_info.java_executable_exec_path
        transitive_tools = [host_java_runtime_info.files]

        java_args = ctx.actions.args()
        java_args.add(import_deps_checker)
        args.append(java_args)
    else:
        executable = ctx.executable._import_deps_checker

    checker_args = ctx.actions.args()
    checker_args.add_all(jars_to_check, before_each = "--input")
    checker_args.add_all(declared_deps, before_each = "--directdep")
    checker_args.add_all(transitive_deps, before_each = "--classpath_entry")
    checker_args.add_all(bootclasspath, before_each = "--bootclasspath_entry")
    checker_args.add(_convert_error_flag(import_deps_checking_level))
    checker_args.add("--jdeps_output", jdeps_artifact)
    checker_args.add("--rule_label", str(rule_label))
    args.append(checker_args)

    ctx.actions.run(
        outputs = [jdeps_artifact],
        inputs = depset(transitive = transitive_deps),
        executable = executable,
        args = args,
        tools = depset(transitive = transitive_tools),
        mnemonic = "ImportDepsChecker",
        progress_message = "Checking the completeness of deps for %{input}",
        use_default_shell_env = True,
    )

IMPORT_DEPS_CHECK_ACTION = create_dep(
    attrs = {
        "_host_java_runtime": attr.label(
            default = semantics.HOST_JAVA_RUNTIME_LABEL,
            providers = [java_common.JavaRuntimeInfo],
        ),
        "_import_deps_checker": attr.label(
            default = semantics.IMPORT_DEPS_CHECKER_LABEL,
            executable = True,
            cfg = "exec",
        ),
    },
)
