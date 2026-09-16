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
# End-to-end tests for the `cmd` module, `ctx.actions.run_script` and the
# `script` rule.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# `uname` returns the current platform, e.g "MSYS_NT-10.0" or "Linux".
# `tr` converts all upper case letters to lower case.
# `case` matches the result if the `uname | tr` expression to string prefixes
# that use the same wildcards as names do in Bash, i.e. "msys*" matches strings
# starting with "msys", and "*" matches everything (it's the default case).
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*)
  # As of 2019-01-15, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

function set_up() {
  add_to_bazelrc "build --experimental_starlark_cmd"
  setup_module_dot_bazel
}

function write_todos_rule() {
  mkdir -p rules
  cat > rules/todos.bzl <<'EOF2'
def _todos_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.run_script(
        cmd.cat(ctx.files.srcs) | cmd.grep("TODO") | cmd.sort(unique = True) > out,
        mnemonic = "CollectTodos",
        progress_message = "Collecting TODOs for %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

todos = rule(
    implementation = _todos_impl,
    attrs = {"srcs": attr.label_list(allow_files = True)},
    toolchains = [cmd.toolchain_type],
)
EOF2
  touch rules/BUILD
}

function test_pipeline_with_builtins() {
  write_todos_rule
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF2'
load("//rules:todos.bzl", "todos")

todos(
    name = "todos",
    srcs = ["a.cc", "b.cc"],
)
EOF2
  cat > pkg/a.cc <<'EOF2'
int main() {} // TODO: return something
// TODO: add tests
EOF2
  cat > pkg/b.cc <<'EOF2'
// TODO: add tests
// nothing to do here
EOF2

  bazel build //pkg:todos &> "$TEST_log" || fail "build failed"
  cat > expected <<'EOF2'
// TODO: add tests
int main() {} // TODO: return something
EOF2
  diff expected bazel-bin/pkg/todos.txt || fail "unexpected output"
}

function test_script_rule_with_tool_and_substitution() {
  add_rules_shell "MODULE.bazel"
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF2'
load("@bazel_tools//tools/cmd:defs.bzl", "script")
load("@rules_shell//shell:sh_binary.bzl", "sh_binary")

sh_binary(
    name = "gen",
    srcs = ["gen.sh"],
    data = ["template.txt"],
)

script(
    name = "generated",
    srcs = ["input.txt"],
    outs = [
        "out/generated.txt",
        "log.txt",
    ],
    tools = [":gen"],
    cmd = [
        cmd.mkdir(cmd.dirname("out/generated.txt")),
        (cmd(":gen", cmd.dirname("out/generated.txt"), "input.txt", cmd.wc("input.txt", lines = True))
            .env(GREETING = "hello") > "log.txt"),
        cmd.echo("done", cmd.cat("log.txt") | cmd.head(lines = 1)) >> "log.txt",
    ],
)
EOF2
  cat > pkg/gen.sh <<'EOF2'
#!/usr/bin/env bash
set -euo pipefail
out_dir="$1"
input="$2"
lines="$3"
# The tool's runfiles are available.
template="$(dirname "$0")/gen.runfiles/_main/pkg/template.txt"
if [[ ! -f "$template" ]]; then
  template="$(dirname "$0")/../pkg/template.txt"
fi
echo "$GREETING: $(cat "$template") $(cat "$input") ($lines lines)" > "$out_dir/generated.txt"
echo "generated $out_dir/generated.txt"
EOF2
  chmod +x pkg/gen.sh
  echo "world" > pkg/template.txt
  echo "input" > pkg/input.txt

  bazel build //pkg:generated &> "$TEST_log" || fail "build failed"
  assert_equals "hello: world input (1 pkg/input.txt lines)" "$(cat bazel-bin/pkg/out/generated.txt)"
  cat > expected <<'EOF2'
generated bazel-out/REPLACED/bin/pkg/out/generated.txt
done generated bazel-out/REPLACED/bin/pkg/out/generated.txt
EOF2
  sed -E 's#bazel-out/[^/]+/bin#bazel-out/REPLACED/bin#' bazel-bin/pkg/log.txt > actual
  diff expected actual || fail "unexpected log"
}

function test_failure_is_reported_and_stops_script() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF2'
load("@bazel_tools//tools/cmd:defs.bzl", "script")

script(
    name = "failing",
    outs = ["out.txt"],
    cmd = [
        cmd.echo("first") > "out.txt",
        cmd.fail("giving up on", "out.txt"),
        cmd.echo("unreachable") >> "out.txt",
    ],
)
EOF2

  bazel build //pkg:failing &> "$TEST_log" && fail "build should have failed"
  expect_log "giving up on bazel-out/.*/bin/pkg/out.txt"
  expect_not_log "unreachable"
}

