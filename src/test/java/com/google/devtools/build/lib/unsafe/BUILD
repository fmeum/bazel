load("@rules_java//java:defs.bzl", "java_binary", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src/test/java/com/google/devtools/build/lib:test_lib"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/test/java/com/google/devtools/build/lib:__pkg__"],
)

java_test(
    name = "StringUnsafeTest",
    srcs = ["StringUnsafeTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unsafe:string",
        "//third_party/java/junit",
        "//third_party/java/truth",
    ],
)

# Run via
#   bazel run //src/test/java/com/google/devtools/build/lib/unsafe:IsAsciiBenchmark
java_binary(
    name = "IsAsciiBenchmark",
    srcs = ["IsAsciiBenchmark.java"],
    javacopts = ["--add-modules=jdk.incubator.vector"],
    jvm_flags = ["--add-modules=jdk.incubator.vector"],
    main_class = "org.openjdk.jmh.Main",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unsafe:string",
        "//third_party:jmh",
    ],
)
