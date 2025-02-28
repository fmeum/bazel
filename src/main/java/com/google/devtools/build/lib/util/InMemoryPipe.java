// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;
import jdk.internal.vm.annotation.Contended;

/**
 * A drop-in replacement for {@link java.io.PipedInputStream}/{@link java.io.PipedOutputStream} that
 * doesn't use {@code synchronized} to <a href="https://bugs.openjdk.org/browse/JDK-8337395">avoid
 * deadlocks when used with virtual threads</a>.
 */
public class InMemoryPipe {
  private static final Object CLOSED_SENTINEL = new Object();

  private final byte[] buffer;
  private final int capacity;
  private final long mask;

  // Invariants:
  // * writePos and readPos increase monotonically.
  // * writePos >= readPos at all times.
  // * writePos - readPos <= capacity at all times.
  // * writePos is only modified by the thread that owns the OutPipe.
  // * readPos is only modified by the thread that owns the InPipe.
  // * parkedThread is one of:
  //   * CLOSED_SENTINEL: the pipe is closed.
  //   * Thread: the thread that is parked.
  //   * null: no thread is parked.
  // * parkedThread is only updated through CAS to ensure that at most a single thread is parked at
  //   any given time.

  // All mutable variables are only accessed via VarHandles.
  @Contended("reader")
  private long readPos = 0;

  @Contended("writer")
  private long writePos = 0;

  @Nullable private Object parkedThread;

  private static final VarHandle READ_POS;
  private static final VarHandle WRITE_POS;
  private static final VarHandle PARKED_THREAD;

