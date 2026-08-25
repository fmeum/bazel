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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.vfs.OutputService.RewoundActionSynchronizer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

/**
 * A {@link RewoundActionSynchronizer} implementation for Bazel's remote filesystem, which is backed
 * by actual files on disk and requires synchronization to ensure that action outputs aren't deleted
 * while they are being read.
 */
final class RemoteRewoundActionSynchronizer implements RewoundActionSynchronizer {
  /** A task with a cancellation callback. */
  public interface Cancellable {
    void cancel() throws InterruptedException;
  }

  private final RemoteActionInputFetcher actionInputFetcher;
  private final ConcurrentHashMap<ActionLookupData, Cancellable> outputUploadTasks =
      new ConcurrentHashMap<>();

  // A single coarse lock is used to synchronize rewound actions (writers) and both rewound and
  // non-rewound actions (readers) as long as no rewound action has attempted to prepare for its
  // execution.
  // This ensures high throughput and low memory footprint for the common case of no rewound
  // actions. In this case, there won't be any writers and the performance characteristics of a
  // ReentrantReadWriteLock are comparable to that of an atomic counter. A StampedLock would not be
  // a good fit as its performance regresses with 127 or more concurrent readers.
  // Note that it wouldn't be correct to only start using this lock once an action is rewound,
  // because a non-rewound action consuming its non-lost outputs could have already started
  // executing.
  @Nullable private volatile ReadWriteLock coarseLock = new ReentrantReadWriteLock();

  // A fine-grained lock structure that is switched to when the first rewound action attempts to
  // prepare for its execution. This structure is used to ensure that rewound actions do not
  // delete their outputs while they are being read by other actions, while still allowing
  // rewound actions and non-rewound actions to run concurrently (i.e., not force the equivalent
  // of --jobs=1 for as long as a rewound action is running, as the coarse lock would).
  // A rewound action will acquire the write locks on the keys guarding its outputs (see
  // outputKeysFor) before it prepares for execution, while any action will acquire a read lock on
  // the key guarding each of its inputs (see inputKeyFor) before it starts executing.
  // The values of this cache are weakly referenced to ensure that locks are cleaned up when they
  // are no longer needed. Callers hold on to the FineLock itself rather than to a view of it, so a
  // lock can't be collected and replaced while it is held.
  @Nullable private volatile LoadingCache<ActionLookupData, FineLock> fineLocks;

  public RemoteRewoundActionSynchronizer(RemoteActionInputFetcher actionInputFetcher) {
    this.actionInputFetcher = actionInputFetcher;
  }

