#!/bin/bash
# Builds and runs the demo twice with a Bazel binary built from this branch:
#   1. with the native module lookup (BMIs of dependencies are referenced individually), and
#   2. with --define=modules_barrier=1 (BMI directories of dependencies are input directories).
# In both cases, compiling lib/slow.cppm takes SLOW_SECONDS (default 10) seconds, but app/main.cpp
# doesn't import it. The script prints when the compilation of app/main.cpp started relative to
# the end of the compilation of lib/slow.cppm.
#
# Usage: run_demo.sh <bazel binary> [<output base>]
set -euo pipefail

bazel_bin="$(realpath "${1:?usage: $0 <bazel binary> [<output base>]}")"
output_base="${2:-${TMPDIR:-/tmp}/cpp20_modules_demo}"
cd "$(dirname "$0")"

function bazel() {
  "$bazel_bin" --output_base="$output_base" "$@"
}

function build_and_report() {
  local mode="$1"
  shift
  local profile="$output_base/$mode.profile.json"
  bazel clean
  echo "=== $mode: bazel build //app:main $*"
  bazel build //app:main --profile="$profile" --noslim_profile "$@"
  echo "--- output of //app:main:"
  "$(bazel cquery --output=files //app:main "$@" 2>/dev/null)"
  python3 - "$profile" <<'PY'
import json, sys
events = [e for e in json.load(open(sys.argv[1]))["traceEvents"]
          if e.get("ph") == "X" and "dur" in e]
def find(name):
    matches = [e for e in events if e.get("name") == name]
    if not matches:
        sys.exit("no profile event named %r" % name)
    return min(matches, key=lambda e: e["ts"])
slow = find("Precompiling lib/slow.cppm")
main = find("Compiling app/main.cpp")
link = find("Linking app/main")
start = min(e["ts"] for e in events)
for label, e in [("precompile lib/slow.cppm", slow), ("compile app/main.cpp", main), ("link app/main", link)]:
    print("%-26s %6.2fs - %6.2fs" % (label, (e["ts"] - start) / 1e6, (e["ts"] + e["dur"] - start) / 1e6))
delta = (main["ts"] - (slow["ts"] + slow["dur"])) / 1e6
print("app/main.cpp started %.2fs %s lib/slow.cppm was done"
      % (abs(delta), "before" if delta < 0 else "after"))
PY
}

build_and_report lookup
build_and_report barrier --define=modules_barrier=1
