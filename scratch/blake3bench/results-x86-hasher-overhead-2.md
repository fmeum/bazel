# BLAKE3 x86 hasher overhead, experiment 2 (AVX state and huge pages)

Commit under test: e1e553c4dc4 (detached). Code not modified. Date: 2026-11-12.

## Environment
```
Architecture:                            x86_64
CPU op-mode(s):                          32-bit, 64-bit
Address sizes:                           52 bits physical, 57 bits virtual
Byte Order:                              Little Endian
CPU(s):                                  4
On-line CPU(s) list:                     0-3
Vendor ID:                               GenuineIntel
Model name:                              Intel(R) Xeon(R) Processor @ 2.10GHz
CPU family:                              6
Model:                                   207
Thread(s) per core:                      1
Core(s) per socket:                      4
Socket(s):                               1
Stepping:                                2
BogoMIPS:                                4200.00
Hypervisor vendor:                       KVM
Virtualization type:                     full
Flags (relevant): avx avx2 avx512f avx512dq avx512bw avx512vl avx512_fp16 amx_tile (full list in lscpu)
4
Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC @0 x86_64 x86_64 x86_64 GNU/Linux
always [madvise] never
always defer defer+madvise [madvise] never
```

THP: enabled=[madvise], defrag=[madvise] (last two lines above).

## objdump check: functions containing vzeroupper
```
      1 <_blake3_hash_many_avx2>:
      1 <_blake3_hash_many_avx512>:
      1 <_blake3_xof_many_avx512>:
      2 <dirty_ymm0_vzeroupper>:
      2 <dirty_zmm0_vzeroupper>:
      2 <dirty_zmm16_vzeroupper>:
      3 <main>:
```
The dirty_ymm0 / dirty_zmm0 / dirty_zmm16 preludes without _vzeroupper in their name contain no vzeroupper. The _vzeroupper variants each contain 2 (the 3 in main are its own).

## C1: blake3_avx_penalty (run 1, verbatim)
```
clean                      round 1:   106.5 ns/op
clean                      round 2:   106.6 ns/op
dirty ymm0                 round 1:   240.6 ns/op
dirty ymm0                 round 2:   281.0 ns/op
dirty zmm0                 round 1:   262.4 ns/op
dirty zmm0                 round 2:   258.9 ns/op
dirty zmm16                round 1:   259.9 ns/op
dirty zmm16                round 2:   252.1 ns/op
dirty ymm0 + vzeroupper    round 1:   104.8 ns/op
dirty ymm0 + vzeroupper    round 2:   102.9 ns/op
dirty zmm0 + vzeroupper    round 1:   122.7 ns/op
dirty zmm0 + vzeroupper    round 2:   122.7 ns/op
dirty zmm16 + vzeroupper   round 1:   110.4 ns/op
dirty zmm16 + vzeroupper   round 2:   112.6 ns/op
clean (again)              round 1:   113.9 ns/op
clean (again)              round 2:   116.3 ns/op
```

## C2: blake3_avx_penalty (run 2, verbatim)
```
clean                      round 1:    99.3 ns/op
clean                      round 2:    98.5 ns/op
dirty ymm0                 round 1:   220.6 ns/op
dirty ymm0                 round 2:   217.9 ns/op
dirty zmm0                 round 1:   250.0 ns/op
dirty zmm0                 round 2:   252.4 ns/op
dirty zmm16                round 1:   248.6 ns/op
dirty zmm16                round 2:   254.0 ns/op
dirty ymm0 + vzeroupper    round 1:   100.8 ns/op
dirty ymm0 + vzeroupper    round 2:    99.5 ns/op
dirty zmm0 + vzeroupper    round 1:   115.4 ns/op
dirty zmm0 + vzeroupper    round 2:   114.5 ns/op
dirty zmm16 + vzeroupper   round 1:   102.9 ns/op
dirty zmm16 + vzeroupper   round 2:   104.3 ns/op
clean (again)              round 1:    99.5 ns/op
clean (again)              round 2:   103.8 ns/op
```

