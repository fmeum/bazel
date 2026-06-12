# Rewinding testbed

This testbed exercises Bazel's recovery from lost inputs via action rewinding
(`--rewind_lost_inputs`) against a remote execution backend that actively loses
CAS entries, the way real-world remote caches do (e.g. due to evictions or
outages).

It builds a target (by default, Bazel itself) with remote execution and
`--remote_download_minimal` against the [example remote
worker](../../src/tools/remote) running in a mode in which it deletes a
deterministic sample of blobs from its CAS right after their first upload
(`--lost_blob_percentage`/`--lost_blob_seed`). Intermediate action outputs that
are lost this way exist neither locally (due to build without the bytes) nor
remotely, so when a downstream action needs them as inputs, Bazel can only
recover by rewinding the generating action and re-executing it. The worker only
ever loses a given blob once, so recovery is always possible and the build must
converge.

## Usage

From the workspace root:

```sh
scripts/rewinding-testbed/run.sh
```

This builds the example worker and a fresh Bazel client from HEAD
(`//src:bazel-dev`), starts the worker with a 2% blob loss rate, builds
`//src:bazel-dev` against it in a separate output base, and then verifies that:

1. the build succeeded,
2. the worker actually lost blobs, and
3. at least one action was rewound (visible in the JSON profile as "Preparing
   rewind plan" events).

The run fails if losses occurred but the build did not succeed, making any
regression in lost input handling (in rewinding itself, but also in client-side
re-upload logic and remote cache consistency handling) immediately visible.

Useful knobs (see `run.sh --help` for the full list):

```sh
# Stress harder and with a fixed, reproducible loss sample. At high loss
# percentages, also raise the per-action retry budget: every transient remote
# failure of an action (missing inputs, lost stdout/stderr) draws from the same
# --remote_retries budget, which can otherwise run out before the build
# converges.
scripts/rewinding-testbed/run.sh --lost_blob_percentage=10 --lost_blob_seed=my-seed \
    -- --remote_retries=10

# Lose each affected blob multiple times so that clients have to recover from
# the loss of the same blob repeatedly (e.g. by rewinding the same action
# several times). Bazel tolerates up to 20 repeated losses of the same input
# per action (ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS), so values well
# below that are expected to converge.
scripts/rewinding-testbed/run.sh --lost_blob_max_losses=3 -- --remote_retries=10

# Use a smaller target for a quick check:
scripts/rewinding-testbed/run.sh --target=//src/main/cpp:client

# Test the host Bazel (or any other binary) instead of HEAD:
scripts/rewinding-testbed/run.sh --bazel=host
scripts/rewinding-testbed/run.sh --bazel=/path/to/bazel

# Exercise rewinding on an incremental build by reusing the base dir with
# different seeds (each new seed loses a fresh sample of blobs):
scripts/rewinding-testbed/run.sh --base_dir=/tmp/testbed --keep --lost_blob_seed=1
scripts/rewinding-testbed/run.sh --base_dir=/tmp/testbed --keep --lost_blob_seed=2

# Pass extra flags to the inner build:
scripts/rewinding-testbed/run.sh -- --jobs=4 --keep_going
```

On failure (and with `--keep`), the worker log, the build log, and the JSON
profile are preserved in the base dir, which is printed at the start of the
run. The worker log contains one `Simulated loss of CAS entry <digest>` line
per lost blob; correlating these digests with the build log and the gRPC log
(`--experimental_remote_grpc_log`, passed after `--`) makes it easy to debug
unrecovered losses.

## How blob loss is simulated

See the "Simulating lost CAS entries" section in the [example worker
README](../../src/tools/remote/README.md). In short: whether a blob is lost is
a deterministic function of its digest and the seed, the loss is an actual
deletion from the on-disk CAS (so every RPC observes it consistently), and only
a bounded number of uploads of a blob is affected (`--lost_blob_max_losses`,
1 by default), so uploading it sufficiently often revives it.
