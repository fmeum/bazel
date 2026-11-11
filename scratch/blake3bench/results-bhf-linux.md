# BazelHashFunctionsBenchmark (BLAKE3) on Linux x86, PR #32593

base = b9b31a8603a (master + hashBytesChunked case), pr = eefdbd95adb (origin/blake3-ffm-oneshot). Order: base-1, pr-1, pr-2, base-2.
Command: `-p type=BLAKE3 -p size=B,KB,MB -f 2 -wi 3 -w 1s -i 5 -r 1s`, bazel 9.3.2 via bazelisk.

## Environment
```
Architecture:                            x86_64
CPU(s):                                  4
Model name:                              Intel(R) Xeon(R) Processor @ 2.10GHz
Thread(s) per core:                      1
Core(s) per socket:                      4
Socket(s):                               1
Hypervisor vendor:                       KVM
L2 cache:                                8 MiB (4 instances)
L3 cache:                                260 MiB (1 instance)
Flags (relevant): amx_bf16 amx_int8 amx_tile avx2 avx512_bf16 avx512_bitalg avx512_fp16 avx512_vbmi2 avx512_vnni avx512_vpopcntdq avx512bw avx512cd avx512dq avx512f avx512ifma avx512vbmi avx512vl sha_ni sse4_1 
nproc: 4
               total        used        free      shared  buff/cache   available
Mem:              15           2          10           0           3          13
Swap:              0           0           0
Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC @0 x86_64 x86_64 x86_64 GNU/Linux
uptime at start: 13:23:21 up 0 min, load average: 0.08, 0.02, 0.01
```

## base-1
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score        Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1477961.285 ±  34694.695  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   597320.988 ±  11203.794  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3133.033 ±    153.676  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  1398959.317 ± 140539.741  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   605925.740 ±  21581.859  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     5355.453 ±    255.699  ops/s
```

## pr-1
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score       Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1666992.719 ± 56292.694  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   621899.896 ± 17553.939  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3211.904 ±   140.548  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  3465795.470 ± 74993.525  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   687332.774 ± 25929.557  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     5159.808 ±   299.193  ops/s
```

## pr-2
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score        Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1656472.579 ± 114685.805  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   626578.108 ±  14613.659  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3220.539 ±    152.589  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  3387979.242 ± 167489.130  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   695084.545 ±  18242.050  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     5281.167 ±    193.079  ops/s
```

## base-2
```
Benchmark                                     (size)  (type)   Mode  Cnt        Score       Error  Units
BazelHashFunctionsBenchmark.hashBytesChunked       B  BLAKE3  thrpt   10  1478137.662 ± 30091.155  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      KB  BLAKE3  thrpt   10   616787.966 ± 16074.807  ops/s
BazelHashFunctionsBenchmark.hashBytesChunked      MB  BLAKE3  thrpt   10     3148.320 ±   134.320  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot       B  BLAKE3  thrpt   10  1463792.316 ± 87903.264  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      KB  BLAKE3  thrpt   10   598005.095 ± 24249.715  ops/s
BazelHashFunctionsBenchmark.hashBytesOneShot      MB  BLAKE3  thrpt   10     5344.652 ±   304.518  ops/s
```

## Notes
- All four runs and builds succeeded (rc=0); no failures.
- Error bars (99.9% CI) are mostly 2-5%; larger for B: base-1 OneShot B ±10%, base-2 OneShot B ±6%, pr-2 Chunked B ±7%, pr-2 OneShot B ±5%.
- Run-to-run: base-1 vs base-2 OneShot B 1.40M vs 1.46M ops/s (within error); base Chunked KB 597k vs 617k.
- Observed (means of two runs): OneShot B ~1.43M -> ~3.43M ops/s (~2.4x); OneShot KB ~602k -> ~691k (~+15%); OneShot MB ~5.35k -> ~5.22k (~-2.5%, within error); Chunked B ~1.48M -> ~1.66M (~+12%); Chunked KB ~607k -> ~624k (+3%); Chunked MB ~3.14k -> ~3.22k (+2.5%).
- 4 vCPU KVM guest (shared cloud VM), so noise from neighbours is possible; nothing else heavy ran during benchmarks.