  static {
    // Reduce the risk of "lost unpark" due to classloading.
    @SuppressWarnings("unused")
    Class<?> ensureLoaded = LockSupport.class;
    try {
      var lookup = MethodHandles.lookup();
      READ_POS = lookup.findVarHandle(InMemoryPipe.class, "readPos", long.class);
      WRITE_POS = lookup.findVarHandle(InMemoryPipe.class, "writePos", long.class);
      PARKED_THREAD = lookup.findVarHandle(InMemoryPipe.class, "parkedThread", Object.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public InMemoryPipe() {
    this(8192);
  }

  public InMemoryPipe(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Buffer capacity must be positive");
    }
    // Round to next power of 2 to simplify the buffer wrapping logic.
    this.capacity = 1 << (32 - Integer.numberOfLeadingZeros(capacity - 1));
    this.mask = this.capacity - 1;
    this.buffer = new byte[this.capacity];
  }

  public InputStream in() {
    return new InPipe();
  }

  public OutputStream out() {
    return new OutPipe();
  }

  // Wait for the other end of the pipe to catch up.
  private boolean waitForOtherEndOrClose(Object blocker) {
    return switch ((Object) PARKED_THREAD.compareAndExchange(this, null, Thread.currentThread())) {
      case Thread thread -> {
        // The other end of the pipe is parked, which means that it made progress, and thus we don't
        // need to park. Also, unpark the other end as we are about to make progress.
        LockSupport.unpark(thread);
        yield false;
      }
      case null -> {
        // The other end of the pipe isn't parked, so we can park.
        LockSupport.park(blocker);
        // Since only a single thread is blocked at any given time, the only way for parkedThread
        // not to be us is if the pipe was closed.
        yield !PARKED_THREAD.compareAndSet(this, Thread.currentThread(), null);
      }
      // The other end of the pipe is closed.
      default -> true;
    };
  }

  private void notifyClosed() {
    if ((Object) PARKED_THREAD.getAndSet(this, CLOSED_SENTINEL) instanceof Thread thread) {
      LockSupport.unpark(thread);
    }
  }

  private class InPipe extends InputStream {
    private final byte[] singleByte = new byte[1];

    @Override
    public int available() {
      // The read position is only modified by the current thread, so no atomicity is required.
      return (int)
          ((long) WRITE_POS.getOpaque(InMemoryPipe.this) - (long) READ_POS.get(InMemoryPipe.this));
    }

    @Override
    public int read() {
      return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      Objects.requireNonNull(b);
      Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) {
        return 0;
      }

      int originalLen = len;
      while (true) {
        // No atomicity required since the current thread is the only one that modifies the read
        // position.
        long rp = (long) READ_POS.get(InMemoryPipe.this);
        // Writes to the buffer must happen-before reading the current limit.
        long wp = (long) WRITE_POS.getAcquire(InMemoryPipe.this);
        int bytesToRead = Math.min((int) (wp - rp), len);
        if (bytesToRead > 0) {
          // The mask rounds to a power of two, so truncation to int is safe.
          int start = (int) (rp & mask);
          int end = (int) ((rp + bytesToRead) & mask);
          if (start < end) {
            System.arraycopy(buffer, start, b, off, bytesToRead);
          } else {
            int firstChunk = capacity - start;
            System.arraycopy(buffer, start, b, off, firstChunk);
            System.arraycopy(buffer, 0, b, off + firstChunk, end);
          }

          off += bytesToRead;
          len -= bytesToRead;
          // Reads from the buffer must happen-before allowing the writer to overwrite the data.
          READ_POS.setRelease(InMemoryPipe.this, rp + bytesToRead);

          if (len == 0) {
            return originalLen;
          }
        }

        if (waitForOtherEndOrClose(this)) {
          if (available() > 0) {
            // The writer has written new data and been closed since we last checked, read it first
            // before signaling EOF.
            continue;
          }
          int written = originalLen - len;
          return written == 0 ? -1 : written;
        }
      }
    }

    @Override
    public long skip(long n) {
      if (n < 0) {
        return 0;
      }

      long remaining = n;
      while (true) {
        // No atomicity required since the current thread is the only one that modifies the read
        // position.
        long rp = (long) READ_POS.get(InMemoryPipe.this);
        // No need to synchronize-with the buffer contents as we don't read them.
        long wp = (long) WRITE_POS.getOpaque(InMemoryPipe.this);
        // wp - rp <= capacity, which is always an int.
        int bytesToSkip = (int) Math.min(wp - rp, remaining);
        if (bytesToSkip > 0) {
          remaining -= bytesToSkip;
          // No need to synchronize-with the buffer contents as we don't read them.
          READ_POS.setOpaque(InMemoryPipe.this, rp + bytesToSkip);

          if (remaining == 0) {
            return n;
          }
        }

        if (waitForOtherEndOrClose(this)) {
          if (available() > 0) {
            // The writer has written new data and been closed since we last checked, skip it first
            // before signaling EOF.
            continue;
          }
          return n - remaining;
        }
      }
    }

    @Override
    public void close() {
      notifyClosed();
    }
  }

  private class OutPipe extends OutputStream {
    private final byte[] singleByte = new byte[1];

    @Override
    public void write(int b) throws IOException {
      singleByte[0] = (byte) b;
      write(singleByte, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      Objects.requireNonNull(b);
      Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) {
        return;
      }

      while (true) {
        // Reads from the buffer must happen-before we may overwrite the data.
        long rp = (long) READ_POS.getAcquire(InMemoryPipe.this);
        // No atomicity required since the current thread is the only one that modifies the write
        // position.
        long wp = (long) WRITE_POS.get(InMemoryPipe.this);
        int bytesToWrite = Math.min(capacity - (int) (wp - rp), len);
        if (bytesToWrite > 0) {
          int start = (int) (wp & mask);
          int end = (int) ((wp + bytesToWrite) & mask);
          if (start < end) {
            System.arraycopy(b, off, buffer, start, bytesToWrite);
          } else {
            int firstChunk = capacity - start;
            System.arraycopy(b, off, buffer, start, firstChunk);
            System.arraycopy(b, off + firstChunk, buffer, 0, end);
          }

          off += bytesToWrite;
          len -= bytesToWrite;
          // Writes to the buffer must happen-before allowing the reader to read the data.
          WRITE_POS.setRelease(InMemoryPipe.this, wp + bytesToWrite);

          if (len == 0) {
            return;
          }
        }

        if (waitForOtherEndOrClose(this)) {
          throw new IOException("Pipe closed");
        }
      }
    }

    @Override
    public void close() {
      notifyClosed();
    }
  }
}
