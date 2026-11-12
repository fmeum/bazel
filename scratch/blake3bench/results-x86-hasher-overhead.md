# x86_64 BLAKE3 per-hasher overhead profile

Measurement only; code under test unchanged (commit abd7235cb9e, branch blake3-x86-profile).

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
L1d cache:                               192 KiB (4 instances)
L1i cache:                               128 KiB (4 instances)
L2 cache:                                8 MiB (4 instances)
L3 cache:                                260 MiB (1 instance)
NUMA node(s):                            1
NUMA node0 CPU(s):                       0-3
4
               total        used        free      shared  buff/cache   available
Mem:              15           0          15           0           0          15
Swap:              0           0           0
Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC @0 x86_64 x86_64 x86_64 GNU/Linux
```

CPU flags of note: avx2, avx512f/dq/cd/bw/vl/vbmi/vnni/fp16, amx, sha_ni, vaes (Sapphire Rapids class, KVM guest).

JMH header: `# VM version: JDK 25.0.4, OpenJDK 64-Bit Server VM, 25.0.4+7-LTS`; JMH 1.37, 2 forks x (3 warmup + 5 measurement iterations of 1 s), 1 thread (Cnt=10); the run-D profile uses -f 1 (Cnt=5).
VM invoker: `/root/.cache/bazel/_bazel_root/cache/repos/v1/contents/be9d1f488b48cd5544180bfdef7f8e6305d48f35f001c97b040bd54dd0910b6b/d5723423-d5df-4b36-ab52-55946591043c/bin/java`
(VM options include `--enable-native-access=ALL-UNNAMED`, no heap/GC flags.)

PrintFlagsFinal (flags absent from the output, e.g. AVX3Threshold, do not exist as product flags in this JDK):

```
      int AllocatePrefetchDistance                 = 256                                       {product} {default}
      int AllocatePrefetchStyle                    = 1                                         {product} {default}
   size_t MaxHeapSize                              = 3586129920                                {product} {ergonomic}
   size_t MaxNewSize                               = 2151677952                                {product} {ergonomic}
   size_t NewSize                                  = 1363144                                   {product} {default}
      int UseAVX                                   = 3                                    {ARCH product} {default}
     bool UseCompactObjectHeaders                  = false                          {product lp64_product} {default}
     bool UseG1GC                                  = true                                      {product} {ergonomic}
     bool UseLargePages                            = false                                  {pd product} {default}
     bool UseParallelGC                            = false                                     {product} {default}
     bool UseTLAB                                  = true                                      {product} {default}
     bool UseTransparentHugePages                  = false                                     {product} {default}
```

