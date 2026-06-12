#!/usr/bin/env bash
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
# A testbed for --rewind_lost_inputs: builds a target against the example
# remote worker while the worker simulates losing CAS entries, then verifies
# that the build succeeded and that lost inputs were recovered via action
# rewinding. See README.md in this directory for details.

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/rewinding-testbed/run.sh [options] [-- <extra args for the inner bazel build>]

Options:
  --target=<label>            Target(s) to build (default: //src:bazel-dev).
  --bazel=<path|auto|host>    Bazel client to test: 'auto' builds and uses
                              //src:bazel-dev from HEAD (default), 'host' uses
                              bazel from PATH, or pass an explicit binary path.
  --lost_blob_percentage=<n>  Percentage of CAS blobs the worker loses after
                              their first upload (default: 2).
  --lost_blob_seed=<seed>     Seed selecting which blobs are lost. Vary it to
                              lose a different sample of blobs (default: the
                              current time, printed for reproducibility).
  --lost_blob_max_losses=<n>  How many times each affected blob is lost, i.e.
                              how often clients must recover from the loss of
                              the same blob (default: 1).
  --port=<port>               Port for the worker (default: a random free port).
  --base_dir=<path>           Directory for worker state, output base, and logs
                              (default: a fresh temporary directory). Reusing a
                              base dir results in an incremental build.
  --allow_no_rewinds          Do not fail if the build succeeded without any
                              action rewinding (e.g. for tiny targets or very
                              low loss percentages).
  --keep                      Keep the base dir even on success.
  -h, --help                  Show this help.
EOF
}

TARGET="//src:bazel-dev"
BAZEL=auto
LOST_BLOB_PERCENTAGE=2
LOST_BLOB_SEED="$(date +%s)"
LOST_BLOB_MAX_LOSSES=1
PORT=
BASE_DIR=
ALLOW_NO_REWINDS=0
KEEP=0
EXTRA_BUILD_ARGS=()

while (($# > 0)); do
  case "$1" in
    --target=*) TARGET="${1#*=}" ;;
    --bazel=*) BAZEL="${1#*=}" ;;
    --lost_blob_percentage=*) LOST_BLOB_PERCENTAGE="${1#*=}" ;;
    --lost_blob_seed=*) LOST_BLOB_SEED="${1#*=}" ;;
    --lost_blob_max_losses=*) LOST_BLOB_MAX_LOSSES="${1#*=}" ;;
    --port=*) PORT="${1#*=}" ;;
    --base_dir=*) BASE_DIR="${1#*=}" ;;
    --allow_no_rewinds) ALLOW_NO_REWINDS=1 ;;
    --keep) KEEP=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      EXTRA_BUILD_ARGS=("$@")
      break
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

WORKSPACE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$WORKSPACE_DIR"

if [[ -z "$BASE_DIR" ]]; then
  BASE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rewinding-testbed.XXXXXX")"
fi
mkdir -p "$BASE_DIR/worker/work" "$BASE_DIR/worker/cas"
WORKER_LOG="$BASE_DIR/worker.log"
BUILD_LOG="$BASE_DIR/build.log"
PROFILE="$BASE_DIR/profile.gz"
PID_FILE="$BASE_DIR/worker.pid"

echo ">>> Base dir: $BASE_DIR"
echo ">>> Lost blob percentage: $LOST_BLOB_PERCENTAGE, seed: $LOST_BLOB_SEED," \
  "max losses: $LOST_BLOB_MAX_LOSSES"

WORKER_PID=
cleanup() {
  local exit_code=$?
  if [[ -n "$WORKER_PID" ]]; then
    kill "$WORKER_PID" 2>/dev/null || true
  fi
  if ((exit_code == 0 && KEEP == 0)); then
    # Some external repos (e.g. hermetic toolchains) write-protect their files.
    chmod -R +w "$BASE_DIR" 2>/dev/null || true
    rm -rf "$BASE_DIR"
  else
    echo ">>> Logs and state kept in $BASE_DIR"
  fi
  exit $exit_code
}
trap cleanup EXIT

echo ">>> Building the example remote worker..."
bazel build //src/tools/remote:worker
bazel run --script_path="$BASE_DIR/run_worker.sh" //src/tools/remote:worker

if [[ "$BAZEL" == auto ]]; then
  echo ">>> Building the Bazel client under test (//src:bazel-dev)..."
  bazel build //src:bazel-dev
  BAZEL="$WORKSPACE_DIR/bazel-bin/src/bazel-dev"
elif [[ "$BAZEL" == host ]]; then
  BAZEL="$(command -v bazel)"
fi
echo ">>> Bazel client under test: $BAZEL"

