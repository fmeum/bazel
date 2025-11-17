# Description: Truffle-based Starlark interpreter for high performance

load("@rules_java//java:defs.bzl", "java_library")

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/net/starlark/java:__pkg__"],
)

# Truffle-based Starlark implementation
java_library(
    name = "truffle",
    srcs = glob([
        "*.java",
        "nodes/*.java",
        "nodes/**/*.java",
        "values/*.java",
        "parser/*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//third_party:truffle",
    ],
)
