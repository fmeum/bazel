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
package com.google.devtools.build.lib.vfs;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A pool of scratch buffers used to shuttle file contents into a {@link
 * java.security.MessageDigest}.
 *
 * <p>Digesting a file is a tight loop of {@code read()} into a scratch buffer followed by an update
 * of the digest. Allocating that buffer per file is surprisingly expensive: the JVM zeroes every
 * freshly allocated array, so for the many small files a typical build digests, zeroing a buffer
 * that is larger than the file itself can cost more than hashing the file. Buffers are therefore
 * recycled.
 *
 * <p>The pool is a fixed-size array of slots that threads index into by identity. A thread normally
 * finds its own buffer in the slot it hashes to, making acquisition a single uncontended atomic
 * exchange. Because the slot count is fixed, memory consumption stays bounded even when digests are
 * computed on virtual threads, of which there may be arbitrarily many; threads that collide on a
 * slot simply allocate.
 */
@ThreadSafe
final class DigestScratchBuffers {

  /**
   * Size of a scratch buffer.
   *
   * <p>The read loop is the part of digesting that this size controls, and {@code read()} is
   * expensive enough - on macOS, and on network filesystems and FUSE anywhere - that asking for
   * less than this measurably slows down files big enough to need more than one read. Past this
   * size the digest computation is bound by the hash function instead and larger buffers stop
   * paying for themselves.
   */
  private static final int BUFFER_SIZE = 128 * 1024;

  /** Smallest and largest permissible number of slots. Both must be powers of two. */
  private static final int MIN_SLOTS = 8;

  private static final int MAX_SLOTS = 64;

  /**
   * Number of slots, a power of two.
   *
   * <p>A buffer is held only for as long as it takes to read one file, so the number of buffers in
   * use at any time is bounded by the number of threads inside the read loop. Sizing the pool at
   * twice the number of cores makes collisions - which merely cost an allocation - rare, while
   * bounding retained memory to 8 MiB.
   */
  private static final int SLOTS =
      Math.clamp(
          Integer.highestOneBit(2 * Runtime.getRuntime().availableProcessors() - 1) * 2,
          MIN_SLOTS,
          MAX_SLOTS);

  private static final int SLOT_SHIFT = Long.SIZE - Integer.numberOfTrailingZeros(SLOTS);

  private static final AtomicReferenceArray<byte[]> slots = new AtomicReferenceArray<>(SLOTS);

  private DigestScratchBuffers() {}

  /**
   * Returns the index of the slot the calling thread should use.
   *
   * <p>Thread ids are handed out sequentially, so they are mixed to avoid clustering.
   */
  private static int slotIndex() {
    long id = Thread.currentThread().threadId();
    return (int) ((id * 0x9E3779B97F4A7C15L) >>> SLOT_SHIFT);
  }

  /**
   * Returns a scratch buffer of {@link #BUFFER_SIZE} bytes whose contents are unspecified.
   *
   * <p>Must be paired with a call to {@link #release} in a {@code finally} block. Failing to
   * release a buffer is safe, but wasteful.
   */
  static byte[] acquire() {
    byte[] buffer = slots.getAndSet(slotIndex(), null);
    return buffer != null ? buffer : new byte[BUFFER_SIZE];
  }

  /** Returns a buffer previously obtained from {@link #acquire} to the pool. */
  static void release(byte[] buffer) {
    // A release store suffices: it pairs with the getAndSet in acquire(), and a buffer that isn't
    // published in time is merely reallocated by the next caller.
    slots.lazySet(slotIndex(), buffer);
  }
}
