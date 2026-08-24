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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.remote.RemoteRewoundActionSynchronizer.FineLock;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the fine locks of {@link RemoteRewoundActionSynchronizer}. */
@RunWith(JUnit4.class)
public final class RemoteRewoundActionSynchronizerTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final List<Thread> threads = new ArrayList<>();

  @After
  public void joinThreads() throws InterruptedException {
    for (Thread thread : threads) {
      thread.interrupt();
    }
    for (Thread thread : threads) {
      thread.join(TIMEOUT.toMillis());
    }
    threads.clear();
  }

  @Test(timeout = 30_000)
  public void readLock_notBlockedByQueuedWriter() throws Exception {
    var lock = new FineLock();

    lock.lockReadInterruptibly();
    startBlockedWriter(lock);

    assertThat(awaitReadLock(lock)).isTrue();

    lock.unlockRead();
  }

  /**
   * The RR case of the deadlock proof requires that a thread waiting for a read lock is only ever
   * waiting for the thread holding the write lock.
   *
   * <p>This is the scenario that breaks if the read lock is acquired with a single barging {@code
   * tryLock} followed by a blocking acquisition on a {@link
   * java.util.concurrent.locks.ReentrantReadWriteLock}: the first reader fails to barge because a
   * writer holds the lock, and a blocking acquisition enqueues it behind the second writer, where a
   * reader that barges in later keeps it blocked indefinitely.
   */
  @Test(timeout = 30_000)
  public void readLock_notBlockedByReaderBehindQueuedWriter() throws Exception {
    for (int i = 0; i < 100; i++) {
      var lock = new FineLock();

      // A rewound action covering the key holds the write lock.
      lock.lockWriteInterruptibly();
      // A second rewound action covering the same key wants the write lock.
      startBlockedWriter(lock);
      // A consumer of the key can't barge and has to wait for the write lock to be released.
      var blockedReader = startBlockedReader(lock);
      // Another consumer of the key barges in as soon as the write lock is released and holds on
      // to the read lock.
      var bargingReader = new BargingReader(lock);
      startThread("bargingReader", bargingReader);

      lock.unlockWrite();

      assertThat(bargingReader.acquired.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
      assertThat(blockedReader.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
      bargingReader.release.countDown();

      joinThreads();
    }
  }

  @Test(timeout = 30_000)
  public void writeLock_excludesReaders() throws Exception {
    var lock = new FineLock();

    lock.lockWriteInterruptibly();
    assertThat(awaitReadLock(lock)).isFalse();

    lock.unlockWrite();
    assertThat(awaitReadLock(lock)).isTrue();
  }

  @Test(timeout = 30_000)
  public void writeLock_excludesWriters() throws Exception {
    var lock = new FineLock();

    lock.lockWriteInterruptibly();
    var blockedWriter = startBlockedWriter(lock);

    lock.unlockWrite();
    blockedWriter.join(TIMEOUT.toMillis());
    assertThat(blockedWriter.isAlive()).isFalse();
  }

  @Test(timeout = 30_000)
  public void writeLock_waitsForReadersToDrain() throws Exception {
    var lock = new FineLock();

    lock.lockReadInterruptibly();
    var blockedWriter = startBlockedWriter(lock);

    lock.unlockRead();
    blockedWriter.join(TIMEOUT.toMillis());
    assertThat(blockedWriter.isAlive()).isFalse();
  }

  @Test(timeout = 30_000)
  public void locks_areReentrant() throws Exception {
    var lock = new FineLock();

    lock.lockReadInterruptibly();
    lock.lockReadInterruptibly();
    lock.unlockRead();
    // Still read locked.
    assertThat(awaitWriteLock(lock)).isFalse();
    lock.unlockRead();

    lock.lockWriteInterruptibly();
    lock.lockWriteInterruptibly();
    // A thread holding the write lock can also acquire the read lock.
    assertThat(lock.tryLockRead()).isTrue();
    lock.unlockRead();
    lock.unlockWrite();
    // Still write locked.
    assertThat(awaitReadLock(lock)).isFalse();
    lock.unlockWrite();

    assertThat(awaitReadLock(lock)).isTrue();
    assertThat(awaitWriteLock(lock)).isTrue();
  }

  @Test(timeout = 30_000)
  public void unmatchedUnlock_throws() {
    var lock = new FineLock();

    assertThrows(IllegalMonitorStateException.class, lock::unlockRead);
    assertThrows(IllegalMonitorStateException.class, lock::unlockWrite);
  }

  @Test(timeout = 30_000)
  public void interruptedWriter_releasesWriterGate() throws Exception {
    var lock = new FineLock();

    // Interrupt a writer that is waiting for the readers to drain.
    lock.lockReadInterruptibly();
    Thread blockedByReader = startBlockedWriter(lock);
    blockedByReader.interrupt();
    blockedByReader.join(TIMEOUT.toMillis());
    assertThat(blockedByReader.isAlive()).isFalse();
    lock.unlockRead();
    assertThat(awaitWriteLock(lock)).isTrue();

    // Interrupt a writer that is waiting for another writer.
    lock.lockWriteInterruptibly();
    Thread blockedByWriter = startBlockedWriter(lock);
    blockedByWriter.interrupt();
    blockedByWriter.join(TIMEOUT.toMillis());
    assertThat(blockedByWriter.isAlive()).isFalse();
    lock.unlockWrite();

    assertThat(awaitWriteLock(lock)).isTrue();
  }

  @Test(timeout = 60_000)
  public void underContention_maintainsMutualExclusion() throws Exception {
    var lock = new FineLock();
    var writers = new AtomicInteger();
    var readers = new AtomicInteger();
    var violations = new AtomicInteger();

    for (int i = 0; i < 16; i++) {
      int seed = i;
      startThread(
          "contender" + i,
          () -> {
            var random = new Random(seed);
            for (int j = 0; j < 20_000; j++) {
              if (random.nextInt(8) == 0) {
                lock.lockWriteInterruptibly();
                if (writers.incrementAndGet() != 1 || readers.get() != 0) {
                  violations.incrementAndGet();
                }
                if (writers.decrementAndGet() != 0) {
                  violations.incrementAndGet();
                }
                lock.unlockWrite();
              } else {
                lock.lockReadInterruptibly();
                readers.incrementAndGet();
                if (writers.get() != 0) {
                  violations.incrementAndGet();
                }
                readers.decrementAndGet();
                lock.unlockRead();
              }
            }
          });
    }
    for (Thread thread : threads) {
      thread.join(TIMEOUT.toMillis());
      assertThat(thread.isAlive()).isFalse();
    }

    assertThat(violations.get()).isEqualTo(0);
    assertThat(readers.get()).isEqualTo(0);
    assertThat(writers.get()).isEqualTo(0);
  }

  @Test(timeout = 30_000)
  public void unreachableLock_isGarbageCollected() {
    WeakReference<FineLock> reference = new WeakReference<>(new FineLock());

    GcFinalization.awaitClear(reference);
  }

  /** Starts a writer that is expected to block and returns once it does. */
  private Thread startBlockedWriter(FineLock lock) throws InterruptedException {
    var started = new CountDownLatch(1);
    Thread writer =
        startThread(
            "writer",
            () -> {
              started.countDown();
              lock.lockWriteInterruptibly();
              lock.unlockWrite();
            });
    started.await();
    awaitBlocked(writer);
    return writer;
  }

  /**
   * Starts a reader that is expected to block and returns a latch counted down once it
   * unblocks.
   */
  private CountDownLatch startBlockedReader(FineLock lock) throws InterruptedException {
    var started = new CountDownLatch(1);
    var acquired = new CountDownLatch(1);
    Thread reader =
        startThread(
            "blockedReader",
            () -> {
              started.countDown();
              lock.lockReadInterruptibly();
              acquired.countDown();
              lock.unlockRead();
            });
    started.await();
    awaitBlocked(reader);
    assertThat(acquired.getCount()).isEqualTo(1);
    return acquired;
  }

  /** Returns whether a fresh thread can acquire the read lock. */
  private boolean awaitReadLock(FineLock lock) throws InterruptedException {
    return awaitLock(
        () -> {
          lock.lockReadInterruptibly();
          lock.unlockRead();
          return true;
        });
  }

  /** Returns whether a fresh thread can acquire the write lock. */
  private boolean awaitWriteLock(FineLock lock) throws InterruptedException {
    return awaitLock(
        () -> {
          lock.lockWriteInterruptibly();
          lock.unlockWrite();
          return true;
        });
  }

  private boolean awaitLock(InterruptibleSupplier acquisition) throws InterruptedException {
    var acquired = new CountDownLatch(1);
    Thread thread =
        startThread(
            "acquirer",
            () -> {
              if (acquisition.get()) {
                acquired.countDown();
              }
            });
    boolean result = acquired.await(1, SECONDS);
    thread.interrupt();
    thread.join(TIMEOUT.toMillis());
    return result;
  }

  /** A reader that barges in as soon as the write lock is free and holds on to the read lock. */
  private static final class BargingReader implements InterruptibleRunnable {
    private final FineLock lock;
    private final CountDownLatch acquired = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BargingReader(FineLock lock) {
      this.lock = lock;
    }

    @Override
    public void run() throws InterruptedException {
      while (!Thread.currentThread().isInterrupted()) {
        if (lock.tryLockRead()) {
          acquired.countDown();
          try {
            release.await();
          } finally {
            lock.unlockRead();
          }
          return;
        }
        Thread.onSpinWait();
      }
    }
  }

  private interface InterruptibleRunnable {
    void run() throws InterruptedException;
  }

  private interface InterruptibleSupplier {
    boolean get() throws InterruptedException;
  }

  private Thread startThread(String name, InterruptibleRunnable runnable) {
    Thread thread =
        new Thread(
            () -> {
              try {
                runnable.run();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            name);
    threads.add(thread);
    thread.start();
    return thread;
  }

  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (thread.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isNotEqualTo(Thread.State.RUNNABLE);
  }
}
