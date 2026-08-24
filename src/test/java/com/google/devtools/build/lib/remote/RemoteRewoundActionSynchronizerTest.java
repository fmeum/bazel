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

import com.google.common.testing.GcFinalization;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReadWriteLock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteRewoundActionSynchronizer}. */
@RunWith(JUnit4.class)
public final class RemoteRewoundActionSynchronizerTest {
  @Test(timeout = 30_000)
  public void fineReadLock_bargesAheadOfQueuedWriter() throws Exception {
    ReadWriteLock lock = RemoteRewoundActionSynchronizer.newFineLock();
    Lock readLock = lock.readLock();
    Lock writeLock = lock.writeLock();
    readLock.lock();
    var writerStarted = new CountDownLatch(1);
    Thread writer =
        new Thread(
            () -> {
              writerStarted.countDown();
              try {
                writeLock.lockInterruptibly();
                writeLock.unlock();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    writer.start();
    Thread reader = null;
    try {
      writerStarted.await();
      waitUntilBlocked(writer);

      var readerAcquired = new CountDownLatch(1);
      reader =
          new Thread(
              () -> {
                try {
                  RemoteRewoundActionSynchronizer.lockFineReadLockInterruptibly(readLock);
                  readerAcquired.countDown();
                  readLock.unlock();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      reader.start();

      assertThat(readerAcquired.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      readLock.unlock();
      if (reader != null) {
        reader.interrupt();
        reader.join();
      }
      writer.interrupt();
      writer.join();
    }
  }

  @Test
  public void fineReadLockView_keepsWeaklyCachedValueAlive() {
    LockAndWeakReference lockAndReference = newLockViewAndWeakReference(/* write= */ false);

    GcFinalization.awaitFullGc();

    assertThat(lockAndReference.reference().get()).isNotNull();
    Reference.reachabilityFence(lockAndReference.view());
  }

  @Test
  public void fineWriteLockView_keepsWeaklyCachedValueAlive() {
    LockAndWeakReference lockAndReference = newLockViewAndWeakReference(/* write= */ true);

    GcFinalization.awaitFullGc();

    assertThat(lockAndReference.reference().get()).isNotNull();
    Reference.reachabilityFence(lockAndReference.view());
  }

  @Test(timeout = 30_000)
  public void fineLock_withoutReachableView_isGarbageCollected() {
    WeakReference<ReadWriteLock> reference = newFineLockWeakReference();

    GcFinalization.awaitClear(reference);
  }

  private static void waitUntilBlocked(Thread thread) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (LockSupport.getBlocker(thread) == null && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(LockSupport.getBlocker(thread)).isNotNull();
  }

  private static LockAndWeakReference newLockViewAndWeakReference(boolean write) {
    ReadWriteLock lock = RemoteRewoundActionSynchronizer.newFineLock();
    return new LockAndWeakReference(
        write ? lock.writeLock() : lock.readLock(), new WeakReference<>(lock));
  }

  private static WeakReference<ReadWriteLock> newFineLockWeakReference() {
    return new WeakReference<>(RemoteRewoundActionSynchronizer.newFineLock());
  }

  private record LockAndWeakReference(Lock view, WeakReference<ReadWriteLock> reference) {}
}