## E0 (no extra options)
```
Benchmark                                     Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.hasher                avgt   10  626.004 ± 31.459  ns/op
Blake3OverheadBenchmark.hasherRaw             avgt   10  576.540 ± 44.095  ns/op
Blake3OverheadBenchmark.hasherRawVzeroupper   avgt   10  496.008 ± 36.815  ns/op
Blake3OverheadBenchmark.oneShot               avgt   10  309.844 ± 90.351  ns/op
Blake3OverheadBenchmark.oneShotRaw            avgt   10  259.909 ± 20.242  ns/op
Blake3OverheadBenchmark.oneShotRawVzeroupper  avgt   10  110.442 ±  9.566  ns/op
```

## E1 (-XX:MaxVectorSize=32)
```
Benchmark                                     Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.hasher                avgt   10  622.304 ± 75.007  ns/op
Blake3OverheadBenchmark.hasherRaw             avgt   10  566.256 ± 38.766  ns/op
Blake3OverheadBenchmark.hasherRawVzeroupper   avgt   10  493.398 ± 44.908  ns/op
Blake3OverheadBenchmark.oneShot               avgt   10  255.336 ± 18.641  ns/op
Blake3OverheadBenchmark.oneShotRaw            avgt   10  245.430 ± 21.013  ns/op
Blake3OverheadBenchmark.oneShotRawVzeroupper  avgt   10  109.954 ±  3.110  ns/op
```

## E2 (-XX:MaxVectorSize=16)
```
Benchmark                                     Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.hasher                avgt   10  541.043 ± 18.276  ns/op
Blake3OverheadBenchmark.hasherRaw             avgt   10  488.684 ± 24.230  ns/op
Blake3OverheadBenchmark.hasherRawVzeroupper   avgt   10  476.289 ± 19.082  ns/op
Blake3OverheadBenchmark.oneShot               avgt   10  119.650 ±  7.001  ns/op
Blake3OverheadBenchmark.oneShotRaw            avgt   10  110.075 ±  4.646  ns/op
Blake3OverheadBenchmark.oneShotRawVzeroupper  avgt   10  109.294 ±  6.645  ns/op
```

## E3 (-XX:UseAVX=2)
```
Benchmark                                     Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.hasher                avgt   10  530.844 ± 19.102  ns/op
Blake3OverheadBenchmark.hasherRaw             avgt   10  478.793 ± 17.447  ns/op
Blake3OverheadBenchmark.hasherRawVzeroupper   avgt   10  488.739 ± 22.720  ns/op
Blake3OverheadBenchmark.oneShot               avgt   10  118.242 ±  8.535  ns/op
Blake3OverheadBenchmark.oneShotRaw            avgt   10  111.276 ±  7.037  ns/op
Blake3OverheadBenchmark.oneShotRawVzeroupper  avgt   10  110.986 ±  6.077  ns/op
```

## F0 (no extra options)
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  271.061 ± 25.871  ns/op
Blake3OverheadBenchmark.hasherRaw       avgt   10  622.185 ± 71.819  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  263.989 ± 13.045  ns/op
Blake3OverheadBenchmark.oneShotRaw      avgt   10  273.025 ± 45.968  ns/op
```

## F1 (-XX:+UseTransparentHugePages -Xlog:pagesize)
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  279.318 ± 40.507  ns/op
Blake3OverheadBenchmark.hasherRaw       avgt   10  571.955 ± 48.920  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  257.983 ± 24.258  ns/op
Blake3OverheadBenchmark.oneShotRaw      avgt   10  267.518 ± 20.308  ns/op
```

## F2 (-XX:+UseTransparentHugePages -XX:+AlwaysPreTouch -Xms3g -Xmx3g -Xmn2g)
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  267.673 ± 28.494  ns/op
Blake3OverheadBenchmark.hasherRaw       avgt   10  540.724 ± 31.138  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  261.524 ± 12.548  ns/op
Blake3OverheadBenchmark.oneShotRaw      avgt   10  251.012 ±  9.890  ns/op
```

## F3 (-XX:+UseTransparentHugePages -Xmn64m)
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  315.690 ± 35.115  ns/op
Blake3OverheadBenchmark.hasherRaw       avgt   10  624.508 ± 66.873  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  316.298 ± 35.653  ns/op
Blake3OverheadBenchmark.oneShotRaw      avgt   10  278.139 ± 43.875  ns/op
```

