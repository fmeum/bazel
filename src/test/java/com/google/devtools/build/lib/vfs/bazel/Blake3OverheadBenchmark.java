// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.vfs.bazel;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.google.common.hash.HashCode;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Splits the cost of hashing one byte through a {@link Blake3Hasher} into its parts, to compare
 * it with {@link Blake3HashFunction#hashBytes}. Scratch code for investigating the per-hasher
 * overhead, not meant to be submitted.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class Blake3OverheadBenchmark {
  private static final long[] INITIAL_STATE;
  private static final MethodHandle HASHER_SIZE;
  private static final MethodHandle HASHER_INIT;
  private static final MethodHandle HASHER_UPDATE;
  private static final MethodHandle HASHER_FINALIZE;

  static {
    try {
      var field = Blake3MessageDigest.class.getDeclaredField("INITIAL_STATE");
      field.setAccessible(true);
      // Reading the field initializes Blake3MessageDigest, which loads the native library.
      INITIAL_STATE = (long[]) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
    var linker = Linker.nativeLinker();
    var lookup = SymbolLookup.loaderLookup();
    HASHER_SIZE =
        linker.downcallHandle(
            lookup.find("blaze_blake3_hasher_size").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT),
            Linker.Option.critical(false));
    HASHER_INIT =
        linker.downcallHandle(
            lookup.find("blake3_hasher_init").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS),
            Linker.Option.critical(true));
    HASHER_UPDATE =
        linker.downcallHandle(
            lookup.find("blake3_hasher_update").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG),
            Linker.Option.critical(true));
    HASHER_FINALIZE =
        linker.downcallHandle(
            lookup.find("blake3_hasher_finalize").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG),
            Linker.Option.critical(true));
  }

  private final byte[] oneByte = {42};
  private final byte[] out = new byte[32];
  private Blake3MessageDigest digest;
  private Blake3MessageDigest digestWithOneByte;
  private long[] heapState;
  private MemorySegment heapStateWithOneByte;
  private MemorySegment nativeState;
  private MemorySegment nativeStateWithOneByte;

  @Setup
  public void setup() throws Throwable {
    digest = new Blake3MessageDigest();
    digestWithOneByte = new Blake3MessageDigest();
    digestWithOneByte.engineUpdate(oneByte, 0, 1);
    heapState = INITIAL_STATE.clone();
    heapStateWithOneByte = MemorySegment.ofArray(INITIAL_STATE.clone());
    HASHER_UPDATE.invokeExact(heapStateWithOneByte, MemorySegment.ofArray(oneByte), 1L);
    long size = INITIAL_STATE.length * (long) Long.BYTES;
    nativeState = Arena.global().allocate(size, 64);
    nativeStateWithOneByte = Arena.global().allocate(size, 64);
    MemorySegment.copy(MemorySegment.ofArray(INITIAL_STATE), 0, nativeStateWithOneByte, 0, size);
    HASHER_UPDATE.invokeExact(nativeStateWithOneByte, MemorySegment.ofArray(oneByte), 1L);
  }

  // The two end-to-end paths.

  @Benchmark
  public HashCode oneShot() {
    return Blake3HashFunction.INSTANCE.hashBytes(oneByte);
  }

  @Benchmark
  public HashCode hasher() {
    return Blake3HashFunction.INSTANCE.newHasher().putBytes(oneByte).hash();
  }

  // The parts of the hasher path.

  @Benchmark
  public Object newHasher() {
    return Blake3HashFunction.INSTANCE.newHasher();
  }

  @Benchmark
  public long[] cloneState() {
    return INITIAL_STATE.clone();
  }

  @Benchmark
  public long[] newZeroedState() {
    return new long[INITIAL_STATE.length];
  }

  @Benchmark
  public void resetState() {
    System.arraycopy(INITIAL_STATE, 0, heapState, 0, heapState.length);
  }

  @Benchmark
  public void updateThenReset() {
    digest.engineUpdate(oneByte, 0, 1);
    digest.engineReset();
  }

  @Benchmark
  public byte[] digestOneByte() {
    return digestWithOneByte.digestWithoutReset();
  }

  // Raw downcalls, with the hasher state on the heap and in native memory.

  @Benchmark
  public int downcallTrivial() throws Throwable {
    return (int) HASHER_SIZE.invokeExact();
  }

  @Benchmark
  public void downcallInitHeap() throws Throwable {
    HASHER_INIT.invokeExact(MemorySegment.ofArray(heapState));
  }

  @Benchmark
  public void downcallInitNative() throws Throwable {
    HASHER_INIT.invokeExact(nativeState);
  }

  @Benchmark
  public void downcallFinalizeHeap() throws Throwable {
    HASHER_FINALIZE.invokeExact(heapStateWithOneByte, MemorySegment.ofArray(out), 32L);
  }

  @Benchmark
  public void downcallFinalizeNative() throws Throwable {
    HASHER_FINALIZE.invokeExact(nativeStateWithOneByte, MemorySegment.ofArray(out), 32L);
  }
}
