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
ijar action
"""

load(":common/paths.bzl", "paths")
load(":common/rule_util.bzl", "create_dep")
load(":common/java/java_semantics.bzl", "semantics")

def _derived_artifact(ctx, artifact, prefix, suffix):
    name_without_ext, _ = paths.split_extension(artifact.basename)
    new_name = prefix + name_without_ext + suffix
    return ctx.actions.declare_file(new_name, sibling = artifact)

def _ijar_artifact(ctx, jar, add_prefix):
    if add_prefix:
        rule_base = paths.join("_ijar", ctx.attr.name)
        artifact_dir_fragment = paths.dirname(paths.root_relative_path(ctx, jar))
        name_without_ext, _ = paths.split_extension(jar.basename)
        ijar_basename = name_without_ext + "-ijar.jar"
        return ctx.actions.declare_file(paths.join(rule_base, artifact_dir_fragment, ijar_basename))
    else:
        return _derived_artifact(ctx, jar, "", "-ijar.jar")

def ijar_action(
        ctx,
        input_jar,
        add_prefix,
        target_label = None,
        injecting_rule_kind = None):
    interface_jar = _ijar_artifact(input_jar)

    args = ctx.actions.args()
    args.add(input_jar)
    args.add(interface_jar)
    if target_label:
        args.add("--target_label", str(target_label))
    if injecting_rule_kind:
        args.add("--injecting_rule_kind", injecting_rule_kind)

    ctx.actions.run(
        inputs = [input_jar],
        outputs = [interface_jar],
        executable = ctx.attr._java_toolchain[JavaToolchainInfo].ijar,
        args = [args],
        mnemonic = "JavaIjar",
        progress_message = "Extracting interface %{label}",
        use_default_shell_env = True,
    )

IJAR_ACTION = create_dep(
    attrs = {
        "_java_toolchain": attr.label(
            default = semantics.JAVA_TOOLCHAIN_LABEL,
            providers = [java_common.JavaToolchainInfo],
        ),
    },
)