## Run A: all benchmarks, -prof gc
```
Benchmark                                                          Mode  Cnt     Score     Error   Units
Blake3OverheadBenchmark.cloneState                                 avgt   10   261.896 ±   6.777   ns/op
Blake3OverheadBenchmark.cloneState:gc.alloc.rate                   avgt   10  7018.428 ± 180.575  MB/sec
Blake3OverheadBenchmark.cloneState:gc.alloc.rate.norm              avgt   10  1928.002 ±   0.001    B/op
Blake3OverheadBenchmark.cloneState:gc.count                        avgt   10   150.000            counts
Blake3OverheadBenchmark.cloneState:gc.time                         avgt   10   150.000                ms
Blake3OverheadBenchmark.digestOneByte                              avgt   10   258.954 ±   9.361   ns/op
Blake3OverheadBenchmark.digestOneByte:gc.alloc.rate                avgt   10   176.768 ±   6.365  MB/sec
Blake3OverheadBenchmark.digestOneByte:gc.alloc.rate.norm           avgt   10    48.002 ±   0.001    B/op
Blake3OverheadBenchmark.digestOneByte:gc.count                     avgt   10    14.000            counts
Blake3OverheadBenchmark.digestOneByte:gc.time                      avgt   10    37.000                ms
Blake3OverheadBenchmark.downcallFinalizeHeap                       avgt   10    99.327 ±   4.456   ns/op
Blake3OverheadBenchmark.downcallFinalizeHeap:gc.alloc.rate         avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.downcallFinalizeHeap:gc.alloc.rate.norm    avgt   10     0.001 ±   0.001    B/op
Blake3OverheadBenchmark.downcallFinalizeHeap:gc.count              avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.downcallFinalizeNative                     avgt   10   102.019 ±   3.091   ns/op
Blake3OverheadBenchmark.downcallFinalizeNative:gc.alloc.rate       avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.downcallFinalizeNative:gc.alloc.rate.norm  avgt   10     0.001 ±   0.001    B/op
Blake3OverheadBenchmark.downcallFinalizeNative:gc.count            avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.downcallInitHeap                           avgt   10     4.251 ±   0.214   ns/op
Blake3OverheadBenchmark.downcallInitHeap:gc.alloc.rate             avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.downcallInitHeap:gc.alloc.rate.norm        avgt   10    ≈ 10⁻⁵              B/op
Blake3OverheadBenchmark.downcallInitHeap:gc.count                  avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.downcallInitNative                         avgt   10     5.254 ±   0.261   ns/op
Blake3OverheadBenchmark.downcallInitNative:gc.alloc.rate           avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.downcallInitNative:gc.alloc.rate.norm      avgt   10    ≈ 10⁻⁴              B/op
Blake3OverheadBenchmark.downcallInitNative:gc.count                avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.downcallTrivial                            avgt   10     3.080 ±   0.385   ns/op
Blake3OverheadBenchmark.downcallTrivial:gc.alloc.rate              avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.downcallTrivial:gc.alloc.rate.norm         avgt   10    ≈ 10⁻⁵              B/op
Blake3OverheadBenchmark.downcallTrivial:gc.count                   avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.hasher                                     avgt   10   627.347 ±  39.885   ns/op
Blake3OverheadBenchmark.hasher:gc.alloc.rate                       avgt   10  3104.531 ± 184.691  MB/sec
Blake3OverheadBenchmark.hasher:gc.alloc.rate.norm                  avgt   10  2040.004 ±   0.001    B/op
Blake3OverheadBenchmark.hasher:gc.count                            avgt   10    94.000            counts
Blake3OverheadBenchmark.hasher:gc.time                             avgt   10   103.000                ms
Blake3OverheadBenchmark.newHasher                                  avgt   10   301.549 ±   8.055   ns/op
Blake3OverheadBenchmark.newHasher:gc.alloc.rate                    avgt   10  6374.809 ± 169.854  MB/sec
Blake3OverheadBenchmark.newHasher:gc.alloc.rate.norm               avgt   10  2016.002 ±   0.001    B/op
Blake3OverheadBenchmark.newHasher:gc.count                         avgt   10   127.000            counts
Blake3OverheadBenchmark.newHasher:gc.time                          avgt   10   154.000                ms
Blake3OverheadBenchmark.newZeroedState                             avgt   10   255.093 ±   6.237   ns/op
Blake3OverheadBenchmark.newZeroedState:gc.alloc.rate               avgt   10  7206.569 ± 176.617  MB/sec
Blake3OverheadBenchmark.newZeroedState:gc.alloc.rate.norm          avgt   10  1928.002 ±   0.001    B/op
Blake3OverheadBenchmark.newZeroedState:gc.count                    avgt   10   139.000            counts
Blake3OverheadBenchmark.newZeroedState:gc.time                     avgt   10   160.000                ms
Blake3OverheadBenchmark.oneShot                                    avgt   10   283.172 ±  23.623   ns/op
Blake3OverheadBenchmark.oneShot:gc.alloc.rate                      avgt   10   378.050 ±  30.833  MB/sec
Blake3OverheadBenchmark.oneShot:gc.alloc.rate.norm                 avgt   10   112.002 ±   0.001    B/op
Blake3OverheadBenchmark.oneShot:gc.count                           avgt   10    30.000            counts
Blake3OverheadBenchmark.oneShot:gc.time                            avgt   10    53.000                ms
Blake3OverheadBenchmark.resetState                                 avgt   10    16.230 ±   1.211   ns/op
Blake3OverheadBenchmark.resetState:gc.alloc.rate                   avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.resetState:gc.alloc.rate.norm              avgt   10    ≈ 10⁻⁴              B/op
Blake3OverheadBenchmark.resetState:gc.count                        avgt   10       ≈ 0            counts
Blake3OverheadBenchmark.updateThenReset                            avgt   10    29.166 ±   6.186   ns/op
Blake3OverheadBenchmark.updateThenReset:gc.alloc.rate              avgt   10     0.007 ±   0.001  MB/sec
Blake3OverheadBenchmark.updateThenReset:gc.alloc.rate.norm         avgt   10    ≈ 10⁻⁴              B/op
Blake3OverheadBenchmark.updateThenReset:gc.count                   avgt   10       ≈ 0            counts
```

