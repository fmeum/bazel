# Real-build digest benchmark, Linux (PR #32594: 64 KiB vs 8 KiB digest reads)

Driver: `bench_real.sh` (branch blake3-perf), 2 rounds, target `//src:bazel-dev`.
Base = 813095b17e6, PR = c2fd0585478. Workload checkout = 813095b17e6.

**SHA256 only.** Only 25 GB of disk was free before the run (< 45 GB), so
`DIGESTS=SHA256` was used. BLAKE3 was NOT measured.

## Environment
- Intel Xeon (Sapphire Rapids, family 6 model 207) @ 2.10 GHz, KVM guest, 4 vCPUs (1 thread/core), AVX2/AVX-512, sha_ni
- 15 GiB RAM, no swap; 252 GB disk (/dev/vda, root and /tmp same fs), 25 GB free at start of run
- Linux 6.18.44-fc-v378 x86_64; Bazel 9.3.2 via bazelisk; JDK Zulu 25.0.4 (remotejdk25)
- Run time: 12:29:01 -> ~12:50 UTC on 2026-11-11 (~21 min incl. warmup). Building the two binaries beforehand took ~16 min (base) + ~10 s (64k, incremental).

## Mean of 2 rounds (wall_ms, SHA256)
| scenario | base | 64k | diff |
|---|---|---|---|
| A_top | 22308 | 22688 | +1.7% |
| A_all | 31388 | 32379 | +3.2% |
| B_null | 16978 | 16779 | -1.2% |

## Notes / anomalies
- No failures. All measured rounds: actions_executed=5789 with remote_cache_hits=4806 for A_top/A_all (the remaining 983 are non-remote internal actions, identical for both binaries and in warmup), so no unexpected cache misses. B_null: 1 action executed, 0 hits, as expected.
- The warmup base A_all (887 s) was the one real full build populating the cache; the 64k warmup was then fully cached.
- Differences between base and 64k are within round-to-round noise: base itself varied 21.7 -> 22.9 s (A_top) and 29.8 -> 33.0 s (A_all) between rounds 1 and 2; round 2 was slower for both binaries. Only n=2 per cell, so no significant effect of the 64 KiB buffer is detectable on this machine for SHA256 (sha_ni makes hashing cheap).
- Order alternated (round 1 base first, round 2 64k first); round 2 was uniformly ~3-5% slower, a possible drift/ordering effect.
