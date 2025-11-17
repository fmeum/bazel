# Description: Tests for Truffle-based Starlark interpreter

load("@rules_java//java:defs.bzl", "java_library", "java_test")

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
        "//third_party:truffle",
    ],
)

java_test(
    name = "DirectTruffleStarlarkTest",
    size = "small",
    srcs = ["DirectTruffleStarlarkTest.java"],
    deps = [
        "//src/main/java/net/starlark/truffle",
        "//third_party:junit4",
        "//third_party:truffle",
    ],
)

# Script-based tests reusing testdata from the original implementation
java_library(
    name = "TruffleScriptTest_lib",
    srcs = ["TruffleScriptTest.java"],
    data = [
        "testdata/phase1.star",
        "testdata/phase2.star",
        "testdata/phase3.star",
        "//src/test/java/net/starlark/java/eval:testdata/assign.star",
        # TODO: Add more test files as features are implemented
    ],
    deps = [
        "//src/main/java/net/starlark/truffle",
        "//third_party:guava",
        "//third_party:polyglot",
        "//third_party:truffle",
    ],
)

java_test(
    name = "TruffleScriptTest",
    main_class = "net.starlark.truffle.TruffleScriptTest",
    use_testrunner = False,
    runtime_deps = [":TruffleScriptTest_lib"],
)
