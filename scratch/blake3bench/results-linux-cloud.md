# Linux cloud BLAKE3 benchmark results

## Environment
- Arch: **x86_64** (not aarch64). Date: 2026-11-11. Branch blake3-perf @ 09e8399.
- CPU: Intel(R) Xeon(R) Processor @ 2.10GHz (family 6 model 207, Sapphire Rapids-class), KVM guest, 4 vCPU (1 thread/core), L2 8 MiB, L3 260 MiB
- avx2: yes; avx512f: yes; avx512vl: yes (also avx512bw/dq/vbmi2, amx)
- uname: Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC x86_64 (Firecracker-style microVM)
- gcc 13.3.0 (Ubuntu 13.3.0-6ubuntu2~24.04.1); clang 18.1.3 (Ubuntu 1ubuntu1); nproc 4; RAM 15 GiB, no swap
- /tmp: not a separate mount (findmnt printed nothing); lives on root /dev/vda (real virtual disk, not tmpfs)
- uptime at start: load 0.07 (fresh VM, up 0 min). At end: up 13 min, load avg 0.97/1.41/1.16 (mostly my own bazel build)
- Bazel: bazelisk (latest release) -> Bazel 9.3.2 per .bazelversion
- Noise: shared KVM guest on a large host (260 MiB L3 shared); vCPU frequency/steal not observable. Treat as potentially noisy.

## Part 1: native C benchmarks: NOT RUN
The BLAKE3 1.8.2 / 1.8.7 sources were fetched (git clone --depth 1 --branch <tag>, because the tarball URLs were 403 via the proxy), but the single command that compiled and ran bench.c / filebench.c was denied by the Claude Code auto-mode permission classifier ("Code from External"). Per its instructions I did not work around it. No C numbers exist in this file. Re-run with permission to build/run the downloaded sources (test files were already created: f100 f4096 f65536 f1048576 f67108864 on /dev/vda).

## Part 2: JMH (Blake3PerfBenchmark via bazel run, JSON in /tmp/linux-jmh.json)
Build succeeded first try, no fixes needed. Run was single, 5 measurement iterations (Cnt=5), mode avgt, ns/op.