  /*
  Proof of deadlock freedom:

  As long as the coarse lock is used, there can't be any deadlock because there is only a single
  read-write lock.

  Now assume that there is a deadlock while the fine locks are used. First, note that the logic in
  ImportantOutputHandler that is guarded by enterProcessOutputsAndGetLostArtifacts does not block
  on any (rewound or non-rewound) action executions while it holds read locks and can thus be
  ignored in the following. Consider the directed labeled "wait-for" graph defined as follows:

  * Nodes are given by the currently active Skyframe action execution threads, each of which is
    identified with the action it is (or will be) executing. Actions are in one-to-one
    correspondence with the ActionLookupData that is used as the key in the fine locks map.
  * For each pair of actions A_1 and A_2, there is an edge from A_1 to A_2 labeled with XY(K)
    if A_1 is waiting for the X lock of the key K and A_2 currently holds the Y lock of K, where X
    and Y are either R (for read) or W (for write). The resulting graph may have parallel edges
    with distinct labels.

  Say that an action A "covers" a key K if A is the action identified by K, or if K identifies an
  ActionTemplate and A is one of its expanded actions. By construction of outputKeysFor, the
  write locks of K are only ever acquired by actions covering K. An expanded action covers two
  keys, its template's and its own; every other action covers exactly one.

  Let C be any directed cycle in the graph representing a deadlock, let A_1 -[XY(K)]-> A_2 be an
  edge in C and consider the following cases for the pair XY:

  * RR: A thread waiting for a fine read lock is only ever waiting for the writers of that key to
        finish, see FineLock. If another thread holds the read lock, the key has no writers, so
        this case doesn't occur.
  * WW: The writers of a key don't exclude each other, see FineLock, so this case doesn't occur.
  * WR: A_1 attempts to acquire a write lock, which only happens when A_1 is a rewound action about
        to prepare for its (re-)execution. If A_1 is waiting for the first write lock it acquires
        in enterActionPreparation, it doesn't hold any locks: enterActionExecution hasn't been
        called yet in SkyframeActionExecutor, the write locks are the first locks it acquires, and
        all past executions of the action have released all their locks due to use of
        try-with-resources. This means that A_1 can't have any incoming edges in the wait-for
        graph, which is a contradiction to the assumption that it is contained in the directed
        cycle C. Otherwise, A_1 is a rewound expanded action waiting for the write lock on its own
        key while already holding the write lock on its template's key (see outputKeysFor), and
        A_2 holds the read lock on A_1's own key. By construction of inputKeyFor, only consumers
        of individual files generated by A_1 acquire that read lock, and such consumers are
        expanded actions of the same template, so A_2 covers the same key as A_1 and depends on
        A_1 (**).

   We conclude that every edge of C is either an RW(K) edge, or a WR(K) edge of the second kind
   above with K the own key of the waiting expanded action, whose endpoints both cover the same
   template key, see (**).

   By construction of inputKeyFor, the waiting action of an RW(K) edge waits because it has an
   input guarded by K: an output of the single action identified by K, the whole tree artifact
   declared by the ActionTemplate identified by K, or an individual file generated by the
   expanded action identified by K if K is an own key. In each case the waiting action depends on
   all actions covering K (*): a whole tree depends on every expanded action of its template, and
   an own key is covered only by its expanded action. Moreover, if K is an own key, the waiting
   action is an expanded action of the same template, since individual files are only ever
   consumed within their expansion - so both endpoints of the edge cover the same template key.

   Now collapse all nodes of C covering the same template key (or the same key of a non-expanded
   action) into a single node. Every surviving edge is an RW(K) edge with K a template key or the
   key of a non-expanded action, whose waiting action depends on all actions covering K - in
   particular on the action at which C exits the collapsed node the edge points to. If any edge
   survives, chaining these dependencies around the collapsed cycle yields a directed cycle in
   the action graph, which is a contradiction since Bazel disallows dependency cycles.

   Otherwise, C lies entirely within the actions covering a single template key. A WR(K) edge of
   C starts at a writer that holds only the write lock of the template key (own keys are acquired
   last, see outputKeysFor). Its incoming edges could only come from readers waiting on that
   template key - but a sibling never acquires the read lock of its own template key, since no
   expanded action can depend on the tree it contributes to, so the writer has no incoming edges
   within the class and C contains no WR edges at all. C thus consists only of RW edges on own
   keys, each of which points from an action to a sibling it depends on, again yielding a cycle
   in the action graph and a contradiction.

   Notes:
   * The proof would not go through at (*) if fineLocks were replaced by a Striped lock structure
     with a fixed number of locks. In fact, this gives rise to a deadlock if the number of stripes
     is at least 2, but low enough that distinct generating actions hash to the same stripe.
   * A rewound expanded action acquires two write locks, but in a fixed order: the template key
     strictly before its own key, and it never holds any lock of another action's keys while
     waiting for a write lock. A rewound action holding one write lock while waiting for the
     write lock of an unrelated key could deadlock with a reader acquiring read locks on the same
     two keys in the opposite order, and readers acquire their locks in an arbitrary order.
   * A rewound action must skip the read locks of the keys guarding its own outputs, which it
     already holds the write locks of: a reader waits for all writers of the key including
     itself. With inputKeyFor mapping same-expansion inputs to the keys of their generating
     sibling actions, no input key of an action should ever equal one of its own output keys, but
     the filter in inputKeysFor is kept as cheap insurance since acquiring such a read lock would
     self-deadlock.
   */

