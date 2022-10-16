package(default_visibility = ["//visibility:private"])

load(":runfiles_test.bzl", "runfiles_tests")

runfiles_tests()

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
        exclude = [
            ".*",  # .swp files and such
            "runfiles.bzl",
            "runfiles_test.bzl",
        ],
    ),
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = ["BUILD.tools"],
    visibility = ["//tools:__pkg__"],
)
