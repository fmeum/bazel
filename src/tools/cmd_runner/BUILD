load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__pkg__"],
)

# Executes scripts created by the `cmd` Starlark module (ctx.actions.run_script)
# without requiring a shell or coreutils on the execution platform.
cc_binary(
    name = "cmd_runner",
    srcs = [
        "cmd_runner.cc",
        "platform.h",
    ] + select({
        "@platforms//os:windows": ["platform_windows.cc"],
        "//conditions:default": ["platform_posix.cc"],
    }),
    copts = select({
        "@platforms//os:windows": ["/std:c++17"],
        "//conditions:default": ["-std=c++17"],
    }),
    linkopts = select({
        "@platforms//os:windows": [],
        "//conditions:default": ["-lpthread"],
    }),
)
