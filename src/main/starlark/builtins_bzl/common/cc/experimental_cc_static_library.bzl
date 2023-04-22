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

"""This is an experimental implementation of cc_static_library.

We may change the implementation at any moment or even delete this file. Do not
rely on this.
"""

load(":common/cc/action_names.bzl", "ACTION_NAMES")
load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "artifact_category", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", "semantics")

_TEMPLATE_CONTENT = "%content%"

def _get_static_library_artifact(ctx, cc_toolchain):
    name = ctx.label.name
    new_name = cc_toolchain.get_artifact_name_for_category(
        category = artifact_category.STATIC_LIBRARY,
        output_name = cc_helper.get_base_name(name),
    )
    return ctx.actions.declare_file(cc_helper.replace_name(name, new_name))

def _collect_linker_inputs(deps):
    transitive_linker_inputs = [dep[CcInfo].linking_context.linker_inputs for dep in deps]
    return depset(transitive = transitive_linker_inputs)

def _has_objects(linker_input):
    for lib in linker_input.libraries:
        if lib.pic_objects or lib.objects:
            return True
    return False

def _has_libraries(linker_input):
    for lib in linker_input.libraries:
        if lib.pic_static_library or lib.static_library or lib.dynamic_library:
            return True
    return False

def _flatten_and_get_objects(linker_inputs):
    # Flattening a depset to get the action inputs.
    transitive_objects = []
    for linker_input in linker_inputs.to_list():
        for lib in linker_input.libraries:
            if lib.pic_objects:
                transitive_objects.append(depset(lib.pic_objects))
            elif lib.objects:
                transitive_objects.append(depset(lib.objects))

    return depset(transitive = transitive_objects)

def _archive_objects(*, actions, cc_toolchain, feature_configuration, output, objects):
    archiver_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = ACTION_NAMES.cpp_link_static_library,
    )
    archiver_variables = cc_common.create_link_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        output_file = output.path,
        is_using_linker = False,
    )
    command_line = cc_common.get_memory_inefficient_command_line(
        feature_configuration = feature_configuration,
        action_name = ACTION_NAMES.cpp_link_static_library,
        variables = archiver_variables,
    )
    args = actions.args()
    args.add_all(command_line)
    args.add_all(objects)

    if cc_common.is_enabled(
        feature_configuration = feature_configuration,
        feature_name = "archive_param_file",
    ):
        args.use_param_file("@%s", use_always = True)

    env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = ACTION_NAMES.cpp_link_static_library,
        variables = archiver_variables,
    )

    actions.run(
        executable = archiver_path,
        arguments = [args],
        env = env,
        inputs = depset(
            transitive = [cc_toolchain.all_files, objects],
        ),
        outputs = [output],
        use_default_shell_env = True,
        mnemonic = "CppTransitiveArchive",
        progress_message = "Creating static library %{output}",
    )

def _validate_archive(*, name, actions, cc_toolchain, feature_configuration, archive):
    if not cc_common.action_is_enabled(
        feature_configuration = feature_configuration,
        action_name = ACTION_NAMES.validate_static_library,
    ):
        return None

    validation_output = actions.declare_file(name + "_validation_output.txt")

    validator_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = ACTION_NAMES.validate_static_library,
    )
    args = actions.args()
    args.add(archive)
    args.add(validation_output)

    actions.run(
        executable = validator_path,
        arguments = [args],
        inputs = depset(
            direct = [archive],
            transitive = [cc_toolchain.all_files],
        ),
        outputs = [validation_output],
        mnemonic = "ValidateStaticLibrary",
        progress_message = "Validating static library %{input}",
    )

    return validation_output

def _format_linkdeps(linker_input):
    if _has_objects(linker_input):
        # Has been added to the archive.
        return None
    if not _has_libraries(linker_input):
        # Does not require linking, but may have contributed linkopts.
        return None
    return str(linker_input.owner) + "\n"

