#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
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
#
# Tests the `stdout` parameter of `ctx.actions.run`, which redirects an
# action's standard output into a declared output file instead of reporting it
# as regular action stdout.

# --- begin runfiles.bash initialization ---
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function set_up() {
  mkdir -p pkg

  cat > pkg/defs.bzl <<'EOF'
def _capture_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".out")
    ctx.actions.run(
        outputs = [],
        executable = ctx.executable._tool,
        arguments = [ctx.attr.text],
        stdout = out,
        mnemonic = "Capture",
    )
    return [DefaultInfo(files = depset([out]))]

capture = rule(
    implementation = _capture_impl,
    attrs = {
        "text": attr.string(mandatory = True),
        "_tool": attr.label(
            default = ":echo_tool",
            executable = True,
            cfg = "exec",
        ),
    },
)

def _consume_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".copy")
    ctx.actions.run_shell(
        inputs = ctx.files.src,
        outputs = [out],
        command = "cp \"$1\" \"$2\"",
        arguments = [ctx.files.src[0].path, out.path],
        mnemonic = "Consume",
    )
    return [DefaultInfo(files = depset([out]))]

consume = rule(
    implementation = _consume_impl,
    attrs = {"src": attr.label(allow_files = True, mandatory = True)},
)
EOF

  cat > pkg/echo_tool.sh <<'EOF'
#!/bin/bash
# Writes its argument to stdout (which the action redirects into a file).
printf '%s' "$1"
EOF
  chmod +x pkg/echo_tool.sh

  cat > pkg/BUILD <<'EOF'
load("@rules_shell//shell:sh_binary.bzl", "sh_binary")
load(":defs.bzl", "capture", "consume")

sh_binary(
    name = "echo_tool",
    srcs = ["echo_tool.sh"],
)

capture(
    name = "captured",
    text = "hello-from-stdout",
)

consume(
    name = "consumed",
    src = ":captured",
)
EOF
  add_rules_shell "MODULE.bazel"
}

function test_stdout_captured_into_file() {
  bazel build //pkg:captured >&"$TEST_log" || fail "build failed"
  local out
  out=$(find "$(bazel info bazel-bin)/pkg" -name "captured.out")
  [[ -n "$out" ]] || fail "captured.out was not produced"
  assert_equals "hello-from-stdout" "$(cat "$out")"
}

function test_stdout_not_printed_to_terminal() {
  # The captured stdout must not be echoed to the terminal as regular action
  # output, even when it would normally be shown.
  bazel build //pkg:captured >&"$TEST_log" || fail "build failed"
  expect_not_log "hello-from-stdout"
}

function test_stdout_output_consumed_by_downstream_action() {
  bazel build //pkg:consumed >&"$TEST_log" || fail "build failed"
  local out
  out=$(find "$(bazel info bazel-bin)/pkg" -name "consumed.copy")
  [[ -n "$out" ]] || fail "consumed.copy was not produced"
  assert_equals "hello-from-stdout" "$(cat "$out")"
}

run_suite "ctx.actions.run stdout parameter tests"
