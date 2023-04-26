load(
    "@bazel_tools//tools/cpp:cc_toolchain_config_lib.bzl",
    "action_config",
    "artifact_name_pattern",
    "env_entry",
    "env_set",
    "feature",
    "feature_set",
    "flag_group",
    "flag_set",
    "tool",
    "tool_path",
    "variable_with_value",
    "with_feature_set",
)
load("@bazel_tools//tools/build_defs/cc:action_names.bzl", "ACTION_NAMES")
load("@rules_testing//lib:util.bzl", "util")

def _mock_cc_toolchain_config_impl(ctx):
    return cc_common.create_cc_toolchain_config_info(
        ctx = ctx,
        action_configs = [
            action_config(
                action_name = ACTION_NAMES.cpp_link_static_library,
                enabled = True,
                tools = [
                    tool(
                        path = "my_ar",
                        execution_requirements = ["my_requirement"],
                    ),
                ],
            ),
        ],
        features = [
            feature(
                name = "archiver_flags",
                enabled = True,
                env_sets = [
                    env_set(
                        actions = [ACTION_NAMES.cpp_link_static_library],
                        env_entries = [
                            env_entry(
                                key = "MY_KEY",
                                value = "my_value",
                            ),
                        ],
                    ),
                ],
                flag_sets = [
                    flag_set(
                        actions = [ACTION_NAMES.cpp_link_static_library],
                        flag_groups = [
                            flag_group(flags = ["abc"]),
                            flag_group(
                                flags = ["/MY_OUT:%{output_execpath}"],
                                expand_if_available = "output_execpath",
                            ),
                        ],
                    ),
                ],
            ),
        ],
        artifact_name_patterns = [
            artifact_name_pattern(
                category_name = "static_library",
                prefix = "prefix",
                extension = ".lib",
            ),
        ],
        toolchain_identifier = "mock_toolchain",
        host_system_name = "local",
        target_system_name = "local",
        target_cpu = "local",
        target_libc = "local",
        compiler = "compiler",
    )

_mock_cc_toolchain_config = rule(
    implementation = _mock_cc_toolchain_config_impl,
    provides = [CcToolchainConfigInfo],
)

def mock_cc_toolchain(name):
    _mock_cc_toolchain_config(
        name = name + "_config",
    )

    empty = name + "_empty"
    native.filegroup(
        name = empty,
    )

    archiver = util.empty_file(
        name = name + "_my_ar",
    )

    all_files = name + "_all_files"
    native.filegroup(
        name = all_files,
        srcs = [archiver],
    )

    native.cc_toolchain(
        name = name + "_cc_toolchain",
        toolchain_config = name + "_config",
        all_files = all_files,
        dwp_files = empty,
        compiler_files = empty,
        linker_files = empty,
        objcopy_files = empty,
        strip_files = empty,
    )

    native.toolchain(
        name = name,
        toolchain = name + "_cc_toolchain",
        toolchain_type = "@bazel_tools//tools/cpp:toolchain_type",
    )
