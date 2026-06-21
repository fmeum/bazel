# Description: Bazel's Starlark interpreter

load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_java//java:defs.bzl", "java_library")

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/net/starlark/java:__pkg__"],
)

# The Starlark evaluator
java_library(
    name = "eval",
    srcs = [
        "BuiltinFunction.java",
        "CallUtils.java",
        "CompactImmutableDict.java",
        "Compactable.java",
        "CpuProfiler.java",
        "Debug.java",
        "Dict.java",
        "Eval.java",
        "EvalException.java",
        "EvalUtils.java",
        "FlagGuardedValue.java",
        "FormatParser.java",
        "GuardedValue.java",
        "HasBinary.java",
        "ImmutableSingletonStarlarkList.java",
        "ImmutableStarlarkList.java",
        "JNI.java",
        "LazyImmutableStarlarkList.java",
        "MethodDescriptor.java",
        "MethodLibrary.java",
        "Module.java",
        "Mutability.java",
        "MutableStarlarkList.java",
        "NoneType.java",
        "ParamDescriptor.java",
        "Printer.java",
        "RangeList.java",
        "RegularImmutableStarlarkList.java",
        "RegularTuple.java",
        "Sequence.java",
        "SingletonTuple.java",
        "Starlark.java",
        "StarlarkCallable.java",
        "StarlarkEngine.java",
        "StarlarkFloat.java",
        "StarlarkFunction.java",
        "StarlarkIndexable.java",
        "StarlarkInt.java",
        "StarlarkIterable.java",
        "StarlarkList.java",
        "StarlarkMembershipTestable.java",
        "StarlarkSemantics.java",
        "StarlarkSet.java",
        "StarlarkThread.java",
        "StarlarkTypeValue.java",
        "StarlarkValue.java",
        "StringModule.java",
        "Structure.java",
        "SymbolGenerator.java",
        "Tuple.java",
        "TypeChecker.java",
    ],
    visibility = ["//src/main/java/net/starlark/java:clients"],
    deps = [
        # Do not add Bazel or Google dependencies here!
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/spelling",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:error_prone_annotations",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        ":cpu_profiler_native_support",
    ],
)

# Experimental Truffle-based execution engine for --experimental_starlark_engine=truffle.
# Kept separate from :eval so the core interpreter stays free of the Truffle dependency; it lives in
# the same Java package to reuse package-private internals (Frame, EvalUtils.binaryOp, ...).
java_library(
    name = "truffle_engine",
    srcs = ["TruffleStarlarkEngine.java"],
    visibility = ["//src/main/java/net/starlark/java:clients"],
    deps = [
        ":eval",
        "//src/main/java/net/starlark/java/syntax",
        "@maven//:org_graalvm_truffle_truffle_api",
    ],
    runtime_deps = [
        # Provides the optimizing runtime; only JIT-compiles guest code on a Graal/Truffle-capable
        # JVM, otherwise runs interpreted (which is fine for correctness).
        "@maven//:org_graalvm_truffle_truffle_runtime",
    ],
)

java_library(
    name = "cpu_profiler_native_support",
    srcs = ["CpuProfilerNativeSupport.java"],
    visibility = ["//src/main/java/net/starlark/java:clients"],
)

java_library(
    name = "cpu_profiler_native_support_impl",
    srcs = [
        "CpuProfilerNativeSupportImpl.java",
        "JNI.java",
    ],
    visibility = ["//src/main/java/net/starlark/java:clients"],
    deps = [":cpu_profiler_native_support"],
)

# Dynamic library for Starlark CPU profiler.
# The application or test must arrange for the library to be added
# to the java.library.path directory (e.g. by 'java -Djava.library.path=<dir>').
# What a mess.
filegroup(
    name = "cpu_profiler_jni",
    srcs = select({
        "//src/conditions:darwin": [":libcpu_profiler.dylib"],
        "//src/conditions:linux": [":libcpu_profiler.so"],
        "//conditions:default": [],
    }),
    visibility = ["//src/main/java/net/starlark/java:clients"],
)

genrule(
    name = "cpu_profiler_jni_darwin",
    srcs = ["libcpu_profiler.so"],
    outs = ["libcpu_profiler.dylib"],
    cmd = "cp $< $@",
)

# The C++ portion of the Starlark CPU profiler.
cc_binary(
    name = "libcpu_profiler.so",
    srcs = select({
        "//src/conditions:darwin": ["cpu_profiler_posix.cc"],
        "//src/conditions:linux": ["cpu_profiler_posix.cc"],
        "//conditions:default": [],
    }),
    linkshared = 1,
    deps = ["@rules_java//toolchains:jni"],
)
