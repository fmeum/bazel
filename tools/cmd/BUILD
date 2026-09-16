package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        "cmd_toolchain.bzl",
        "defs.bzl",
    ],
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]),
    visibility = [
        "//src/test/java/com/google/devtools/build/lib/analysis/mock:__pkg__",
        "//tools:__pkg__",
    ],
)

exports_files(["defs.bzl"])
