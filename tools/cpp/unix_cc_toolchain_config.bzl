# Copyright 2019 The Bazel Authors. All rights reserved.
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

"""A Starlark cc_toolchain configuration rule"""

load("@bazel_tools//tools/build_defs/cc:action_names.bzl", "ACTION_NAMES")
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

def _target_os_version(ctx):
    platform_type = ctx.fragments.apple.single_arch_platform.platform_type
    xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
    return xcode_config.minimum_os_for_platform_type(platform_type)

def layering_check_features(compiler, extra_flags_per_feature, is_macos):
    if compiler != "clang":
        return []
    return [
        feature(
            name = "use_module_maps",
            requires = [feature_set(features = ["module_maps"])],
            flag_sets = [
                flag_set(
                    actions = [
                        ACTION_NAMES.c_compile,
                        ACTION_NAMES.cpp_compile,
                        ACTION_NAMES.cpp_header_parsing,
                        ACTION_NAMES.cpp_module_compile,
                    ],
                    flag_groups = [
                        flag_group(
                            # macOS requires -Xclang because of a bug in Apple Clang
                            flags = (["-Xclang"] if is_macos else []) + [
                                "-fmodule-name=%{module_name}",
                            ] + (["-Xclang"] if is_macos else []) + [
                                "-fmodule-map-file=%{module_map_file}",
                            ] + extra_flags_per_feature.get("use_module_maps", []),
                        ),
                    ],
                ),
            ],
        ),

        # Tell blaze we support module maps in general, so they will be generated
        # for all c/c++ rules.
        # Note: not all C++ rules support module maps; thus, do not imply this
        # feature from other features - instead, require it.
        feature(name = "module_maps", enabled = True),
        feature(
            name = "layering_check",
            implies = ["use_module_maps"],
            flag_sets = [
                flag_set(
                    actions = [
                        ACTION_NAMES.c_compile,
                        ACTION_NAMES.cpp_compile,
                        ACTION_NAMES.cpp_header_parsing,
                        ACTION_NAMES.cpp_module_compile,
                    ],
                    flag_groups = [
                        flag_group(flags = [
                            "-fmodules-strict-decluse",
                            "-Wprivate-header",
                        ]),
                        flag_group(
                            iterate_over = "dependent_module_map_files",
                            flags = (["-Xclang"] if is_macos else []) + [
                                "-fmodule-map-file=%{dependent_module_map_files}",
                            ],
                        ),
                    ],
                ),
            ],
        ),
    ]

def parse_headers_support(parse_headers_tool_path):
    if not parse_headers_tool_path:
        return [], []
    action_configs = [
        action_config(
            action_name = ACTION_NAMES.c_header_parsing,
            tools = [
                tool(path = parse_headers_tool_path),
            ],
            flag_sets = [
                flag_set(
                    flag_groups = [
                        flag_group(
                            flags = [
                                "-xc-header",
                                "-fsyntax-only",
                            ],
                        ),
                    ],
                ),
            ],
            implies = [
                # Copied from the legacy feature definition in CppActionConfigs.java.
                "legacy_compile_flags",
                "user_compile_flags",
                "sysroot",
                "unfiltered_compile_flags",
                "compiler_input_flags",
                "compiler_output_flags",
            ],
        ),
        action_config(
            action_name = ACTION_NAMES.cpp_header_parsing,
            tools = [
                tool(path = parse_headers_tool_path),
            ],
            flag_sets = [
                flag_set(
                    flag_groups = [
                        flag_group(
                            flags = [
                                "-xc++-header",
                                "-fsyntax-only",
                            ],
                        ),
                    ],
                ),
            ],
            implies = [
                # Copied from the legacy feature definition in CppActionConfigs.java.
                "legacy_compile_flags",
                "user_compile_flags",
                "sysroot",
                "unfiltered_compile_flags",
                "compiler_input_flags",
                "compiler_output_flags",
            ],
        ),
    ]
    features = [
        feature(name = "parse_headers"),
    ]
    return action_configs, features

all_compile_actions = [
    ACTION_NAMES.c_compile,
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.linkstamp_compile,
    ACTION_NAMES.assemble,
    ACTION_NAMES.preprocess_assemble,
    ACTION_NAMES.c_header_parsing,
    ACTION_NAMES.cpp_header_parsing,
    ACTION_NAMES.cpp_module_compile,
    ACTION_NAMES.cpp_module_codegen,
    ACTION_NAMES.clif_match,
    ACTION_NAMES.lto_backend,
]

all_cpp_compile_actions = [
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.linkstamp_compile,
    ACTION_NAMES.cpp_header_parsing,
    ACTION_NAMES.cpp_module_compile,
    ACTION_NAMES.cpp_module_codegen,
    ACTION_NAMES.clif_match,
]