## Run B0: heap/JIT sensitivity baseline (no extra options)
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  277.329 ± 12.347  ns/op
Blake3OverheadBenchmark.hasher          avgt   10  635.054 ± 32.520  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  261.774 ± 11.929  ns/op
Blake3OverheadBenchmark.oneShot         avgt   10  285.767 ± 13.221  ns/op
Blake3OverheadBenchmark.resetState      avgt   10   18.906 ±  0.776  ns/op
```

## Run B1: -Xmn8m
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  252.714 ± 24.507  ns/op
Blake3OverheadBenchmark.hasher          avgt   10  594.422 ± 28.152  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  274.192 ± 11.715  ns/op
Blake3OverheadBenchmark.oneShot         avgt   10  297.456 ±  9.362  ns/op
Blake3OverheadBenchmark.resetState      avgt   10   18.245 ±  0.521  ns/op
```

## Run B2: -Xmn2g -Xms3g -Xmx3g -XX:+AlwaysPreTouch
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  263.677 ± 10.602  ns/op
Blake3OverheadBenchmark.hasher          avgt   10  615.557 ± 26.557  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  258.602 ± 11.955  ns/op
Blake3OverheadBenchmark.oneShot         avgt   10  276.065 ± 14.320  ns/op
Blake3OverheadBenchmark.resetState      avgt   10   19.393 ±  0.825  ns/op
```

## Run B3: -XX:+UseParallelGC
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  266.355 ± 12.225  ns/op
Blake3OverheadBenchmark.hasher          avgt   10  614.537 ± 25.245  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  250.657 ±  8.074  ns/op
Blake3OverheadBenchmark.oneShot         avgt   10  283.498 ± 11.291  ns/op
Blake3OverheadBenchmark.resetState      avgt   10   17.443 ±  1.742  ns/op
```

## Run B4: -XX:UseAVX=2
```
Benchmark                               Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.cloneState      avgt   10  303.155 ±  8.465  ns/op
Blake3OverheadBenchmark.hasher          avgt   10  503.081 ± 13.830  ns/op
Blake3OverheadBenchmark.newZeroedState  avgt   10  281.681 ±  9.858  ns/op
Blake3OverheadBenchmark.oneShot         avgt   10  124.544 ±  5.417  ns/op
Blake3OverheadBenchmark.resetState      avgt   10   40.361 ±  1.588  ns/op
```

## Run C: hardware counters

Not available. `perf` was installed (linux-tools-6.8.0-149-generic; /usr/lib/linux-tools/6.8.0-149-generic/perf), but `perf stat -e cycles,instructions,cache-misses true` reports `<not supported>` for all three events (KVM guest without a vPMU; perf_event_paranoid=2). -prof perfnorm was skipped.

## Run D: async-profiler (event=cpu worked, no fallback to itimer needed)

`-f 1`, default sampling interval, so only ~500 samples per benchmark. Percentages are of all samples in that benchmark's profile (the benchmark thread plus a few GC/JIT threads). Times in ns convert percentages with the -f 1 scores (hasher 632.3 ns/op, oneShot 289.3 ns/op).
Run D JMH scores:
```
Benchmark                              Mode  Cnt    Score    Error  Units
Blake3OverheadBenchmark.hasher         avgt    5  632.269 ± 60.743  ns/op
Blake3OverheadBenchmark.hasher:async   avgt           NaN             ---
Blake3OverheadBenchmark.oneShot        avgt    5  289.301 ± 40.680  ns/op
Blake3OverheadBenchmark.oneShot:async  avgt           NaN             ---
```

