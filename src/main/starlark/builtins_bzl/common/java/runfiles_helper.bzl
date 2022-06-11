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

load(":common/rule_util.bzl", "create_dep")
load(":common/java/java_semantics.bzl", "semantics")

java_common = _builtins.toplevel.java_common

RunfilesLibraryInfo = _builtins.toplevel.RunfilesLibraryInfo

_RUNFILES_HELPER_TEMPLATE = """package com.google.devtools.build.runfiles;

public final class RunfilesHelper {
  public static final String CURRENT_REPO_NAME = "%s";
  private RunfilesHelper() {}
}
"""

def depends_on_runfiles_library(deps):
    for dep in deps:
        if RunfilesLibraryInfo in dep:
            return True
    return False

def runfiles_helper_action(ctx):
    basename = "_runfiles_helper/%s_runfiles_helper" % ctx.attr.name

    java_file = ctx.actions.declare_file(basename + ".java")
    ctx.actions.write(java_file, _RUNFILES_HELPER_TEMPLATE % ctx.label.workspace_name)

    jar_file = ctx.actions.declare_file(basename + ".jar")
    return java_common.compile(
        ctx,
        java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],
        # The JLS (§13.1) guarantees that constants are inlined. Since the generated code only
        # contains constants, we can remove it from the runtime classpath.
        neverlink = True,
        output = jar_file,
        source_files = [java_file],
    )

RUNFILES_HELPER_ACTION = create_dep(
    runfiles_helper_action,
    attrs = {
        "_java_toolchain": attr.label(
            default = semantics.JAVA_TOOLCHAIN_LABEL,
            providers = [java_common.JavaToolchainInfo],
        ),
    },
)

RUNFILES_HELPER_ACTION_IMPLICIT_ATTRS = {
    "_java_toolchain": attr.label(
        default = semantics.JAVA_TOOLCHAIN_LABEL,
        providers = [java_common.JavaToolchainInfo],
    ),
}