function test_external_program_failure_and_stderr_redirect() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF2'
load("@bazel_tools//tools/cmd:defs.bzl", "script")

script(
    name = "cp_and_replace",
    srcs = ["in.txt"],
    outs = ["copy.txt", "replaced.txt", "listing.txt"],
    cmd = [
        cmd.cp("in.txt", "copy.txt"),
        cmd.replace("l+", "L", "copy.txt", regex = True) | cmd.tee("replaced.txt") > "listing.txt",
        cmd.ls(cmd.dirname("copy.txt")) | cmd.grep("^(copy|replaced).txt$") >> "listing.txt",
    ],
)

script(
    name = "broken_tool",
    outs = ["never.txt"],
    cmd = cmd("this-program-does-not-exist", "--flag") > "never.txt",
)
EOF2
  echo "hello world" > pkg/in.txt

  bazel build //pkg:cp_and_replace &> "$TEST_log" || fail "build failed"
  assert_equals "hello world" "$(cat bazel-bin/pkg/copy.txt)"
  assert_equals "heLo worLd" "$(cat bazel-bin/pkg/replaced.txt)"
  cat > expected <<'EOF2'
heLo worLd
copy.txt
replaced.txt
EOF2
  diff expected bazel-bin/pkg/listing.txt || fail "unexpected listing"

  bazel build //pkg:broken_tool &> "$TEST_log" && fail "build should have failed"
  expect_log "cannot execute this-program-does-not-exist"
}

function test_cwd_and_directory_outputs() {
  mkdir -p pkg
  cat > pkg/defs.bzl <<'EOF2'
def _tree_impl(ctx):
    out_dir = ctx.actions.declare_directory(ctx.label.name)
    manifest = ctx.actions.declare_file(ctx.label.name + ".manifest")
    ctx.actions.run_script(
        [
            cmd.mkdir(out_dir.path + "/sub"),
            cmd.echo("a") > out_dir.path + "/sub/a.txt",
            cmd.cp(ctx.file.src, out_dir),
            # Relative file arguments are made absolute when running in a directory.
            cmd("cat", ctx.file.src).cwd(out_dir) > out_dir.path + "/from_cwd.txt",
            cmd.ls(out_dir, recursive = True) > manifest,
        ],
        mnemonic = "Tree",
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset([out_dir, manifest]))]

tree = rule(
    implementation = _tree_impl,
    attrs = {"src": attr.label(allow_single_file = True)},
    toolchains = ["@bazel_tools//tools/cmd:toolchain_type"],
)
EOF2
  cat > pkg/BUILD <<'EOF2'
load(":defs.bzl", "tree")

tree(
    name = "tree",
    src = "src.txt",
)
EOF2
  echo "source" > pkg/src.txt

  bazel build //pkg:tree &> "$TEST_log" || fail "build failed"
  cat > expected <<'EOF2'
from_cwd.txt
src.txt
sub/a.txt
EOF2
  diff expected bazel-bin/pkg/tree.manifest || fail "unexpected manifest"
  assert_equals "source" "$(cat bazel-bin/pkg/tree/from_cwd.txt)"
}

function test_requires_flag() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF2'
load("@bazel_tools//tools/cmd:defs.bzl", "script")

script(
    name = "x",
    outs = ["x.txt"],
    cmd = cmd.echo("x") > "x.txt",
)
EOF2

  bazel build --noexperimental_starlark_cmd //pkg:x &> "$TEST_log" && fail "build should have failed"
  expect_log "experimental_starlark_cmd"
}

run_suite "cmd script tests"
