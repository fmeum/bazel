#!/usr/bin/env bash
# Real-build benchmark for file digest changes.
#
# Usage: bench_real.sh BIN_DIR WORKLOAD_DIR OUT_DIR JAVA ROUNDS [TARGET]
#
# BIN_DIR must contain bazel-base, bazel-64k and worker_deploy.jar. For each
# digest function (SHA256, BLAKE3), a local remote cache (Bazel's remote worker)
# is populated once. Then, for ROUNDS rounds and for both binaries (in
# alternating order), three builds of TARGET in WORKLOAD_DIR are measured, each
# on a freshly started server:
#   A_top:  bazel clean + build, all actions are remote cache hits, default
#           (toplevel) output downloads.
#   A_all:  same with --remote_download_all.
#   B_null: null build after a server restart with all outputs on disk.
# Results are appended to OUT_DIR/results.csv. Set DIGESTS to limit the digest
# functions, e.g. DIGESTS=SHA256.
set -euo pipefail

BIN_DIR=$(cd "$1" && pwd)
WORKLOAD=$(cd "$2" && pwd)
mkdir -p "$3"
OUT=$(cd "$3" && pwd)
JAVA=$4
ROUNDS=$5
TARGET=${6:-//src:bazel-dev}
DIGESTS=${DIGESTS:-SHA256 BLAKE3}

mkdir -p "$OUT/work" "$OUT/bep" "$OUT/logs"
CSV=$OUT/results.csv
if [ ! -f "$CSV" ]; then
  echo "round,bin,digest,scenario,client_s,wall_ms,cpu_ms,analysis_ms,exec_ms,actions_executed,remote_cache_hits" > "$CSV"
fi

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

port_for() {
  case $1 in
    SHA256) echo 18980 ;;
    BLAKE3) echo 18981 ;;
  esac
}

WORKER_PIDS=""
cleanup() {
  for pid in $WORKER_PIDS; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT

start_worker() {
  local df=$1
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dbazel.DigestFunction="$df" \
    -jar "$BIN_DIR/worker_deploy.jar" \
    --listen_port="$(port_for "$df")" --cas_path="$OUT/work/cas-$df" \
    > "$OUT/logs/worker-$df.log" 2>&1 &
  WORKER_PIDS="$WORKER_PIDS $!"
  sleep 3
}

bz() {
  local bin=$1 df=$2
  shift 2
  (cd "$WORKLOAD" && "$BIN_DIR/bazel-$bin" --nohome_rc --nosystem_rc \
    --output_base="$OUT/work/ob-$bin-$df" --digest_function="$df" "$@")
}

build_flags() {
  local df=$1
  echo "--remote_cache=grpc://localhost:$(port_for "$df") --noshow_progress --color=no --curses=no"
}

record() {
  local round=$1 bin=$2 df=$3 scenario=$4 client_s=$5 bep=$6
  python3 - "$round" "$bin" "$df" "$scenario" "$client_s" "$bep" >> "$CSV" <<'EOF'
import json, sys
round_, bin_, df, scenario, client_s, bep = sys.argv[1:]
metrics = {}
for line in open(bep):
    event = json.loads(line)
    if "buildMetrics" in event:
        metrics = event["buildMetrics"]
timing = metrics.get("timingMetrics", {})
actions = metrics.get("actionSummary", {})
hits = sum(int(r.get("count", 0)) for r in actions.get("runnerCount", [])
           if r.get("name") == "remote cache hit")
print(",".join(str(x) for x in [
    round_, bin_, df, scenario, client_s,
    timing.get("wallTimeInMs", ""), timing.get("cpuTimeInMs", ""),
    timing.get("analysisPhaseTimeInMs", ""), timing.get("executionPhaseTimeInMs", ""),
    actions.get("actionsExecuted", ""), hits]))
EOF
}

measure() {
  local round=$1 bin=$2 df=$3 scenario=$4
  shift 4
  local bep="$OUT/bep/$round-$bin-$df-$scenario.json"
  local t0 t1
  t0=$(now)
  bz "$bin" "$df" build "$TARGET" $(build_flags "$df") "$@" \
    --build_event_json_file="$bep" > "$OUT/logs/$round-$bin-$df-$scenario.log" 2>&1
  t1=$(now)
  record "$round" "$bin" "$df" "$scenario" "$(python3 -c "print(f'{$t1 - $t0:.3f}')")" "$bep"
  tail -1 "$CSV"
}

for df in $DIGESTS; do
  start_worker "$df"
  # Populate the cache and fetch external repositories for both binaries.
  for bin in base 64k; do
    echo "== warmup $bin $df"
    measure warmup "$bin" "$df" A_all --remote_download_all
    measure warmup "$bin" "$df" B_null --remote_download_all
    bz "$bin" "$df" shutdown
  done
done

for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) -eq 1 ]; then order="base 64k"; else order="64k base"; fi
  for df in $DIGESTS; do
    for bin in $order; do
      echo "== round $round $bin $df"
      bz "$bin" "$df" shutdown
      bz "$bin" "$df" clean > /dev/null 2>&1
      measure "$round" "$bin" "$df" A_top
      bz "$bin" "$df" shutdown
      bz "$bin" "$df" clean > /dev/null 2>&1
      measure "$round" "$bin" "$df" A_all --remote_download_all
      bz "$bin" "$df" shutdown
      measure "$round" "$bin" "$df" B_null --remote_download_all
      bz "$bin" "$df" shutdown
    done
  done
done

for df in $DIGESTS; do
  for bin in base 64k; do
    bz "$bin" "$df" shutdown > /dev/null 2>&1 || true
  done
done
echo "done: $CSV"
