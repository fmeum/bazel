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

  /**
   * The writers of a key are the actions of a single {@link
   * com.google.devtools.build.lib.actions.ActionTemplate} expansion, which generate disjoint
   * outputs. Rewinding a lost tree artifact rewinds all of them, so they must be able to
   * re-execute in parallel.
   */
  @Test(timeout = 30_000)
  public void writers_dontExcludeEachOther() throws Exception {
    var lock = new FineLock();
    int writerCount = 8;
    var allAcquired = new CountDownLatch(writerCount);
    var release = new CountDownLatch(1);

    for (int i = 0; i < writerCount; i++) {
      startThread(
          "writer" + i,
          () -> {
            lock.lockWriteInterruptibly();
            allAcquired.countDown();
            try {
              release.await();
            } finally {
              lock.unlockWrite();
            }
          });
    }

    // All of them hold the write lock at the same time.
    assertThat(allAcquired.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
    // ... and readers are excluded while any of them does.
    assertThat(awaitReadLock(lock)).isFalse();

    release.countDown();
    joinThreads();
    assertThat(awaitReadLock(lock)).isTrue();
  }

  /**
   * The RR case of the deadlock proof requires that a thread waiting for a read lock is only ever
   * waiting for the writers of that key, never for another reader.
   *
   * <p>This is the scenario that breaks on a {@link
   * java.util.concurrent.locks.ReentrantReadWriteLock}, whose single queue can leave a reader
   * behind a waiting writer that is itself blocked by a reader that barged in later.
   */
  @Test(timeout = 30_000)
  public void readers_dontBlockEachOther() throws Exception {
    for (int i = 0; i < 100; i++) {
      var lock = new FineLock();

      // A rewound action covering the key holds the write lock.
      lock.lockWriteInterruptibly();
      // A consumer of the key has to wait for it.
      var blockedReader = startBlockedReader(lock).acquired();
      // Another consumer of the key gets in as soon as the writer is done, and holds on.
      var bargingReader = new BargingReader(lock);
      startThread("bargingReader", bargingReader);
      // A second rewound action covering the same key wants to re-execute too.
      var secondWriter = startWriter(lock);

      lock.unlockWrite();

      assertThat(bargingReader.acquired.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
      // The blocked reader must not be held up by the reader that got in first, whether or not the
      // second writer is waiting behind it.
      assertThat(blockedReader.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
      bargingReader.release.countDown();
      assertThat(secondWriter.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();

      joinThreads();
    }
  }

  @Test(timeout = 30_000)
  public void writers_excludeReaders() throws Exception {
    var lock = new FineLock();

    lock.lockWriteInterruptibly();
    assertThat(awaitReadLock(lock)).isFalse();
    assertThat(lock.tryLockRead()).isFalse();

    lock.unlockWrite();
    assertThat(awaitReadLock(lock)).isTrue();
  }

  @Test(timeout = 30_000)
  public void writers_waitForReadersToDrain() throws Exception {
    var lock = new FineLock();

    lock.lockReadInterruptibly();
    lock.lockReadInterruptibly();
    var writer = startBlockedWriter(lock).acquired();

    lock.unlockRead();
    assertThat(writer.await(100, MILLISECONDS)).isFalse();

    lock.unlockRead();
    assertThat(writer.await(TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
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
    // A thread holding the write lock must not acquire the read lock: readers wait for all writers,
    // including the current thread. enterActionExecution relies on this by skipping the key.
    assertThat(lock.tryLockRead()).isFalse();
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
  public void interruptedAcquisition_leavesLockUsable() throws Exception {
    var lock = new FineLock();

    // Interrupt a writer waiting for the readers to drain.
    lock.lockReadInterruptibly();
    Thread writer = startBlockedWriter(lock).thread();
    writer.interrupt();
    writer.join(TIMEOUT.toMillis());
    assertThat(writer.isAlive()).isFalse();
    lock.unlockRead();
    assertThat(awaitWriteLock(lock)).isTrue();

    // Interrupt a reader waiting for the writers to finish.
    lock.lockWriteInterruptibly();
    Thread reader = startBlockedReader(lock).thread();
    reader.interrupt();
    reader.join(TIMEOUT.toMillis());
    assertThat(reader.isAlive()).isFalse();
    lock.unlockWrite();
    assertThat(awaitReadLock(lock)).isTrue();
  }

  @Test(timeout = 60_000)
  public void underContention_maintainsGroupExclusion() throws Exception {
    var lock = new FineLock();
    var writers = new AtomicInteger();
    var readers = new AtomicInteger();
    var violations = new AtomicInteger();
    var concurrentWriters = new AtomicInteger();

    for (int i = 0; i < 16; i++) {
      int seed = i;
      startThread(
          "contender" + i,
          () -> {
            var random = new Random(seed);
            for (int j = 0; j < 20_000; j++) {
              if (random.nextInt(4) == 0) {
                lock.lockWriteInterruptibly();
                int active = writers.incrementAndGet();
                concurrentWriters.accumulateAndGet(active, Math::max);
                if (readers.get() != 0) {
                  violations.incrementAndGet();
                }
                writers.decrementAndGet();
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
    // Writers really do run as a group rather than one at a time.
    assertThat(concurrentWriters.get()).isGreaterThan(1);
  }

  @Test(timeout = 30_000)
  public void unreachableLock_isGarbageCollected() {
    WeakReference<FineLock> reference = new WeakReference<>(new FineLock());

    GcFinalization.awaitClear(reference);
  }

  /** Starts a writer and returns a latch counted down once it has acquired and released. */
  private CountDownLatch startWriter(FineLock lock) {
    var done = new CountDownLatch(1);
    startThread(
        "writer",
        () -> {
          lock.lockWriteInterruptibly();
          lock.unlockWrite();
          done.countDown();
        });
    return done;
  }

  /** A thread parked on an acquisition, with a latch counted down once it gets through. */
  private record Blocked(Thread thread, CountDownLatch acquired) {}

  private Blocked startBlockedWriter(FineLock lock) throws InterruptedException {
    return startBlocked(
        "blockedWriter",
        lock,
        () -> {
          lock.lockWriteInterruptibly();
          lock.unlockWrite();
        });
  }

  private Blocked startBlockedReader(FineLock lock) throws InterruptedException {
    return startBlocked(
        "blockedReader",
        lock,
        () -> {
          lock.lockReadInterruptibly();
          lock.unlockRead();
        });
  }

  private Blocked startBlocked(String name, FineLock lock, InterruptibleRunnable acquisition)
      throws InterruptedException {
    var started = new CountDownLatch(1);
    var acquired = new CountDownLatch(1);
    Thread thread =
        startThread(
            name,
            () -> {
              started.countDown();
              acquisition.run();
              acquired.countDown();
            });
    started.await();
    awaitBlocked(thread);
    assertThat(acquired.getCount()).isEqualTo(1);
    return new Blocked(thread, acquired);
  }

  /** Returns whether a fresh thread can acquire the read lock within a short timeout. */
  private boolean awaitReadLock(FineLock lock) throws InterruptedException {
    return awaitLock(
        () -> {
          lock.lockReadInterruptibly();
          lock.unlockRead();
        });
  }

  /** Returns whether a fresh thread can acquire the write lock within a short timeout. */
  private boolean awaitWriteLock(FineLock lock) throws InterruptedException {
    return awaitLock(
        () -> {
          lock.lockWriteInterruptibly();
          lock.unlockWrite();
        });
  }

  private boolean awaitLock(InterruptibleRunnable acquisition) throws InterruptedException {
    var acquired = new CountDownLatch(1);
    Thread thread =
        startThread(
            "acquirer",
            () -> {
              acquisition.run();
              acquired.countDown();
            });
    boolean result = acquired.await(1, SECONDS);
    thread.interrupt();
    thread.join(TIMEOUT.toMillis());
    return result;
  }

  /** A reader that gets in as soon as the writers are done and holds on to the read lock. */
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