### D.1 hasher
```
total samples 499

Top 25 leaf frames
    314  62.9%  blake3_hasher_finalize_seek
    134  26.9%  _blake3_compress_xof_avx512
     26   5.2%  blake3_hasher_update.part.0
      6   1.2%  jlong_disjoint_arraycopy
      4   0.8%  nep_invoker_blob
      3   0.6%  com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
      3   0.6%  blake3_compress_xof
      2   0.4%  finish_task_switch.isra.0_[k]
      1   0.2%  G1CollectionSet::iterate_incremental_part_from
      1   0.2%  G1HeapRegionManager::par_iterate
      1   0.2%  __popcountdi2
      1   0.2%  G1CollectedHeap::new_mutator_alloc_region
      1   0.2%  com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.<init>
      1   0.2%  _raw_spin_unlock_irqrestore_[k]
      1   0.2%  G1CollectedHeap::prepare_for_mutator_after_young_collection

Top 15 stacks (innermost 12 frames, outermost first)
    314  62.9%
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.hash
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.writeOutput
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cf000.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
    blake3_hasher_finalize_seek
    134  26.9%
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.hash
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.writeOutput
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cf000.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
    _blake3_compress_xof_avx512
     26   5.2%
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.putBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.engineUpdate
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.update
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cec00.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
    blake3_hasher_update.part.0
      4   0.8%
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x0000000019061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x0000000019061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.newHasher
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.<init>
    jlong_disjoint_arraycopy
      3   0.6%
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x0000000019061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x0000000019061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.hash
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
      3   0.6%
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.hash
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.writeOutput
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cf000.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
    blake3_hasher_finalize_seek
    blake3_compress_xof
      2   0.4%
    __new_sem_wait_slow64.constprop.0
    __GI___futex_abstimed_wait_cancelable64
    entry_SYSCALL_64_after_hwframe_[k]
    do_syscall_64_[k]
    __x64_sys_futex_[k]
    do_futex_[k]
    futex_wait_[k]
    __futex_wait_[k]
    futex_do_wait_[k]
    schedule_[k]
    __schedule_[k]
    finish_task_switch.isra.0_[k]
      2   0.4%
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.putBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.engineUpdate
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.update
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cec00.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
      2   0.4%
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.hash
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.digestWithoutReset
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.writeOutput
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cf000.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
      2   0.4%
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x0000000019061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x0000000019061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.putBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.engineUpdate
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.update
    jlong_disjoint_arraycopy
      1   0.2%
    __GI___clone3
    start_thread
    thread_native_entry
    Thread::call_run
    WorkerThread::run
    G1MergeHeapRootsTask::work
    G1CollectionSet::iterate_incremental_part_from
      1   0.2%
    __GI___clone3
    start_thread
    thread_native_entry
    Thread::call_run
    WorkerThread::run
    G1PrepareEvacuationTask::work
    G1HeapRegionManager::par_iterate
      1   0.2%
    com/google/devtools/build/lib/vfs/bazel/Blake3Hasher.putBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.engineUpdate
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.update
    java/lang/invoke/LambdaForm$MH.0x00000000190cdc00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190cec00.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000190c7400.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000190c7000.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000190ce400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000190ce800.invoke
    nep_invoker_blob
    blake3_hasher_update.part.0
    __popcountdi2
      1   0.2%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.newHasher
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.<init>
    C2 Runtime new_array_nozero
    OptoRuntime::new_array_nozero_C
    TypeArrayKlass::allocate_common
    MemAllocator::allocate
    MemAllocator::mem_allocate
    MemAllocator::mem_allocate_inside_tlab_slow
    G1CollectedHeap::allocate_new_tlab
    G1CollectedHeap::attempt_allocation_slow
    G1AllocRegion::new_alloc_region_and_allocate
    G1CollectedHeap::new_mutator_alloc_region
      1   0.2%
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x0000000019061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x0000000019061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_hasher_jmhTest.hasher_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.hasher
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.newHasher
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.<init>
```

