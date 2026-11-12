# BazelHashFunctionsBenchmark on Linux, #32593 (BLAKE3 via FFM), heap state

Revisions: base = b9b31a8603a (master + hashBytesChunked case), pr = 7a46622a984 (origin/blake3-ffm-oneshot).
Command: `bazel run //src/test/java/com/google/devtools/build/lib/vfs/bazel:BazelHashFunctionsBenchmark -- -p type=BLAKE3 -p size=B,KB,MB -f 2 -wi 3 -w 1s -i 5 -r 1s`
Run order: base-1, pr-1, pr-2, base-2. Builds were done beforehand; nothing else heavy ran during timed runs.

## Environment
- CPU: Intel Xeon (family 6, model 207, Sapphire Rapids class) @ 2.10GHz, KVM guest, 4 vCPUs (1 socket x 4 cores, 1 thread/core)
- Flags include avx2, avx512f/dq/bw/vl, avx512_vnni, amx, sha_ni
- L1d 192 KiB (4x), L2 8 MiB (4x), L3 260 MiB
- nproc: 4; memory: 15 GiB total, no swap
- Kernel: Linux 6.18.44-fc-v378 x86_64 (PREEMPT_DYNAMIC)
- JDK: OpenJDK 21.0.12.1; Bazel via bazelisk, version 9.3.2 (.bazelversion)
- uptime at start: up 0 min, load 0.07 (fresh VM)

## Run base-1
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score       Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1422056.947 ± 59715.113  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   601671.597 ± 28177.729  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3182.696 ±   171.641  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  1471818.628 ± 64027.722  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   612433.976 ± 22044.616  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     4839.599 ±   536.481  ops/s

```

## Run pr-1
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score        Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1467524.926 ±  82593.225  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   697471.253 ±  62094.832  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3089.603 ±    236.296  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  3364704.772 ± 214329.406  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   656034.894 ±  68474.303  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     4935.320 ±    541.368  ops/s

```

## Run pr-2
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score        Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1513833.537 ±  93044.129  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   695219.336 ±  59245.640  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3188.863 ±    164.757  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  3268330.229 ± 138017.438  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   677419.274 ±  29110.106  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     4950.320 ±    312.353  ops/s

```

## Run base-2
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score       Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1465270.881 ± 60964.477  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   590880.227 ± 29919.416  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     2897.234 ±   396.929  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  1440036.617 ± 67431.114  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   579126.101 ± 36163.031  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     4948.100 ±   975.858  ops/s

```

## Notes
- All four runs completed; no failures or exceptions in the logs.
- Units are ops/s (higher is better). hashBytesOneShot is the path changed by the PR; hashBytesChunked serves as a control.
- hashBytesOneShot B: base 1.47M / 1.44M vs pr 3.36M / 3.27M ops/s (~2.3x), reproducible across both orderings.
- hashBytesOneShot KB: base 612K / 579K vs pr 656K / 677K (~+10 to 15%); error bars are 4 to 10%, so modest but consistent.
- hashBytesOneShot MB: base 4840 / 4948 vs pr 4935 / 4950: no difference. Error bars are large (base-1 +-11%, base-2 +-20%, pr-1 +-11%, pr-2 +-6%).
- hashBytesChunked (control) MB varied between 2897 and 3189 across runs (base-2 error +-14%), so MB-sized differences under ~10% are noise on this shared VM.
- hashBytesChunked B/KB is flat between base and pr except KB, where pr (697K/695K) is ~15% above base (602K/591K); this is unexpected for a control and may reflect JIT/layout differences.