if [[ -z "$PORT" ]]; then
  for _ in $(seq 1 10); do
    PORT=$((20000 + RANDOM % 40000))
    nc -z localhost "$PORT" 2>/dev/null || break
    PORT=
  done
  [[ -n "$PORT" ]] || {
    echo ">>> Could not find a free port" >&2
    exit 1
  }
elif nc -z localhost "$PORT" 2>/dev/null; then
  echo ">>> Port $PORT is already in use" >&2
  exit 1
fi

echo ">>> Starting the worker on port $PORT..."
rm -f "$PID_FILE"
"$BASE_DIR/run_worker.sh" \
  --work_path="$BASE_DIR/worker/work" \
  --cas_path="$BASE_DIR/worker/cas" \
  --listen_port="$PORT" \
  --pid_file="$PID_FILE" \
  --lost_blob_percentage="$LOST_BLOB_PERCENTAGE" \
  --lost_blob_seed="$LOST_BLOB_SEED" \
  --lost_blob_max_losses="$LOST_BLOB_MAX_LOSSES" \
  >"$WORKER_LOG" 2>&1 &
WORKER_PID=$!

# The pid file is written only once the worker is fully started, so waiting for
# it (in addition to the port) guarantees that the port is served by our worker
# rather than a stale process.
for _ in $(seq 1 120); do
  if ! kill -0 "$WORKER_PID" 2>/dev/null; then
    echo ">>> Worker failed to start:" >&2
    cat "$WORKER_LOG" >&2
    exit 1
  fi
  if [[ -f "$PID_FILE" ]] && nc -z localhost "$PORT" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
[[ -f "$PID_FILE" ]] && nc -z localhost "$PORT" 2>/dev/null || {
  echo ">>> Worker did not come up on port $PORT" >&2
  exit 1
}

echo ">>> Building $TARGET against the lossy worker..."
build_exit=0
"$BAZEL" \
  --output_base="$BASE_DIR/output_base" \
  build \
  "$TARGET" \
  --remote_executor="grpc://localhost:$PORT" \
  --remote_download_minimal \
  --rewind_lost_inputs \
  --experimental_remote_cache_eviction_retries=0 \
  --disk_cache= \
  --profile="$PROFILE" \
  --noslim_profile \
  ${EXTRA_BUILD_ARGS[@]+"${EXTRA_BUILD_ARGS[@]}"} \
  2>&1 | tee "$BUILD_LOG" || build_exit=$?

# Shut down the inner server so the output base can be reused or deleted.
"$BAZEL" --output_base="$BASE_DIR/output_base" shutdown 2>/dev/null || true

lost_blobs=$(grep -c "Simulated loss of CAS entry" "$WORKER_LOG" || true)
lost_bytes=$(grep -o "Simulated loss of CAS entry [a-f0-9]*/[0-9]*" "$WORKER_LOG" \
  | awk -F/ '{sum += $2} END {printf "%.1f", sum / 1024 / 1024}')
rewound=0
lost_inputs=0
max_lost_inputs=0
if [[ -f "$PROFILE" ]]; then
  read -r rewound lost_inputs max_lost_inputs < <(
    gunzip -c "$PROFILE" \
      | grep -o "Preparing rewind plan for [0-9]* lost" \
      | awk '{n++; sum += $5; if ($5 > max) max = $5} END {print n + 0, sum + 0, max + 0}'
  )
fi

echo
echo ">>> Testbed summary"
echo ">>>   Build exit code:             $build_exit"
echo ">>>   Lost CAS entries:            $lost_blobs ($lost_bytes MB)"
echo ">>>   Rewind plans prepared:       $rewound"
echo ">>>   Lost inputs across plans:    $lost_inputs (max per plan: $max_lost_inputs)"

if ((build_exit != 0)); then
  echo ">>> FAIL: build failed despite --rewind_lost_inputs; check $BUILD_LOG and $WORKER_LOG" >&2
  exit 1
fi
if ((lost_blobs == 0)); then
  echo ">>> FAIL: the worker did not lose any blobs; increase --lost_blob_percentage or build a bigger target" >&2
  exit 1
fi
if ((rewound == 0 && ALLOW_NO_REWINDS == 0)); then
  echo ">>> FAIL: no action rewinding occurred. All lost blobs were recovered by client-side" >&2
  echo ">>>       re-uploads. Increase --lost_blob_percentage, build a bigger target, or pass" >&2
  echo ">>>       --allow_no_rewinds if this is expected." >&2
  exit 1
fi
echo ">>> PASS: the build recovered from $lost_blobs lost CAS entries ($rewound actions rewound)"