### D.2 oneShot
```
total samples 500

Top 25 leaf frames
    252  50.4%  _blake3_compress_xof_avx512
    133  26.6%  blake3_hasher_init
     56  11.2%  blake3_hasher_finalize_seek
     13   2.6%  blake3_hasher_update.part.0
     12   2.4%  jlong_disjoint_arraycopy
      6   1.2%  __popcountdi2
      5   1.0%  com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
      3   0.6%  com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
      3   0.6%  /tmp/bazel-jni.15300677695015147235/libunix_jni.so
      3   0.6%  blaze_blake3_hash
      2   0.4%  nep_invoker_blob
      2   0.4%  G1ParScanThreadState::trim_queue_to_threshold
      2   0.4%  java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
      2   0.4%  com/google/common/hash/HashCode.fromBytesNoCopy
      1   0.2%  LeakProfiler::is_running
      1   0.2%  G1CodeRootSet::add
      1   0.2%  java/lang/invoke/MethodHandle.invokeBasic
      1   0.2%  blake3_compress_xof
      1   0.2%  blake3_hasher_update
      1   0.2%  finish_task_switch.isra.0_[k]

Top 15 stacks (innermost 12 frames, outermost first)
    252  50.4%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
    _blake3_compress_xof_avx512
    133  26.6%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
    blake3_hasher_init
     56  11.2%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
    blake3_hasher_finalize_seek
     13   2.6%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
    blake3_hasher_update.part.0
     12   2.4%
    java/util/concurrent/FutureTask.run
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x00000000a0061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a0061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    jlong_disjoint_arraycopy
      6   1.2%
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
    blake3_hasher_update.part.0
    __popcountdi2
      5   1.0%
    java/util/concurrent/Executors$RunnableAdapter.call
    java/util/concurrent/FutureTask.run
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    org/openjdk/jmh/runner/BenchmarkHandler$BenchmarkTask.call
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x00000000a0061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a0061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
      3   0.6%
    java/lang/reflect/Method.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x00000000a0061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a0061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
      3   0.6%
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    /tmp/bazel-jni.15300677695015147235/libunix_jni.so
      3   0.6%
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
    blaze_blake3_hash
      2   0.4%
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
    nep_invoker_blob
      2   0.4%
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    java/lang/invoke/LambdaForm$MH.0x00000000a00cec00.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf800.invoke
    java/lang/invoke/LambdaForm$DMH.0x00000000a00c9c00.invokeStatic
    jdk/internal/foreign/abi/DowncallStub.0x00000000a00c9800.invoke
    java/lang/invoke/LambdaForm$MH.0x00000000a00cf400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a00cfc00.invoke
      2   0.4%
    jdk/internal/reflect/DirectMethodHandleAccessor.invoke
    jdk/internal/reflect/DirectMethodHandleAccessor.invokeImpl
    java/lang/invoke/LambdaForm$MH.0x00000000a0061400.invokeExact_MT
    java/lang/invoke/LambdaForm$MH.0x00000000a0061000.invoke
    java/lang/invoke/DirectMethodHandle$Holder.invokeSpecial
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_AverageTime
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/common/hash/HashCode.fromBytes
    com/google/common/hash/HashCode.fromBytesNoCopy
      1   0.2%
    com/google/devtools/build/lib/vfs/bazel/jmh_generated/Blake3OverheadBenchmark_oneShot_jmhTest.oneShot_avgt_jmhStub
    com/google/devtools/build/lib/vfs/bazel/Blake3OverheadBenchmark.oneShot
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3HashFunction.hashBytes
    com/google/devtools/build/lib/vfs/bazel/Blake3MessageDigest.hash
    C2 Runtime new_array
    OptoRuntime::new_array_C
    TypeArrayKlass::allocate_common
    MemAllocator::allocate
    AllocTracer::send_allocation_in_new_tlab
    JfrAllocationTracer::JfrAllocationTracer
    LeakProfiler::is_running
      1   0.2%
    WorkerThread::run
    G1EvacuateRegionsBaseTask::work
    G1RemSet::scan_collection_set_code_roots
    G1CollectedHeap::par_iterate_regions_array
    G1ScanCodeRootsClosure::do_heap_region
    G1CodeRootSet::nmethods_do
    G1ScanAndCountNMethodClosure::do_nmethod
    G1NMethodClosure::do_nmethod
    nmethod::oops_do_process_weak
    G1NmethodProcessor::do_regular_processing
    nmethod::oops_do
    G1CodeRootSet::add
```

## Analysis

All numbers are ns/op for a 1-byte input, 2-fork JMH runs (error bars about +-5%).

**The gap.** hasher is ~630 ns (627 in A, 635 in B0, 632 in D) and oneShot is ~285 ns, so the per-hasher overhead is ~345 ns. This reproduces the ~670 vs ~300 ns reported for this kind of VM.