preprocessor_compile_actions = [
    ACTION_NAMES.c_compile,
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.linkstamp_compile,
    ACTION_NAMES.preprocess_assemble,
    ACTION_NAMES.cpp_header_parsing,
    ACTION_NAMES.cpp_module_compile,
    ACTION_NAMES.clif_match,
]

codegen_compile_actions = [
    ACTION_NAMES.c_compile,
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.linkstamp_compile,
    ACTION_NAMES.assemble,
    ACTION_NAMES.preprocess_assemble,
    ACTION_NAMES.cpp_module_codegen,
    ACTION_NAMES.lto_backend,
]

all_link_actions = [
    ACTION_NAMES.cpp_link_executable,
    ACTION_NAMES.cpp_link_dynamic_library,
    ACTION_NAMES.cpp_link_nodeps_dynamic_library,
]

lto_index_actions = [
    ACTION_NAMES.lto_index_for_executable,
    ACTION_NAMES.lto_index_for_dynamic_library,
    ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
]

def _sanitizer_feature(name = "", specific_compile_flags = [], specific_link_flags = []):
    return feature(
        name = name,
        flag_sets = [
            flag_set(
                actions = all_compile_actions,
                flag_groups = [
                    flag_group(flags = [
                        "-fno-omit-frame-pointer",
                        "-fno-sanitize-recover=all",
                    ] + specific_compile_flags),
                ],
                with_features = [
                    with_feature_set(features = [name]),
                ],
            ),
            flag_set(
                actions = all_link_actions,
                flag_groups = [
                    flag_group(flags = specific_link_flags),
                ],
                with_features = [
                    with_feature_set(features = [name]),
                ],
            ),
        ],
    )

