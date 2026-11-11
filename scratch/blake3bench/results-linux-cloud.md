# Linux cloud blake3 benchmark results

## Environment
- CPU: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 vCPUs (1 thread/core), x86_64; flags: avx2, avx512f, avx512vl all present
- Kernel: Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC x86_64 (Firecracker-style VM, so shared host)
- gcc 13.3.0 (Ubuntu 24.04); clang 18.1.3
- nproc 4; RAM 15 GiB total
- /tmp is NOT tmpfs: it is on the root disk (/dev/vda, ext4-like)
- uptime at start: up 0 min, load 0.15 (fresh VM)
- Looks like a shared/virtualized cloud VM, so noise is likely. Several JMH error bars are large (see below).
- Branch blake3-perf @ 09e8399; Bazel 9.3.2 via bazelisk. The JMH build took ~324 s.

## Part 1: native C benchmarks — NOT RUN
The BLAKE3 1.8.2 / 1.8.7 tarball download via curl was blocked by the session proxy (HTTP 403).
I fetched both tags with a shallow git clone instead (works). Compiling and running the
downloaded C code was then denied by the Claude Code auto-mode classifier ("Code from
External"), so no bench.c / filebench.c numbers exist. Sources are at /root/w/s182 and
/root/w/s187 if someone wants to rerun with permission.

## Part 2: JMH (Blake3PerfBenchmark, ns/op, avgt, Cnt=5)
```
Benchmark                            (impl)    (size)  Mode  Cnt         Score          Error  Units
Blake3PerfBenchmark.fileDigest       JNI_8K      4096  avgt    5      4033.299 ±     1387.830  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K     65536  avgt    5     25190.955 ±     3204.324  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K   1048576  avgt    5    384025.873 ±    81745.106  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K  67108864  avgt    5  34695732.581 ±  7555094.602  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K      4096  avgt    5      3963.659 ±      294.095  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K     65536  avgt    5     18082.243 ±     5592.876  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K   1048576  avgt    5    293002.889 ±   206461.756  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K  67108864  avgt    5  27140430.565 ±  5714704.496  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K      4096  avgt    5      4231.514 ±     5336.684  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K     65536  avgt    5     25009.994 ±     3034.281  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K   1048576  avgt    5    372122.725 ±   116729.686  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K  67108864  avgt    5  32891311.459 ±  5893218.014  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K      4096  avgt    5      3790.223 ±     1255.381  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K     65536  avgt    5     22569.702 ±     7862.706  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K   1048576  avgt    5    291239.293 ±   150431.173  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K  67108864  avgt    5  28663456.471 ± 13231071.784  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K      4096  avgt    5      2622.252 ±      437.861  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K     65536  avgt    5     15310.740 ±     5680.171  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K   1048576  avgt    5    255448.309 ±   152471.239  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K  67108864  avgt    5  25136460.866 ±  7730272.939  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K      4096  avgt    5      5315.744 ±     1444.208  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K     65536  avgt    5     51785.540 ±     4887.474  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K   1048576  avgt    5    848737.358 ±   201414.322  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K  67108864  avgt    5  58270889.109 ±  2800543.060  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K      4096  avgt    5      5069.577 ±      559.626  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K     65536  avgt    5     48996.207 ±     1941.954  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K   1048576  avgt    5    820400.197 ±    54461.723  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K  67108864  avgt    5  57267925.033 ±  4253363.380  ns/op
Blake3PerfBenchmark.hashBytes           JNI         1  avgt    5       666.491 ±      114.295  ns/op
Blake3PerfBenchmark.hashBytes           JNI        64  avgt    5       656.884 ±       56.995  ns/op
Blake3PerfBenchmark.hashBytes           JNI      1024  avgt    5      1575.673 ±      110.603  ns/op
Blake3PerfBenchmark.hashBytes           JNI      8192  avgt    5      2581.098 ±      448.902  ns/op
Blake3PerfBenchmark.hashBytes           JNI     65536  avgt    5     11498.967 ±     3967.551  ns/op
Blake3PerfBenchmark.hashBytes           JNI   1048576  avgt    5    162620.953 ±    33560.823  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT         1  avgt    5       156.714 ±       13.107  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT        64  avgt    5       165.904 ±       35.373  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT      1024  avgt    5      1064.272 ±      194.726  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT      8192  avgt    5      2332.326 ±     1116.358  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT     65536  avgt    5     10883.465 ±     1674.330  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT   1048576  avgt    5    186078.095 ±    77111.738  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM         1  avgt    5       231.302 ±       39.258  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM        64  avgt    5       238.208 ±       16.907  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM      1024  avgt    5      1142.918 ±      124.565  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM      8192  avgt    5      2158.188 ±      313.373  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM     65536  avgt    5     11177.515 ±     1417.356  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM   1048576  avgt    5    169824.604 ±    28505.541  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT         1  avgt    5       107.706 ±        5.068  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT        64  avgt    5       111.794 ±       11.581  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT      1024  avgt    5       969.128 ±       82.479  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT      8192  avgt    5      1930.034 ±      126.260  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT     65536  avgt    5     10793.184 ±     2312.736  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT   1048576  avgt    5    166196.179 ±    27291.951  ns/op
Blake3PerfBenchmark.hashBytes        SHA256         1  avgt    5       128.705 ±        9.556  ns/op
Blake3PerfBenchmark.hashBytes        SHA256        64  avgt    5       152.843 ±       21.252  ns/op
Blake3PerfBenchmark.hashBytes        SHA256      1024  avgt    5       800.424 ±       68.971  ns/op
Blake3PerfBenchmark.hashBytes        SHA256      8192  avgt    5      5497.757 ±      356.866  ns/op
Blake3PerfBenchmark.hashBytes        SHA256     65536  avgt    5     42854.122 ±     1730.098  ns/op
Blake3PerfBenchmark.hashBytes        SHA256   1048576  avgt    5    682403.298 ±    84230.852  ns/op
```

## Notes
- Many large error bars (e.g. fileDigest FFM_64K 64 MiB: 28.7 ms ± 13.2 ms; JNI_64K 1 MiB ± 70%); consistent with a noisy shared VM, so treat small differences as inconclusive.
- fileDigest runs on /tmp on the root disk; files are likely page-cached, so mostly measures syscall/hash cost.
- No build or run failures in Part 2.
