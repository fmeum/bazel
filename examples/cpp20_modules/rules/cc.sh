#!/bin/bash
# Non-hermetic wrapper around the host's Clang (16 or newer) for this demo.
set -euo pipefail
# Actions don't inherit PATH, but Clang needs one to find the linker.
export PATH="${PATH:-/usr/local/bin:/usr/bin:/bin}"
CXX="${CXX:-clang++}"
SCAN_DEPS="${CLANG_SCAN_DEPS:-}"
if [[ -z "$SCAN_DEPS" ]]; then
  for candidate in clang-scan-deps clang-scan-deps-{22..16}; do
    if command -v "$candidate" > /dev/null; then
      SCAN_DEPS="$candidate"
      break
    fi
  done
fi

mode="$1"
shift
case "$mode" in
  scan)
    # scan <output dir> <manifest> <source>...: writes src_<i>.ddi (P1689) for the i-th source and
    # a JSON list of all of them to the manifest.
    out="$1"
    manifest="$2"
    shift 2
    i=0
    for src in "$@"; do
      "$SCAN_DEPS" -format=p1689 -- "$CXX" -std=c++20 -c "$src" -o "src_$i.o" > "$out/src_$i.ddi"
      i=$((i + 1))
    done
    {
      echo "["
      for ((j = 0; j < i; j++)); do
        [[ $j -gt 0 ]] && echo ","
        cat "$out/src_$j.ddi"
      done
      echo "]"
    } > "$manifest"
    ;;
  compile)
    # Simulates an expensive module that nothing in the demo imports.
    for arg in "$@"; do
      if [[ "$arg" == *slow.cppm ]]; then
        sleep "${SLOW_SECONDS:-10}"
      fi
    done
    exec "$CXX" "$@"
    ;;
  *)
    echo "unknown mode $mode" >&2
    exit 1
    ;;
esac