def _impl(ctx):
    tool_paths = [
        tool_path(name = name, path = path)
        for name, path in ctx.attr.tool_paths.items()
    ]
    action_configs = []

    llvm_cov = ctx.attr.tool_paths.get("llvm-cov")
    if llvm_cov:
        llvm_cov_action = action_config(
            action_name = ACTION_NAMES.llvm_cov,
            tools = [
                tool(
                    path = llvm_cov,
                ),
            ],
        )
        action_configs.append(llvm_cov_action)

    objcopy = ctx.attr.tool_paths.get("objcopy")
    if objcopy:
        objcopy_action = action_config(
            action_name = ACTION_NAMES.objcopy_embed_data,
            tools = [
                tool(
                    path = objcopy,
                ),
            ],
        )
        action_configs.append(objcopy_action)

    supports_pic_feature = feature(
        name = "supports_pic",
        enabled = True,
    )
    supports_start_end_lib_feature = feature(
        name = "supports_start_end_lib",
        enabled = True,
    )

    gcc_quoting_for_param_files_feature = feature(
        name = "gcc_quoting_for_param_files",
        enabled = True,
    )

    static_link_cpp_runtimes_feature = feature(
        name = "static_link_cpp_runtimes",
        enabled = False,
    )

    default_compile_flags_feature = feature(
        name = "default_compile_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_compile_actions,
                flag_groups = [
                    flag_group(
                        # Security hardening requires optimization.
                        # We need to undef it as some distributions now have it enabled by default.
                        flags = ["-U_FORTIFY_SOURCE"],
                    ),
                ],
                with_features = [
                    with_feature_set(
                        not_features = ["thin_lto"],
                    ),
                ],
            ),
            flag_set(
                actions = all_compile_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.compile_flags,
                    ),
                ] if ctx.attr.compile_flags else []),
            ),
            flag_set(
                actions = all_compile_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.dbg_compile_flags,
                    ),
                ] if ctx.attr.dbg_compile_flags else []),
                with_features = [with_feature_set(features = ["dbg"])],
            ),
            flag_set(
                actions = all_compile_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.opt_compile_flags,
                    ),
                ] if ctx.attr.opt_compile_flags else []),
                with_features = [with_feature_set(features = ["opt"])],
            ),
            flag_set(
                actions = [ACTION_NAMES.c_compile],
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.conly_flags,
                    ),
                ] if ctx.attr.conly_flags else []),
            ),
            flag_set(
                actions = all_cpp_compile_actions + [ACTION_NAMES.lto_backend],
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.cxx_flags,
                    ),
                ] if ctx.attr.cxx_flags else []),
            ),
        ],
    )

    default_link_flags_feature = feature(
        name = "default_link_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.link_flags,
                    ),
                ] if ctx.attr.link_flags else []),
            ),
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.opt_link_flags,
                    ),
                ] if ctx.attr.opt_link_flags else []),
                with_features = [with_feature_set(features = ["opt"])],
            ),
        ],
        env_sets = [
            env_set(
                actions = all_link_actions + lto_index_actions + [ACTION_NAMES.cpp_link_static_library],
                env_entries = ([
                    env_entry(
                        # Required for hermetic links on macOS
                        key = "ZERO_AR_DATE",
                        value = "1",
                    ),
                ]),
            ),
        ],
    )

    dbg_feature = feature(name = "dbg")

    opt_feature = feature(name = "opt")

    sysroot_feature = feature(
        name = "sysroot",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.cpp_module_codegen,
                    ACTION_NAMES.lto_backend,
                    ACTION_NAMES.clif_match,
                ] + all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["--sysroot=%{sysroot}"],
                        expand_if_available = "sysroot",
                    ),
                ],
            ),
        ],
    )

    compiler_input_flags_feature = feature(
        name = "compiler_input_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.cpp_module_codegen,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                    ACTION_NAMES.lto_backend,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-c", "%{source_file}"],
                        expand_if_available = "source_file",
                    ),
                ],
            ),
        ],
    )

    compiler_output_flags_feature = feature(
        name = "compiler_output_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.cpp_module_codegen,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                    ACTION_NAMES.lto_backend,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-S"],
                        expand_if_available = "output_assembly_file",
                    ),
                    flag_group(
                        flags = ["-E"],
                        expand_if_available = "output_preprocess_file",
                    ),
                    flag_group(
                        flags = ["-o", "%{output_file}"],
                        expand_if_available = "output_file",
                    ),
                ],
            ),
        ],
    )

    fdo_optimize_feature = feature(
        name = "fdo_optimize",
        flag_sets = [
            flag_set(
                actions = [ACTION_NAMES.c_compile, ACTION_NAMES.cpp_compile],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-fprofile-use=%{fdo_profile_path}",
                            "-fprofile-correction",
                        ],
                        expand_if_available = "fdo_profile_path",
                    ),
                ],
            ),
        ],
        provides = ["profile"],
    )

    supports_dynamic_linker_feature = feature(name = "supports_dynamic_linker", enabled = True)

    user_compile_flags_feature = feature(
        name = "user_compile_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_compile_actions,
                flag_groups = [
                    flag_group(
                        flags = ["%{user_compile_flags}"],
                        iterate_over = "user_compile_flags",
                        expand_if_available = "user_compile_flags",
                    ),
                ],
            ),
        ],
    )

    unfiltered_compile_flags_feature = feature(
        name = "unfiltered_compile_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_compile_actions,
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.unfiltered_compile_flags,
                    ),
                ] if ctx.attr.unfiltered_compile_flags else []),
            ),
        ],
    )

    library_search_directories_feature = feature(
        name = "library_search_directories",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["-L%{library_search_directories}"],
                        iterate_over = "library_search_directories",
                        expand_if_available = "library_search_directories",
                    ),
                ],
            ),
        ],
    )

    static_libgcc_feature = feature(
        name = "static_libgcc",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_executable,
                    ACTION_NAMES.cpp_link_dynamic_library,
                    ACTION_NAMES.lto_index_for_executable,
                    ACTION_NAMES.lto_index_for_dynamic_library,
                ],
                flag_groups = [flag_group(flags = ["-static-libgcc"])],
                with_features = [
                    with_feature_set(features = ["static_link_cpp_runtimes"]),
                ],
            ),
        ],
    )

    pic_feature = feature(
        name = "pic",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_module_codegen,
                    ACTION_NAMES.cpp_module_compile,
                ],
                flag_groups = [
                    flag_group(flags = ["-fPIC"], expand_if_available = "pic"),
                ],
            ),
        ],
    )

    per_object_debug_info_feature = feature(
        name = "per_object_debug_info",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_module_codegen,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-gsplit-dwarf", "-g"],
                        expand_if_available = "per_object_debug_info_file",
                    ),
                ],
            ),
        ],
    )

    preprocessor_defines_feature = feature(
        name = "preprocessor_defines",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.clif_match,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-D%{preprocessor_defines}"],
                        iterate_over = "preprocessor_defines",
                    ),
                ],
            ),
        ],
    )

    cs_fdo_optimize_feature = feature(
        name = "cs_fdo_optimize",
        flag_sets = [
            flag_set(
                actions = [ACTION_NAMES.lto_backend],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-fprofile-use=%{fdo_profile_path}",
                            "-Wno-profile-instr-unprofiled",
                            "-Wno-profile-instr-out-of-date",
                            "-fprofile-correction",
                        ],
                        expand_if_available = "fdo_profile_path",
                    ),
                ],
            ),
        ],
        provides = ["csprofile"],
    )

    autofdo_feature = feature(
        name = "autofdo",
        flag_sets = [
            flag_set(
                actions = [ACTION_NAMES.c_compile, ACTION_NAMES.cpp_compile],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-fauto-profile=%{fdo_profile_path}",
                            "-fprofile-correction",
                        ],
                        expand_if_available = "fdo_profile_path",
                    ),
                ],
            ),
        ],
        provides = ["profile"],
    )

    runtime_library_search_directories_feature = feature(
        name = "runtime_library_search_directories",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        iterate_over = "runtime_library_search_directories",
                        flag_groups = [
                            flag_group(
                                flags = [
                                    "-Xlinker",
                                    "-rpath",
                                    "-Xlinker",
                                    "$EXEC_ORIGIN/%{runtime_library_search_directories}",
                                ],
                                expand_if_true = "is_cc_test",
                            ),
                            flag_group(
                                flags = [
                                    "-Xlinker",
                                    "-rpath",
                                    "-Xlinker",
                                    "$ORIGIN/%{runtime_library_search_directories}",
                                ],
                                expand_if_false = "is_cc_test",
                            ),
                        ],
                        expand_if_available =
                            "runtime_library_search_directories",
                    ),
                ],
                with_features = [
                    with_feature_set(features = ["static_link_cpp_runtimes"]),
                ],
            ),
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        iterate_over = "runtime_library_search_directories",
                        flag_groups = [
                            flag_group(
                                flags = [
                                    "-Xlinker",
                                    "-rpath",
                                    "-Xlinker",
                                    "$ORIGIN/%{runtime_library_search_directories}",
                                ],
                            ),
                        ],
                        expand_if_available =
                            "runtime_library_search_directories",
                    ),
                ],
                with_features = [
                    with_feature_set(
                        not_features = ["static_link_cpp_runtimes"],
                    ),
                ],
            ),
        ],
    )

    fission_support_feature = feature(
        name = "fission_support",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["-Wl,--gdb-index"],
                        expand_if_available = "is_using_fission",
                    ),
                ],
            ),
        ],
    )

    shared_flag_feature = feature(
        name = "shared_flag",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_dynamic_library,
                    ACTION_NAMES.cpp_link_nodeps_dynamic_library,
                    ACTION_NAMES.lto_index_for_dynamic_library,
                    ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
                ],
                flag_groups = [flag_group(flags = ["-shared"])],
            ),
        ],
    )

    random_seed_feature = feature(
        name = "random_seed",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_module_codegen,
                    ACTION_NAMES.cpp_module_compile,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-frandom-seed=%{output_file}"],
                        expand_if_available = "output_file",
                    ),
                ],
            ),
        ],
    )

    includes_feature = feature(
        name = "includes",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.clif_match,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-include", "%{includes}"],
                        iterate_over = "includes",
                        expand_if_available = "includes",
                    ),
                ],
            ),
        ],
    )

    fdo_instrument_feature = feature(
        name = "fdo_instrument",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                ] + all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = [
                            "-fprofile-generate=%{fdo_instrument_path}",
                            "-fno-data-sections",
                        ],
                        expand_if_available = "fdo_instrument_path",
                    ),
                ],
            ),
        ],
        provides = ["profile"],
    )

    cs_fdo_instrument_feature = feature(
        name = "cs_fdo_instrument",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.lto_backend,
                ] + all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = [
                            "-fcs-profile-generate=%{cs_fdo_instrument_path}",
                        ],
                        expand_if_available = "cs_fdo_instrument_path",
                    ),
                ],
            ),
        ],
        provides = ["csprofile"],
    )

    include_paths_feature = feature(
        name = "include_paths",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.clif_match,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-iquote", "%{quote_include_paths}"],
                        iterate_over = "quote_include_paths",
                    ),
                    flag_group(
                        flags = ["-I%{include_paths}"],
                        iterate_over = "include_paths",
                    ),
                    flag_group(
                        flags = ["-isystem", "%{system_include_paths}"],
                        iterate_over = "system_include_paths",
                    ),
                ],
            ),
        ],
    )

    external_include_paths_feature = feature(
        name = "external_include_paths",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.clif_match,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-isystem", "%{external_include_paths}"],
                        iterate_over = "external_include_paths",
                        expand_if_available = "external_include_paths",
                    ),
                ],
            ),
        ],
    )

    strip_debug_symbols_feature = feature(
        name = "strip_debug_symbols",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["-Wl,-S"],
                        expand_if_available = "strip_debug_symbols",
                    ),
                ],
            ),
        ],
    )

    build_interface_libraries_feature = feature(
        name = "build_interface_libraries",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_dynamic_library,
                    ACTION_NAMES.cpp_link_nodeps_dynamic_library,
                    ACTION_NAMES.lto_index_for_dynamic_library,
                    ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
                ],
                flag_groups = [
                    flag_group(
                        flags = [
                            "%{generate_interface_library}",
                            "%{interface_library_builder_path}",
                            "%{interface_library_input_path}",
                            "%{interface_library_output_path}",
                        ],
                        expand_if_available = "generate_interface_library",
                    ),
                ],
                with_features = [
                    with_feature_set(
                        features = ["supports_interface_shared_libraries"],
                    ),
                ],
            ),
        ],
    )

    libraries_to_link_feature = feature(
        name = "libraries_to_link",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        iterate_over = "libraries_to_link",
                        flag_groups = [
                            flag_group(
                                flags = ["-Wl,--start-lib"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                            ),
                            flag_group(
                                flags = ["-Wl,-whole-archive"],
                                expand_if_true =
                                    "libraries_to_link.is_whole_archive",
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                            ),
                            flag_group(
                                flags = ["%{libraries_to_link.object_files}"],
                                iterate_over = "libraries_to_link.object_files",
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                            ),
                            flag_group(
                                flags = ["%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file",
                                ),
                            ),
                            flag_group(
                                flags = ["%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "interface_library",
                                ),
                            ),
                            flag_group(
                                flags = ["%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                            ),
                            flag_group(
                                flags = ["-l%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "dynamic_library",
                                ),
                            ),
                            flag_group(
                                flags = ["-l:%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "versioned_dynamic_library",
                                ),
                            ),
                            flag_group(
                                flags = ["-Wl,-no-whole-archive"],
                                expand_if_true = "libraries_to_link.is_whole_archive",
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                            ),
                            flag_group(
                                flags = ["-Wl,--end-lib"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                            ),
                        ],
                        expand_if_available = "libraries_to_link",
                    ),
                    flag_group(
                        flags = ["-Wl,@%{thinlto_param_file}"],
                        expand_if_true = "thinlto_param_file",
                    ),
                ],
            ),
        ],
    )

    user_link_flags_feature = feature(
        name = "user_link_flags",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["%{user_link_flags}"],
                        iterate_over = "user_link_flags",
                        expand_if_available = "user_link_flags",
                    ),
                ],
            ),
        ],
    )

    default_link_libs_feature = feature(
        name = "default_link_libs",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [flag_group(flags = ctx.attr.link_libs)] if ctx.attr.link_libs else [],
            ),
        ],
    )

    fdo_prefetch_hints_feature = feature(
        name = "fdo_prefetch_hints",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.lto_backend,
                ],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-mllvm",
                            "-prefetch-hints-file=%{fdo_prefetch_hints_path}",
                        ],
                        expand_if_available = "fdo_prefetch_hints_path",
                    ),
                ],
            ),
        ],
    )

    linkstamps_feature = feature(
        name = "linkstamps",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["%{linkstamp_paths}"],
                        iterate_over = "linkstamp_paths",
                        expand_if_available = "linkstamp_paths",
                    ),
                ],
            ),
        ],
    )

    is_linux = ctx.attr.target_libc != "macosx"
    libtool_feature = feature(
        name = "libtool",
        enabled = not is_linux,
    )

    archiver_flags_feature = feature(
        name = "archiver_flags",
        flag_sets = [
            flag_set(
                actions = [ACTION_NAMES.cpp_link_static_library],
                flag_groups = [
                    flag_group(
                        flags = [
                            "rcsD" if is_linux else "rcs",
                            "%{output_execpath}",
                        ],
                        expand_if_available = "output_execpath",
                    ),
                ],
                with_features = [
                    with_feature_set(
                        not_features = ["libtool"],
                    ),
                ],
            ),
            flag_set(
                actions = [ACTION_NAMES.cpp_link_static_library],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-static",
                            "-o",
                            "%{output_execpath}",
                        ],
                        expand_if_available = "output_execpath",
                    ),
                ],
                with_features = [
                    with_feature_set(
                        features = ["libtool"],
                    ),
                ],
            ),
            flag_set(
                actions = [ACTION_NAMES.cpp_link_static_library],
                flag_groups = [
                    flag_group(
                        iterate_over = "libraries_to_link",
                        flag_groups = [
                            flag_group(
                                flags = ["%{libraries_to_link.name}"],
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file",
                                ),
                            ),
                            flag_group(
                                flags = ["%{libraries_to_link.object_files}"],
                                iterate_over = "libraries_to_link.object_files",
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                            ),
                        ],
                        expand_if_available = "libraries_to_link",
                    ),
                ],
            ),
            flag_set(
                actions = [ACTION_NAMES.cpp_link_static_library],
                flag_groups = ([
                    flag_group(
                        flags = ctx.attr.archive_flags,
                    ),
                ] if ctx.attr.archive_flags else []),
            ),
        ],
    )

    force_pic_flags_feature = feature(
        name = "force_pic_flags",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_executable,
                    ACTION_NAMES.lto_index_for_executable,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-pie"],
                        expand_if_available = "force_pic",
                    ),
                ],
            ),
        ],
    )

    dependency_file_feature = feature(
        name = "dependency_file",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.clif_match,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["-MD", "-MF", "%{dependency_file}"],
                        expand_if_available = "dependency_file",
                    ),
                ],
            ),
        ],
    )

    serialized_diagnostics_file_feature = feature(
        name = "serialized_diagnostics_file",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.assemble,
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_module_compile,
                    ACTION_NAMES.objc_compile,
                    ACTION_NAMES.objcpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.clif_match,
                ],
                flag_groups = [
                    flag_group(
                        flags = ["--serialize-diagnostics", "%{serialized_diagnostics_file}"],
                        expand_if_available = "serialized_diagnostics_file",
                    ),
                ],
            ),
        ],
    )

    dynamic_library_linker_tool_feature = feature(
        name = "dynamic_library_linker_tool",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_dynamic_library,
                    ACTION_NAMES.cpp_link_nodeps_dynamic_library,
                    ACTION_NAMES.lto_index_for_dynamic_library,
                    ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
                ],
                flag_groups = [
                    flag_group(
                        flags = [" + cppLinkDynamicLibraryToolPath + "],
                        expand_if_available = "generate_interface_library",
                    ),
                ],
                with_features = [
                    with_feature_set(
                        features = ["supports_interface_shared_libraries"],
                    ),
                ],
            ),
        ],
    )

    generate_linkmap_feature = feature(
        name = "generate_linkmap",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.cpp_link_executable,
                ],
                flag_groups = [
                    flag_group(
                        flags = [
                            "-Wl,-Map=%{output_execpath}.map" if is_linux else "-Wl,-map,%{output_execpath}.map",
                        ],
                        expand_if_available = "output_execpath",
                    ),
                ],
            ),
        ],
    )

    output_execpath_flags_feature = feature(
        name = "output_execpath_flags",
        flag_sets = [
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(
                        flags = ["-o", "%{output_execpath}"],
                        expand_if_available = "output_execpath",
                    ),
                ],
            ),
        ],
    )

    # Note that we also set --coverage for c++-link-nodeps-dynamic-library. The
    # generated code contains references to gcov symbols, and the dynamic linker
    # can't resolve them unless the library is linked against gcov.
    coverage_feature = feature(
        name = "coverage",
        provides = ["profile"],
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                    ACTION_NAMES.cpp_header_parsing,
                    ACTION_NAMES.cpp_module_compile,
                ],
                flag_groups = ([
                    flag_group(flags = ctx.attr.coverage_compile_flags),
                ] if ctx.attr.coverage_compile_flags else []),
            ),
            flag_set(
                actions = all_link_actions + lto_index_actions,
                flag_groups = ([
                    flag_group(flags = ctx.attr.coverage_link_flags),
                ] if ctx.attr.coverage_link_flags else []),
            ),
        ],
    )

    thinlto_feature = feature(
        name = "thin_lto",
        flag_sets = [
            flag_set(
                actions = [
                    ACTION_NAMES.c_compile,
                    ACTION_NAMES.cpp_compile,
                ] + all_link_actions + lto_index_actions,
                flag_groups = [
                    flag_group(flags = ["-flto=thin"]),
                    flag_group(
                        expand_if_available = "lto_indexing_bitcode_file",
                        flags = [
                            "-Xclang",
                            "-fthin-link-bitcode=%{lto_indexing_bitcode_file}",
                        ],
                    ),
                ],
            ),
            flag_set(
                actions = [ACTION_NAMES.linkstamp_compile],
                flag_groups = [flag_group(flags = ["-DBUILD_LTO_TYPE=thin"])],
            ),
            flag_set(
                actions = lto_index_actions,
                flag_groups = [
                    flag_group(flags = [
                        "-flto=thin",
                        "-Wl,-plugin-opt,thinlto-index-only%{thinlto_optional_params_file}",
                        "-Wl,-plugin-opt,thinlto-emit-imports-files",
                        "-Wl,-plugin-opt,thinlto-prefix-replace=%{thinlto_prefix_replace}",
                    ]),
                    flag_group(
                        expand_if_available = "thinlto_object_suffix_replace",
                        flags = [
                            "-Wl,-plugin-opt,thinlto-object-suffix-replace=%{thinlto_object_suffix_replace}",
                        ],
                    ),
                    flag_group(
                        expand_if_available = "thinlto_merged_object_file",
                        flags = [
                            "-Wl,-plugin-opt,obj-path=%{thinlto_merged_object_file}",
                        ],
                    ),
                ],
            ),
            flag_set(
                actions = [ACTION_NAMES.lto_backend],
                flag_groups = [
                    flag_group(flags = [
                        "-c",
                        "-fthinlto-index=%{thinlto_index}",
                        "-o",
                        "%{thinlto_output_object_file}",
                        "-x",
                        "ir",
                        "%{thinlto_input_bitcode_file}",
                    ]),
                ],
            ),
        ],
    )

    treat_warnings_as_errors_feature = feature(
        name = "treat_warnings_as_errors",
        flag_sets = [
            flag_set(
                actions = [ACTION_NAMES.c_compile, ACTION_NAMES.cpp_compile],
                flag_groups = [flag_group(flags = ["-Werror"])],
            ),
            flag_set(
                actions = all_link_actions,
                flag_groups = [flag_group(
                    flags = ["-Wl,-fatal-warnings"] if is_linux else ["-Wl,-fatal_warnings"],
                )],
            ),
        ],
    )

    archive_param_file_feature = feature(
        name = "archive_param_file",
        enabled = True,
    )

    asan_feature = _sanitizer_feature(
        name = "asan",
        specific_compile_flags = [
            "-fsanitize=address",
            "-fno-common",
        ],
        specific_link_flags = [
            "-fsanitize=address",
        ],
    )

    tsan_feature = _sanitizer_feature(
        name = "tsan",
        specific_compile_flags = [
            "-fsanitize=thread",
        ],
        specific_link_flags = [
            "-fsanitize=thread",
        ],
    )

    ubsan_feature = _sanitizer_feature(
        name = "ubsan",
        specific_compile_flags = [
            "-fsanitize=undefined",
        ],
        specific_link_flags = [
            "-fsanitize=undefined",
        ],
    )

    # If you have Xcode + the CLT installed the version defaults can be
    # too old for some standard C apis such as thread locals
    macos_minimum_os_feature = feature(
        name = "macos_minimum_os",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_compile_actions + all_link_actions,
                flag_groups = [flag_group(flags = ["-mmacosx-version-min={}".format(_target_os_version(ctx))])],
            ),
        ],
    )

    # Kept for backwards compatibility with the crosstool that moved. Without
    # linking the objc runtime binaries don't link CoreFoundation for free,
    # which breaks abseil.
    macos_default_link_flags_feature = feature(
        name = "macos_default_link_flags",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = all_link_actions,
                flag_groups = [flag_group(flags = [
                    "-no-canonical-prefixes",
                    "-fobjc-link-runtime",
                ])],
            ),
        ],
    )

    # Tell bazel we support C++ modules now
    cpp_modules_feature = feature(
        name = "cpp_modules",
        # set default value to False
        # to enable the feature
        # use --features=cpp_modules
        # or add cpp_modules to features attr
        enabled = False,
    )

    # TODO(#8303): Mac crosstool should also declare every feature.
    if is_linux:
        # Linux artifact name patterns are the default.
        artifact_name_patterns = []
        features = [
            cpp_modules_feature,
            dependency_file_feature,
            serialized_diagnostics_file_feature,
            random_seed_feature,
            pic_feature,
            per_object_debug_info_feature,
            preprocessor_defines_feature,
            includes_feature,
            include_paths_feature,
            external_include_paths_feature,
            fdo_instrument_feature,
            cs_fdo_instrument_feature,
            cs_fdo_optimize_feature,
            thinlto_feature,
            fdo_prefetch_hints_feature,
            autofdo_feature,
            build_interface_libraries_feature,
            dynamic_library_linker_tool_feature,
            generate_linkmap_feature,
            shared_flag_feature,
            linkstamps_feature,
            output_execpath_flags_feature,
            runtime_library_search_directories_feature,
            library_search_directories_feature,
            libtool_feature,
            archiver_flags_feature,
            force_pic_flags_feature,
            fission_support_feature,
            strip_debug_symbols_feature,
            coverage_feature,
            supports_pic_feature,
            asan_feature,
            tsan_feature,
            ubsan_feature,
            gcc_quoting_for_param_files_feature,
            static_link_cpp_runtimes_feature,
        ] + (
            [
                supports_start_end_lib_feature,
            ] if ctx.attr.supports_start_end_lib else []
        ) + [
            default_compile_flags_feature,
            default_link_flags_feature,
            libraries_to_link_feature,
            user_link_flags_feature,
            default_link_libs_feature,
            static_libgcc_feature,
            fdo_optimize_feature,
            supports_dynamic_linker_feature,
            dbg_feature,
            opt_feature,
            user_compile_flags_feature,
            sysroot_feature,
            compiler_input_flags_feature,
            compiler_output_flags_feature,
            unfiltered_compile_flags_feature,
            treat_warnings_as_errors_feature,
            archive_param_file_feature,
        ] + layering_check_features(ctx.attr.compiler, ctx.attr.extra_flags_per_feature, is_macos = False)
    else:
        # macOS artifact name patterns differ from the defaults only for dynamic
        # libraries.
        artifact_name_patterns = [
            artifact_name_pattern(
                category_name = "dynamic_library",
                prefix = "lib",
                extension = ".dylib",
            ),
        ]
        features = [
            cpp_modules_feature,
            macos_minimum_os_feature,
            macos_default_link_flags_feature,
            dependency_file_feature,
            libtool_feature,
            archiver_flags_feature,
            asan_feature,
            tsan_feature,
            ubsan_feature,
            gcc_quoting_for_param_files_feature,
            static_link_cpp_runtimes_feature,
        ] + (
            [
                supports_start_end_lib_feature,
            ] if ctx.attr.supports_start_end_lib else []
        ) + [
            coverage_feature,
            default_compile_flags_feature,
            default_link_flags_feature,
            user_link_flags_feature,
            default_link_libs_feature,
            external_include_paths_feature,
            fdo_optimize_feature,
            dbg_feature,
            opt_feature,
            user_compile_flags_feature,
            sysroot_feature,
            compiler_input_flags_feature,
            compiler_output_flags_feature,
            unfiltered_compile_flags_feature,
            treat_warnings_as_errors_feature,
            archive_param_file_feature,
            generate_linkmap_feature,
        ] + layering_check_features(ctx.attr.compiler, ctx.attr.extra_flags_per_feature, is_macos = True)

    parse_headers_action_configs, parse_headers_features = parse_headers_support(
        parse_headers_tool_path = ctx.attr.tool_paths.get("parse_headers"),
    )
    action_configs += parse_headers_action_configs
    features += parse_headers_features

    return cc_common.create_cc_toolchain_config_info(
        ctx = ctx,
        features = features,
        action_configs = action_configs,
        artifact_name_patterns = artifact_name_patterns,
        cxx_builtin_include_directories = ctx.attr.cxx_builtin_include_directories,
        toolchain_identifier = ctx.attr.toolchain_identifier,
        host_system_name = ctx.attr.host_system_name,
        target_system_name = ctx.attr.target_system_name,
        target_cpu = ctx.attr.cpu,
        target_libc = ctx.attr.target_libc,
        compiler = ctx.attr.compiler,
        abi_version = ctx.attr.abi_version,
        abi_libc_version = ctx.attr.abi_libc_version,
        tool_paths = tool_paths,
        builtin_sysroot = ctx.attr.builtin_sysroot,
    )

