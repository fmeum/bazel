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

import com.google.devtools.build.lib.jni.JniLoader;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Experimental BLAKE3 entry points for benchmarking only. */
@SuppressWarnings("restricted")
public final class Blake3Experimental {
  static {
    JniLoader.loadJni();
  }

  private Blake3Experimental() {}

  public static final int OUT_LEN = 32;

  // ---- JNI one-shot -------------------------------------------------------

  private static native void hashOneShotJni(byte[] input, int offset, int len, byte[] out);

  public static byte[] jniOneShot(byte[] input, int offset, int len) {
    byte[] out = new byte[OUT_LEN];
    hashOneShotJni(input, offset, len, out);
    return out;
  }

  // ---- FFM ----------------------------------------------------------------

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private static MemorySegment find(String name) {
    return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
  }

  private static final MethodHandle HASHER_SIZE =
      LINKER.downcallHandle(
          find("blaze_blake3_hasher_size"),
          FunctionDescriptor.of(JAVA_INT),
          Linker.Option.critical(false));
  private static final MethodHandle HASHER_INIT =
      LINKER.downcallHandle(
          find("blaze_blake3_hasher_init"),
          FunctionDescriptor.ofVoid(ADDRESS),
          Linker.Option.critical(false));
  private static final MethodHandle HASHER_INIT_HEAP =
      LINKER.downcallHandle(
          find("blaze_blake3_hasher_init"),
          FunctionDescriptor.ofVoid(ADDRESS),
          Linker.Option.critical(true));
  private static final MethodHandle HASHER_UPDATE =
      LINKER.downcallHandle(
          find("blaze_blake3_hasher_update"),
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG),
          Linker.Option.critical(true));
  private static final MethodHandle HASHER_FINALIZE =
      LINKER.downcallHandle(
          find("blaze_blake3_hasher_finalize"),
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG),
          Linker.Option.critical(true));
  private static final MethodHandle HASH =
      LINKER.downcallHandle(
          find("blaze_blake3_hash"),
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS),
          Linker.Option.critical(true));
  // Not critical: performs blocking I/O.
  private static final MethodHandle HASH_FILE =
      LINKER.downcallHandle(
          find("blaze_blake3_hash_file"),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

  private static final long STATE_SIZE;

  static {
    try {
      STATE_SIZE = (int) HASHER_SIZE.invokeExact();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  public static byte[] ffmOneShot(byte[] input, int offset, int len) {
    byte[] out = new byte[OUT_LEN];
    try {
      HASH.invokeExact(
          MemorySegment.ofArray(input).asSlice(offset, len),
          (long) len,
          MemorySegment.ofArray(out));
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
    return out;
  }

  /** Streaming hasher with native state in a confined arena. */
  public static final class FfmHasher {
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment state = arena.allocate(STATE_SIZE, 8);

    public FfmHasher() {
      try {
        HASHER_INIT.invokeExact(state);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }

    public void update(byte[] data, int offset, int len) {
      try {
        HASHER_UPDATE.invokeExact(
            state, MemorySegment.ofArray(data).asSlice(offset, len), (long) len);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }

    public byte[] digestAndClose() {
      byte[] out = new byte[OUT_LEN];
      try {
        HASHER_FINALIZE.invokeExact(state, MemorySegment.ofArray(out), (long) OUT_LEN);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
      arena.close();
      return out;
    }
  }

  /** Streaming hasher with state in a Java heap array, passed via critical heap access. */
  public static final class FfmHeapHasher {
    private final long[] state = new long[(int) ((STATE_SIZE + 7) / 8)];

    public FfmHeapHasher() {
      try {
        HASHER_INIT_HEAP.invokeExact(MemorySegment.ofArray(state));
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }

    public void update(byte[] data, int offset, int len) {
      try {
        HASHER_UPDATE.invokeExact(
            MemorySegment.ofArray(state), MemorySegment.ofArray(data).asSlice(offset, len), (long) len);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }

    public byte[] digest() {
      byte[] out = new byte[OUT_LEN];
      try {
        HASHER_FINALIZE.invokeExact(
            MemorySegment.ofArray(state), MemorySegment.ofArray(out), (long) OUT_LEN);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
      return out;
    }
  }

  public static byte[] hashFile(String path, int bufSize) throws IOException {
    byte[] out = new byte[OUT_LEN];
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment cPath = arena.allocateFrom(path, StandardCharsets.ISO_8859_1);
      MemorySegment outSeg = arena.allocate(OUT_LEN);
      int err = (int) HASH_FILE.invokeExact(cPath, outSeg, (long) bufSize);
      if (err != 0) {
        throw new IOException("errno " + err + ": " + path);
      }
      MemorySegment.copy(outSeg, 0, MemorySegment.ofArray(out), 0, OUT_LEN);
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
    return out;
  }
}
