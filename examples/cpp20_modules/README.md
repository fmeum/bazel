# C++20 modules with `map_directory()`

A prototype of C++20 modules support built on `ctx.actions.map_directory()`,
`template_ctx.read()` and the private `cc_internal.look_up_module_interface()`.
See `third_party/bazel_rules/rules_cc/modules/cc_modules.bzl` for how it works.

The rules call the host's Clang directly and require Clang 16 or newer with
`clang-scan-deps` on the `PATH`.

`lib` contains three modules: `greeting`, which imports `greeting.strings`, and
`greeting.slow`, which nothing in the demo imports. The compiler wrapper makes
compiling `greeting.slow` take 10 seconds. `app/main.cpp` imports `greeting`.

To build and run the demo in both modes and compare when `app/main.cpp` starts
compiling:

```sh
bazel build //src:bazel-dev
examples/cpp20_modules/run_demo.sh bazel-bin/src/bazel-dev
```

This directory is a separate workspace and is listed in Bazel's `.bazelignore`.
