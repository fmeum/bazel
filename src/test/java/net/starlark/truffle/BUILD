# Description: Tests for Truffle-based Starlark interpreter

load("@rules_java//java:defs.bzl", "java_test")

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_test(
    name = "TruffleStarlarkTest",
    size = "small",
    srcs = ["TruffleStarlarkTest.java"],
    deps = [
        "//src/main/java/net/starlark/truffle",
        "//third_party:junit4",
        "//third_party:polyglot",
        "//third_party:truffle_api",
    ],
)

java_test(
    name = "DirectTruffleStarlarkTest",
    size = "small",
    srcs = ["DirectTruffleStarlarkTest.java"],
    deps = [
        "//src/main/java/net/starlark/truffle",
        "//third_party:junit4",
        "//third_party:truffle_api",
    ],
)