  @Override
  public SilentCloseable enterActionPreparation(Action action, boolean wasRewound)
      throws InterruptedException {
    // Skyframe schedules non-rewound actions such that they never run concurrently with actions
    // that consume their outputs.
    if (!wasRewound) {
      return () -> {};
    }
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.ACTION_LOCK, "action.enterActionPreparation")) {
      return enterActionPreparationForRewinding(action);
    }
  }

  private SilentCloseable enterActionPreparationForRewinding(Action action)
      throws InterruptedException {
    var localCoarseLock = coarseLock;
    if (localCoarseLock != null) {
      // This is the first time a rewound action has attempted to prepare for its execution.
      // Switch to using the fine locks under the protection of the coarse write lock.
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.ACTION_LOCK, "action.prepareFirstRewinding")) {
        localCoarseLock.writeLock().lockInterruptibly();
      }
      try {
        // Check again under the lock to avoid a race between multiple rewound actions attempting
        // to prepare for execution at the same time.
        if (fineLocks == null) {
          fineLocks =
              Caffeine.newBuilder()
                  .weakValues()
                  // TODO: Investigate the effect of fair locks on build wall time.
                  .build((ActionLookupData unused) -> new FineLock());
          // Must be assigned after fineLocks as lockArtifactsForConsumption relies on a null
          // coarseLock implying a non-null fineLocks.
          coarseLock = null;
        }
      } finally {
        localCoarseLock.writeLock().unlock();
      }
    }

    var outputKey = outputKeyFor(action);
    var actionKey = actionKeyFor(action);
    var templateLock = fineLocks.get(outputKey);
    // An expanded action's individual outputs are additionally guarded by its own key, which
    // consumers of those outputs acquire the read lock of (see inputKeyFor). The write lock on it
    // ensures that they aren't invalidated below while such a consumer is still reading them. The
    // key of the template must be locked first, see the deadlock proof above.
    FineLock ownLock = actionKey.equals(outputKey) ? null : fineLocks.get(actionKey);
    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.ACTION_LOCK, "action.awaitRewoundActionConsumers")) {
      templateLock.lockWriteInterruptibly();
      if (ownLock != null) {
        try {
          ownLock.lockWriteInterruptibly();
        } catch (Throwable t) {
          templateLock.unlockWrite();
          throw t;
        }
      }
    }
    var unlock = unlockWriteOnce(templateLock, ownLock);
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.INFO, "action.prepareOutputsForRewinding")) {
      prepareOutputsForRewinding(action);
    } catch (Throwable t) {
      unlock.close();
      throw t;
    }
    return unlock;
  }

  /**
   * Returns a {@link SilentCloseable} that releases the given write locks exactly once and throws
   * on a repeated call instead of silently releasing another thread's hold.
   */
  private static SilentCloseable unlockWriteOnce(
      FineLock templateLock, @Nullable FineLock ownLock) {
    var released = new AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) {
        throw new IllegalMonitorStateException("write locks released more than once");
      }
      if (ownLock != null) {
        ownLock.unlockWrite();
      }
      templateLock.unlockWrite();
    };
  }

  /**
   * Cancels all async tasks that operate on the action's outputs and resets any cached data about
   * their prefetching state.
   */
  private void prepareOutputsForRewinding(Action action) throws InterruptedException {
    Cancellable task = outputUploadTasks.remove(actionKeyFor(action));
    if (task != null) {
      task.cancel();
    }
    actionInputFetcher.handleRewoundActionOutputs(action.getOutputs());
  }

  @Override
  public SilentCloseable enterActionExecution(
      Action action, boolean wasRewound, InputMetadataProvider metadataProvider)
      throws InterruptedException {
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.ACTION_LOCK, "action.enterActionExecution")) {
      return lockArtifactsForConsumption(
          action.getInputs().toList(),
          metadataProvider,
          // A rewound action already holds the write locks on the keys guarding its outputs and a
          // reader waits for all writers of a key, including itself, so it must never acquire a
          // read lock on them. Its inputs from the same template expansion are guarded by the
          // keys of their generating actions, which are distinct from its own (see inputKeyFor).
          wasRewound ? outputKeysFor(action) : ImmutableSet.of());
    }
  }

  /**
   * Guards a call to {@link
   * com.google.devtools.build.lib.remote.RemoteImportantOutputHandler#processOutputsAndGetLostArtifacts}.
   */
  public SilentCloseable enterProcessOutputsAndGetLostArtifacts(
      Iterable<Artifact> importantOutputs, InputMetadataProvider fullMetadataProvider)
      throws InterruptedException {
    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.ACTION_LOCK, "action.enterProcessOutputsAndGetLostArtifacts")) {
      return lockArtifactsForConsumption(
          importantOutputs, fullMetadataProvider, /* writeLockedKeys= */ ImmutableSet.of());
    }
  }

  /**
   * Registers a cancellation callback for an upload of action outputs that may still be running
   * after the action has completed.
   */
  public void registerOutputUploadTask(ActionExecutionMetadata action, Cancellable task) {
    // We don't expect to have multiple output upload tasks for the same action registered at the
    // same time.
    outputUploadTasks.merge(
        actionKeyFor(action),
        task,
        (oldTask, newTask) -> {
          throw new IllegalStateException(
              "Attempted to register multiple output upload tasks for %s: %s and %s"
                  .formatted(action, oldTask, newTask));
        });
  }

  private SilentCloseable lockArtifactsForConsumption(
      Iterable<Artifact> artifacts,
      InputMetadataProvider metadataProvider,
      Set<ActionLookupData> writeLockedKeys)
      throws InterruptedException {
    var localCoarseLock = coarseLock;
    if (localCoarseLock != null) {
      // Common case for builds without any rewound actions: acquire the single lock that is never
      // acquired by a writer.
      localCoarseLock.readLock().lockInterruptibly();
    }
    // Read the fine locks after acquiring the coarse lock to allow the fine locks to be inflated
    // lazily.
    var localFineLocks = fineLocks;
    if (localFineLocks == null) {
      // Continuation of the common case for builds without any rewound actions: the fine locks
      // have not been inflated.
      return localCoarseLock.readLock()::unlock;
    }

    // At this point, there has been at least one rewound action that has inflated the fine locks.
    // We need to switch to it.
    if (localCoarseLock != null) {
      localCoarseLock.readLock().unlock();
    }
    var allFineLocks =
        localFineLocks.getAll(inputKeysFor(artifacts, metadataProvider, writeLockedKeys)).values();
    var locksToUnlockBuilder = ImmutableList.<FineLock>builderWithExpectedSize(allFineLocks.size());
    try {
      for (var fineLock : allFineLocks) {
        fineLock.lockReadInterruptibly();
        locksToUnlockBuilder.add(fineLock);
      }
    } catch (Throwable e) {
      for (var fineLock : locksToUnlockBuilder.build()) {
        fineLock.unlockRead();
      }
      throw e;
    }
    var locksToUnlock = locksToUnlockBuilder.build();
    var released = new AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) {
        throw new IllegalMonitorStateException("read locks released more than once");
      }
      locksToUnlock.forEach(FineLock::unlockRead);
    };
  }

  /**
   * The value of the {@link RemoteRewoundActionSynchronizer#fineLocks} cache: a lock that admits
   * any number of readers or any number of writers of a key, but never both at the same time.
   *
   * <p>Writers deliberately don't exclude each other. The actions covering a key are either the
   * single action identified by it, or the expanded actions of the {@link
   * com.google.devtools.build.lib.actions.ActionTemplate} identified by it (see lockKeyFor).
   * Expanded actions generate disjoint outputs under the tree artifact declared by the template, so
   * writers of a key only ever conflict with its readers, never with each other's outputs. A
   * writer that consumes individual files generated by a sibling of the same expansion holds the
   * read lock on that sibling's own key while executing (see inputKeyFor), so a later rewind of
   * the sibling waits for it like for any other consumer instead of invalidating the files it is
   * reading. Excluding writers from each other would serialize the re-execution of an entire
   * template expansion, because the write lock is held across {@code Action#execute} and rewinding
   * a lost tree artifact rewinds every expanded action at once (see
   * ActionRewindStrategy#getActionExecutionDeps).
   *
   * <p>Readers wait only for the writers of the key to finish and writers wait only for its readers
   * to finish, on separate wait sets. A thread waiting for the read lock is therefore never waiting
   * for another reader, which is what the RR case of the deadlock proof above relies on. Neither a
   * {@link java.util.concurrent.locks.ReentrantReadWriteLock} nor a {@link
   * java.util.concurrent.locks.StampedLock} provides this: they order readers and writers in a
   * single queue, so a reader can end up behind a waiting writer that is in turn blocked by an
   * unrelated reader.
   *
   * <p>Both sides are reentrant by counting, with one exception: <b>a thread that holds the write
   * lock must not acquire the read lock of the same key</b>, since readers wait for all writers
   * including the current thread. This is why enterActionExecution skips the keys guarding a
   * rewound action's own outputs.
   *
   * <p>Neither side is fair: a steady stream of readers can starve writers and vice versa. Deadlock
   * freedom doesn't depend on either making progress, and both are bounded in practice by the
   * actions consuming a key's outputs and by the size of a template expansion.
   *
   * <p>Callers hold on to this object rather than to a view of it, so it can't be collected and
   * replaced by the weak {@link RemoteRewoundActionSynchronizer#fineLocks} cache while it is
   * locked.
   */
  @VisibleForTesting
  static final class FineLock {
    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition noWriters = mutex.newCondition();
    private final Condition noReaders = mutex.newCondition();

    // Number of actions currently consuming the outputs guarded by this key.
    private int readers;
    // Number of rewound actions currently preparing for or performing their re-execution.
    private int writers;

    void lockReadInterruptibly() throws InterruptedException {
      mutex.lockInterruptibly();
      try {
        while (writers > 0) {
          noWriters.await();
        }
        readers++;
      } finally {
        mutex.unlock();
      }
    }

    boolean tryLockRead() {
      if (!mutex.tryLock()) {
        return false;
      }
      try {
        if (writers > 0) {
          return false;
        }
        readers++;
        return true;
      } finally {
        mutex.unlock();
      }
    }

    void unlockRead() {
      mutex.lock();
      try {
        if (readers == 0) {
          throw new IllegalMonitorStateException();
        }
        if (--readers == 0) {
          noReaders.signalAll();
        }
      } finally {
        mutex.unlock();
      }
    }

    void lockWriteInterruptibly() throws InterruptedException {
      mutex.lockInterruptibly();
      try {
        while (readers > 0) {
          noReaders.await();
        }
        writers++;
      } finally {
        mutex.unlock();
      }
    }

    void unlockWrite() {
      mutex.lock();
      try {
        if (writers == 0) {
          throw new IllegalMonitorStateException();
        }
        if (--writers == 0) {
          noWriters.signalAll();
        }
      } finally {
        mutex.unlock();
      }
    }

    @Override
    public String toString() {
      mutex.lock();
      try {
        return "FineLock[readers=%d, writers=%d]".formatted(readers, writers);
      } finally {
        mutex.unlock();
      }
    }
  }

  private static Iterable<ActionLookupData> inputKeysFor(
      Iterable<Artifact> artifacts,
      InputMetadataProvider metadataProvider,
      Set<ActionLookupData> writeLockedKeys) {
    var allArtifacts =
        Iterables.concat(
            artifacts,
            Iterables.concat(
                Iterables.transform(
                    metadataProvider.getRunfilesTrees(),
                    runfilesTree -> runfilesTree.getArtifacts().toList())));
    var result =
        Iterables.transform(
            Iterables.filter(allArtifacts, artifact -> artifact instanceof DerivedArtifact),
            artifact -> inputKeyFor((DerivedArtifact) artifact));
    if (writeLockedKeys.isEmpty()) {
      return result;
    }
    return Iterables.filter(result, key -> !writeLockedKeys.contains(key));
  }

  /** Returns the key that uniquely identifies the given action. */
  private static ActionLookupData actionKeyFor(ActionExecutionMetadata action) {
    return ((DerivedArtifact) action.getPrimaryOutput()).getGeneratingActionKey();
  }

  /**
   * Returns the key of the lock that guards the given artifact when consumed as an input, which is
   * its own generating action key.
   *
   * <p>For a tree artifact declared by an {@link
   * com.google.devtools.build.lib.actions.ActionTemplate} this is the key of the template, whose
   * write lock every rewound expanded action holds: consumers of the whole tree read the outputs
   * of every expanded action. For an individual file in such a tree it is the key of the expanded
   * action generating it, whose write lock only that action holds when rewound: individual files
   * are only ever consumed by other actions of the same expansion, and mapping them to the key of
   * the template instead would either self-deadlock (a sibling reader waiting on a template key
   * whose writers include itself) or, if skipped, leave the files unguarded against a concurrent
   * rewind of the generating action.
   */
  private static ActionLookupData inputKeyFor(DerivedArtifact artifact) {
    return artifact.getGeneratingActionKey();
  }

  /**
   * Returns the key of the outermost tree artifact containing the given artifact, or its own
   * generating action key if it isn't contained in one. For an output of an {@link
   * com.google.devtools.build.lib.actions.ActionTemplate} expansion this is the key of the
   * template.
   */
  private static ActionLookupData lockKeyFor(DerivedArtifact artifact) {
    var outermost = artifact;
    for (var parent = artifact.getParent(); parent != null; parent = parent.getParent()) {
      outermost = parent;
    }
    return outermost.getGeneratingActionKey();
  }

  /**
   * Returns the key of the lock that guards the outputs of the given action as a whole, which
   * consumers of its (tree) outputs acquire the read lock of.
   */
  private static ActionLookupData outputKeyFor(Action action) {
    return lockKeyFor((DerivedArtifact) action.getPrimaryOutput());
  }

  /**
   * Returns all keys of locks that guard outputs of the given action, in the order in which a
   * rewound action must acquire their write locks: the key guarding its outputs as a whole (the
   * template key for an expanded action) and, for an expanded action, additionally its own key,
   * which guards its individual outputs (see inputKeyFor).
   */
  private static ImmutableSet<ActionLookupData> outputKeysFor(Action action) {
    return ImmutableSet.of(outputKeyFor(action), actionKeyFor(action));
  }
}