**Where the gap goes (benchmark parts, run A):**
- Creating the heap state is the largest single piece. newZeroedState (allocate a 1912-byte long[], no copy) is 255 ns and cloneState/newHasher is ~280-300 ns (newHasher 301 ns includes the Blake3MessageDigest object). Allocation is 1928-2016 B/op. The memcpy itself is negligible: async-profiler shows jlong_disjoint_arraycopy at only 1.2% of the hasher samples (~8 ns). So the cost is in getting a fresh 2 KB array, not in copying into it. 2 KB per 255 ns is ~7.5 GB/s of allocation rate (gc.alloc.rate 7.2 GB/s for newZeroedState), well below what L1/L2 memset would give; consistent with first-touch / store misses on freshly handed-out young-gen memory, but I could not confirm that (no hardware counters).
- The Java-side reset/update parts are small: resetState 16 ns, updateThenReset 29 ns, so the putBytes downcall is ~13 ns beyond a reset. Raw downcalls are cheap: downcallTrivial 3 ns, downcallInitHeap 4.3 ns, downcallInitNative 5.3 ns. FFM critical-call overhead and the heap-vs-native segment choice are not the problem.
- digestOneByte/finalize on the heap state is the remainder: hasher (635) - newHasher (301) - update (~30) leaves ~300 ns for finalize, similar to the entire oneShot call (283 ns incl. init).
- downcallFinalizeNative (finalize with native-memory state, run A) is 102 ns, i.e. finalize itself is cheap when the state is hot/native, so the ~300 ns finalize cost on the freshly cloned heap array is an extra ~200 ns attributable to the fresh-array state.
- Run A also has downcallFinalizeHeap = 99 ns and downcallFinalizeNative = 102 ns (finalize on a reused, cache-hot state: heap vs native state makes no difference), while digestOneByte is 259 ns (48 B/op allocated). So finalize itself costs ~100 ns when the state is hot, and the extra ~150-200 ns in the end-to-end hasher path appears when the state is a freshly allocated array. I did not inspect what digestOneByte does beyond its name/allocation.
- GC itself is not the cost: gc.time was 103 ms total for hasher in the whole run (94 young GCs), 154 ms for newHasher; at 6-7 GB/s allocation that is a few percent.

**async-profiler (D).** hasher: 62.9% (~400 ns) of samples are *self time in blake3_hasher_finalize_seek*, 26.9% (~170 ns) in _blake3_compress_xof_avx512, 5.2% in blake3_hasher_update, only ~1.2% in the array copy. oneShot: 50.4% (~146 ns) in _blake3_compress_xof_avx512, 26.6% (~77 ns) in blake3_hasher_init, 11.2% (~32 ns) in finalize_seek. So compress time is about the same in both paths (146 vs 170 ns); the difference is ~370 ns of self time inside blake3_hasher_finalize_seek in the hasher path that has no counterpart in oneShot (where the same function runs on the freshly written native-stack state), partly offset by oneShot paying for blake3_hasher_init (~77 ns). Self time in a function that only reads the state and writes the output points to stalls on loading the just-cloned heap array (cache/store-buffer effects of fresh memory, skid from the preceding allocation) rather than instructions; this is a hypothesis, I did not verify it. Note that the async cpu samples are few (~500 per benchmark).

**Which B options change it:**
- B1 (-Xmn8m): hasher 594 (vs 635), oneShot 297; cloneState 253, newZeroedState 274. Within noise/small (~-6% on hasher); the gap stays ~300 ns. A tiny young gen does not fix the allocation cost.
- B2 (3 GB heap, 2 GB young, pre-touched): hasher 616, oneShot 276, cloneState 264, newZeroedState 259. No change; so page faults / heap growth are not the cause.
- B3 (ParallelGC): hasher 615, oneShot 283, cloneState 266, newZeroedState 251. No change; GC choice (G1 barriers/TLAB handling) is not the cause.
- B4 (-XX:UseAVX=2): **the only option that changes things substantially.** oneShot drops from 286 to 125 ns (-160 ns) and hasher from 635 to 503 ns (-130 ns), so the gap actually *widens* in relative terms: 378 ns vs 349 ns in absolute terms (503-125). resetState rises 19 -> 40 ns and cloneState 277 -> 303 ns (the JIT uses 32-byte instead of 64-byte vector code for array operations). The AVX-512 compress path in the BLAKE3 C library thus costs ~160 ns more than the AVX2 path for a 1-byte input on this CPU/VM (consistent with the _blake3_compress_xof_avx512 hot spot above, and likely AVX-512 warmup / state-transition cost on this VM), but it is a cost shared by both paths. The per-hasher overhead itself (~350-380 ns) is independent of the vector width.

**Summary.** The ~345 ns per-hasher overhead is mostly (a) ~255-300 ns for allocating and initializing a fresh 1.9 KB heap array, which is independent of GC type and heap sizing and was not removable with any of the tested options, and (b) an extra slowdown of the finalize call on that fresh array (profile attributes it as self time in blake3_hasher_finalize_seek). FFM downcall overhead itself (3-5 ns) and the Java-side update/reset (16-30 ns) are negligible. Separately, AVX-512 makes small-input compress ~160 ns slower than AVX2 on this machine, which affects both paths equally. Hardware counters were unavailable so the cache-miss explanation for (a)/(b) is untested; a follow-up could reuse one pre-allocated heap/native state per thread (as resetState/updateThenReset hint: ~16-30 ns) instead of cloning per hasher.