def _create_linkdeps_file(*, actions, template, prefix, linker_inputs):
    linkdeps_file = actions.declare_file(prefix + "_linkopts.txt")
    linkdeps_dict = actions.template_dict().add_joined(
        _TEMPLATE_CONTENT,
        linker_inputs,
        join_with = "",
        map_each = _format_linkdeps,
    )
    actions.expand_template(
        template = template,
        output = linkdeps_file,
        computed_substitutions = linkdeps_dict,
    )
    return linkdeps_file

def _format_linkopts(linker_input):
    return [opt + "\n" for opt in linker_input.user_link_flags]

def _create_linkopts_file(*, actions, template, prefix, linker_inputs):
    linkopts_file = actions.declare_file(prefix + "_linkopts.txt")
    linkopts_dict = actions.template_dict().add_joined(
        _TEMPLATE_CONTENT,
        linker_inputs,
        join_with = "",
        map_each = _format_linkopts,
    )
    actions.expand_template(
        template = template,
        output = linkopts_file,
        computed_substitutions = linkopts_dict,
    )
    return linkopts_file

def _format_targets(linker_input):
    if not _has_objects(linker_input):
        return None
    return str(linker_input.owner) + "\n"

def _create_targets_file(*, actions, template, prefix, linker_inputs):
    targets_file = actions.declare_file(prefix + "_targets.txt")
    targets_dict = actions.template_dict().add_joined(
        _TEMPLATE_CONTENT,
        linker_inputs,
        join_with = "",
        map_each = _format_linkopts,
        uniquify = True,
    )
    actions.expand_template(
        template = template,
        output = targets_file,
        computed_substitutions = targets_dict,
    )
    return targets_file

def _cc_static_library_impl(ctx):
    if not cc_common.check_experimental_cc_static_library():
        fail("cc_static_library is an experimental rule and must be enabled with --experimental_cc_static_library")

    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )

    linker_inputs = _collect_linker_inputs(ctx.attr.deps)

    output_archive = _get_static_library_artifact(ctx, cc_toolchain)

    _archive_objects(
        actions = ctx.actions,
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        output = output_archive,
        objects = _flatten_and_get_objects(linker_inputs),
    )

    validation_output = _validate_archive(
        name = ctx.label.name,
        actions = ctx.actions,
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        archive = output_archive,
    )

    linkdeps_file = _create_linkdeps_file(
        actions = ctx.actions,
        template = ctx.file._lazy_content_template,
        prefix = ctx.attr.name,
        linker_inputs = linker_inputs,
    )

    linkopts_file = _create_linkopts_file(
        actions = ctx.actions,
        template = ctx.file._lazy_content_template,
        prefix = ctx.attr.name,
        linker_inputs = linker_inputs,
    )

    targets_file = _create_targets_file(
        actions = ctx.actions,
        template = ctx.file._lazy_content_template,
        prefix = ctx.attr.name,
        linker_inputs = linker_inputs,
    )

    output_groups = {
        "linkdeps": depset([linkdeps_file]),
        "linkopts": depset([linkopts_file]),
        "targets": depset([targets_file]),
    }
    if validation_output:
        output_groups["_validation"] = depset([validation_output])

    runfiles = ctx.runfiles().merge_all([
        dep[DefaultInfo].default_runfiles
        for dep in ctx.attr.deps
    ])

    return [
        DefaultInfo(
            files = depset([output_archive]),
            runfiles = runfiles,
        ),
        OutputGroupInfo(**output_groups),
    ]

cc_static_library = rule(
    implementation = _cc_static_library_impl,
    attrs = {
        "deps": attr.label_list(providers = [CcInfo]),
        "_cc_toolchain": attr.label(
            default = "@" + semantics.get_repo() + "//tools/cpp:current_cc_toolchain",
        ),
        "_lazy_content_template": attr.label(
            default = "@" + semantics.get_repo() + "//tools/cpp:lazy_content_template.txt",
            allow_single_file = True,
        ),
    },
    toolchains = cc_helper.use_cpp_toolchain(),
    fragments = ["cpp"],
)
