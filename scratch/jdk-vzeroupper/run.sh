#!/usr/bin/env bash
# Builds and runs the reproducer. Usage: run.sh /path/to/jdk-25/bin/java
set -euo pipefail
cd "$(dirname "$0")"
java=${1:-java}
work=$(mktemp -d)
cc -O2 -fPIC -shared work.c -o "$work/libwork.so"
echo "Native code (sse_work must not contain VEX-encoded instructions):"
objdump -d --no-show-raw-insn "$work/libwork.so" | awk '/<sse_work>:/,/ret/' | head -60
"$java" -version 2>&1
for flags in "" "-XX:UseAVX=2" "-XX:MaxVectorSize=16" "-XX:UseAVX=0"; do
  echo
  echo "== java ${flags:-(default flags)}"
  # shellcheck disable=SC2086
  "$java" --enable-native-access=ALL-UNNAMED -Dlib="$work/libwork.so" $flags Repro.java
done