cc_toolchain_config = rule(
    implementation = _impl,
    attrs = {
        "cpu": attr.string(mandatory = True),
        "compiler": attr.string(mandatory = True),
        "toolchain_identifier": attr.string(mandatory = True),
        "host_system_name": attr.string(mandatory = True),
        "target_system_name": attr.string(mandatory = True),
        "target_libc": attr.string(mandatory = True),
        "abi_version": attr.string(mandatory = True),
        "abi_libc_version": attr.string(mandatory = True),
        "cxx_builtin_include_directories": attr.string_list(),
        "tool_paths": attr.string_dict(),
        "compile_flags": attr.string_list(),
        "dbg_compile_flags": attr.string_list(),
        "opt_compile_flags": attr.string_list(),
        "conly_flags": attr.string_list(),
        "cxx_flags": attr.string_list(),
        "link_flags": attr.string_list(),
        "archive_flags": attr.string_list(),
        "link_libs": attr.string_list(),
        "opt_link_flags": attr.string_list(),
        "unfiltered_compile_flags": attr.string_list(),
        "coverage_compile_flags": attr.string_list(),
        "coverage_link_flags": attr.string_list(),
        "supports_start_end_lib": attr.bool(),
        "builtin_sysroot": attr.string(),
        "extra_flags_per_feature": attr.string_list_dict(),
        "_xcode_config": attr.label(default = configuration_field(
            fragment = "apple",
            name = "xcode_config_label",
        )),
    },
    fragments = ["apple"],
    provides = [CcToolchainConfigInfo],
)