```
Benchmark                            (impl)    (size)  Mode  Cnt         Score         Error  Units
Blake3PerfBenchmark.fileDigest       JNI_8K      4096  avgt    5      4037.401 ±     304.136  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K     65536  avgt    5     25253.928 ±    4337.982  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K   1048576  avgt    5    358679.716 ±   44910.410  ns/op
Blake3PerfBenchmark.fileDigest       JNI_8K  67108864  avgt    5  30538374.913 ± 2260362.512  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K      4096  avgt    5      4137.329 ±     233.542  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K     65536  avgt    5     17784.056 ±    2860.393  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K   1048576  avgt    5    257780.457 ±   36608.691  ns/op
Blake3PerfBenchmark.fileDigest      JNI_64K  67108864  avgt    5  22172027.735 ± 3030182.508  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K      4096  avgt    5      3569.711 ±     561.889  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K     65536  avgt    5     24192.878 ±    3343.533  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K   1048576  avgt    5    351915.483 ±   55387.766  ns/op
Blake3PerfBenchmark.fileDigest       FFM_8K  67108864  avgt    5  31198444.104 ± 2225639.900  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K      4096  avgt    5      3547.427 ±     444.227  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K     65536  avgt    5     16719.906 ±     372.437  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K   1048576  avgt    5    252390.376 ±   35145.731  ns/op
Blake3PerfBenchmark.fileDigest      FFM_64K  67108864  avgt    5  23130211.653 ± 1300073.206  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K      4096  avgt    5      2804.791 ±     751.263  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K     65536  avgt    5     14996.009 ±    1700.831  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K   1048576  avgt    5    220303.829 ±   33733.630  ns/op
Blake3PerfBenchmark.fileDigest   NATIVE_64K  67108864  avgt    5  21683362.879 ± 3477489.900  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K      4096  avgt    5      5017.441 ±     311.847  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K     65536  avgt    5     49233.160 ±    8189.089  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K   1048576  avgt    5    761959.822 ±   48928.223  ns/op
Blake3PerfBenchmark.fileDigest    SHA256_8K  67108864  avgt    5  56818906.533 ± 2983992.158  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K      4096  avgt    5      5203.316 ±     734.769  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K     65536  avgt    5     50236.737 ±    3229.046  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K   1048576  avgt    5    777432.352 ±  113352.258  ns/op
Blake3PerfBenchmark.fileDigest   SHA256_64K  67108864  avgt    5  56631898.750 ± 4852147.644  ns/op
Blake3PerfBenchmark.hashBytes           JNI         1  avgt    5       647.356 ±      54.474  ns/op
Blake3PerfBenchmark.hashBytes           JNI        64  avgt    5       676.170 ±      38.565  ns/op
Blake3PerfBenchmark.hashBytes           JNI      1024  avgt    5      1517.147 ±     165.420  ns/op
Blake3PerfBenchmark.hashBytes           JNI      8192  avgt    5      2455.317 ±     226.946  ns/op
Blake3PerfBenchmark.hashBytes           JNI     65536  avgt    5     11148.520 ±     669.520  ns/op
Blake3PerfBenchmark.hashBytes           JNI   1048576  avgt    5    163353.832 ±   18724.290  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT         1  avgt    5       158.346 ±      12.917  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT        64  avgt    5       157.295 ±      11.385  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT      1024  avgt    5       999.813 ±      63.373  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT      8192  avgt    5      2110.424 ±     191.984  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT     65536  avgt    5     10671.506 ±    1782.983  ns/op
Blake3PerfBenchmark.hashBytes   JNI_ONESHOT   1048576  avgt    5    173366.936 ±   29953.876  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM         1  avgt    5       236.314 ±      24.833  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM        64  avgt    5       236.560 ±      22.422  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM      1024  avgt    5      1147.893 ±     172.759  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM      8192  avgt    5      2182.019 ±     316.654  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM     65536  avgt    5     11280.411 ±    1148.845  ns/op
Blake3PerfBenchmark.hashBytes    FFM_STREAM   1048576  avgt    5    169172.109 ±   20652.021  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT         1  avgt    5       109.641 ±      12.502  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT        64  avgt    5       107.560 ±      12.364  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT      1024  avgt    5      1000.459 ±      62.104  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT      8192  avgt    5      2027.276 ±     217.627  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT     65536  avgt    5     10569.124 ±    1378.689  ns/op
Blake3PerfBenchmark.hashBytes   FFM_ONESHOT   1048576  avgt    5    163849.106 ±   12165.451  ns/op
Blake3PerfBenchmark.hashBytes        SHA256         1  avgt    5       118.035 ±      13.650  ns/op
Blake3PerfBenchmark.hashBytes        SHA256        64  avgt    5       148.690 ±      10.204  ns/op
Blake3PerfBenchmark.hashBytes        SHA256      1024  avgt    5       785.519 ±     105.386  ns/op
Blake3PerfBenchmark.hashBytes        SHA256      8192  avgt    5      5557.391 ±     605.436  ns/op
Blake3PerfBenchmark.hashBytes        SHA256     65536  avgt    5     45494.029 ±    4458.005  ns/op
Blake3PerfBenchmark.hashBytes        SHA256   1048576  avgt    5    702869.519 ±   61044.542  ns/op
```

## Notes / anomalies
- Error bars (99.9% CI) are mostly 5-15%, some up to ~20-25% (e.g. JNI_ONESHOT 1M +-17%, JNI_8K 64K +-17%, NATIVE_64K 4096 +-27%, JNI_64K 64M +-14%). Only 5 iterations, so treat small differences (<10-15%) as noise.
- hashBytes at 1 MiB: JNI 163 us, JNI_ONESHOT 173 us, FFM_STREAM 169 us, FFM_ONESHOT 164 us: all within error of each other (about 6.4 GB/s); SHA256 703 us. For small inputs (1, 64 B) oneshot variants win clearly: JNI 647-676 ns vs JNI_ONESHOT ~158, FFM_ONESHOT ~108, FFM_STREAM ~236.
- fileDigest: 64K buffer beats 8K by ~30% at >=64 KiB files for both JNI and FFM; NATIVE_64K is fastest at every size; BLAKE3 is ~2.3-2.6x faster than SHA-256 for 64M files.
- No failures or throttling were visible, but not measurable in the VM; fileDigest on 64M files is likely mostly page-cache (15 GiB RAM, files fit).