## F1 -Xlog:pagesize lines for the Java heap
```
[0.001s][info][pagesize]   THP mode: madvise
[0.001s][info][pagesize] UseLargePages=1, UseTransparentHugePages=1
[0.001s][info][pagesize] Large page support enabled. Usable page sizes: 4k, 2M. Default large page size: 2M.
[0.007s][info][pagesize] Heap:  min=8M max=3420M base=0x000000072a400000 size=3420M page_size=2M
[0.001s][info][pagesize]   THP mode: madvise
[0.001s][info][pagesize] UseLargePages=1, UseTransparentHugePages=1
[0.001s][info][pagesize] Large page support enabled. Usable page sizes: 4k, 2M. Default large page size: 2M.
[0.004s][info][pagesize] Heap:  min=8M max=3420M base=0x000000072a400000 size=3420M page_size=2M
[0.001s][info][pagesize]   THP mode: madvise
[0.001s][info][pagesize] UseLargePages=1, UseTransparentHugePages=1
```

## Analysis
All numbers are single runs on one 4-vCPU KVM guest (Sapphire Rapids class); error bars are JMH 99.9% CIs over 10 iterations.

- **C program:** a clean state hashes 1 byte in about 99-107 ns. After a dirty ymm0, zmm0 or zmm16 without vzeroupper it takes about 218-281 ns (2.2-2.6x). Dirty zmm0 and zmm16 are slightly worse (about 250-262 ns) than ymm0 (218-281 ns, noisy between runs), so the upper state of any of the three registers triggers it; this experiment does not separate them clearly. With vzeroupper before the call it returns to 103-123 ns (ymm0 and zmm16 about 103-112, zmm0 about 115-123, slightly above clean). So in pure C, vzeroupper removes the slowdown, with a possible small residue for zmm0. The C library is unchanged between rows.
- **Java, vzeroupper shims (E0):** oneShotRaw 260 ns -> oneShotRawVzeroupper 110 ns, which matches the clean C number. hasherRaw 577 -> hasherRawVzeroupper 496 ns, only about 14% lower, so hasher remains slow for other reasons (roughly 380 ns above one-shot, plausibly more native calls and allocation).
- **MaxVectorSize (E1, E2):** MaxVectorSize=32 does not help (oneShot 255, oneShotRaw 245 ns; the Vzeroupper shim is still 110). MaxVectorSize=16 brings oneShot to 120 and oneShotRaw to 110 ns, equal to the vzeroupper shim. UseAVX=2 (E3) does the same (118 / 111 ns). Under E2 and E3 the vzeroupper shim gives no further gain (109-111 ns), consistent with the JIT code no longer leaving dirty upper state. The flags change only JIT-compiled Java code, not the C library. Hasher variants improved only to about 480-540 ns.
- **Conclusion for hypothesis 1:** supported for oneShot. Dirty upper AVX state left by JIT code costs about 150 ns per call; vzeroupper before the native call, or JIT code that does not use 512-bit (or, per E1, even 256-bit upper) state, removes it. It does not explain the hasher path.
- **Huge pages (F0-F3):** allocation cost of a fresh state (newZeroedState / cloneState) is 258-271 ns in F0, F1 and F2, unchanged by THP. F1's log shows the heap uses page_size=2M (THP madvise mode, usable sizes 4k, 2M), so huge pages were in effect and did not change it. AlwaysPreTouch with a 2 GB young gen (F2) also did not help. F3 (-Xmn64m) was somewhat worse (316 ns), within about 2x error bars of the others. hasherRaw (540-625 ns) and oneShotRaw (251-278 ns) also did not change meaningfully (differences are within noise). Hypothesis 2 (TLB misses with 4 KB pages) is not supported by this data. Whether the heap was actually backed by huge pages beyond the JVM's request was not measured (no dTLB counters or smaps check).
