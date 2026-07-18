// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.Uninterruptibles.awaitTerminationUninterruptibly;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.buildtool.SymlinkForest;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSet.Node;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetReadyForSymlinkPlanting;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An implementation of PackageRoots that allows incremental updating of the packageRootsMap.
 *
 * <p>This class is also in charge of planting the necessary symlinks.
 */
public class IncrementalPackageRoots implements PackageRoots {
  // This work is I/O bound: set the parallelism to something similar to the default number of
  // loading threads.
  private static final int SYMLINK_PLANTING_PARALLELISM = 200;

  // Packages in the main repository all share the same root (singleSourceRoot), so as a memory
  // optimization we only keep track of the roots of external repositories here.
  private final Map<RepositoryName, Root> threadSafeExternalRepoRootsMap;

  @GuardedBy("stateLock")
  @Nullable
  private Set<NestedSet.Node> doneRepoSets = Sets.newConcurrentHashSet();

  // Only tracks the symlinks lazily planted after the first eager planting wave.
  @GuardedBy("stateLock")
  @Nullable
  private Set<Path> lazilyPlantedSymlinks = Sets.newConcurrentHashSet();

  private final ListeningExecutorService symlinkPlantingPool;
  private final Object stateLock = new Object();
  private final Path execroot;
  private final Root singleSourceRoot;
  private final String prefix;

  private final IgnoredSubdirectories ignoredPaths;
  private final boolean useSiblingRepositoryLayout;

  private final boolean allowExternalRepositories;
  @Nullable private EventBus eventBus;

  private IncrementalPackageRoots(
      Path execroot,
      Root singleSourceRoot,
      EventBus eventBus,
      String prefix,
      IgnoredSubdirectories ignoredPaths,
      boolean useSiblingRepositoryLayout,
      boolean allowExternalRepositories) {
    this.threadSafeExternalRepoRootsMap = new ConcurrentHashMap<>();
    this.execroot = execroot;
    this.singleSourceRoot = singleSourceRoot;
    this.prefix = prefix;
    this.ignoredPaths = ignoredPaths;
    this.eventBus = eventBus;
    this.useSiblingRepositoryLayout = useSiblingRepositoryLayout;
    this.allowExternalRepositories = allowExternalRepositories;
    this.symlinkPlantingPool =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                SYMLINK_PLANTING_PARALLELISM,
                new ThreadFactoryBuilder().setNameFormat("Non-eager Symlink planter %d").build()));
  }

  public static IncrementalPackageRoots createAndRegisterToEventBus(
      Path execroot,
      Root singleSourceRoot,
      EventBus eventBus,
      String prefix,
      IgnoredSubdirectories ignoredSubdirectories,
      boolean useSiblingRepositoryLayout,
      boolean allowExternalRepositories) {
    IncrementalPackageRoots incrementalPackageRoots =
        new IncrementalPackageRoots(
            execroot,
            singleSourceRoot,
            eventBus,
            prefix,
            ignoredSubdirectories,
            useSiblingRepositoryLayout,
            allowExternalRepositories);
    eventBus.register(incrementalPackageRoots);
    return incrementalPackageRoots;
  }

  /**
   * Eagerly plant the symlinks to the directories under the single source root.
   *
   * <p>Only the symlinks to external repositories, whose set is discovered incrementally during
   * analysis, are planted lazily later on. If two directories under the source root differ only in
   * casing and the symlinks clash on a case-insensitive file system, this errors out eagerly.
   */
  public void eagerlyPlantSymlinksToSingleSourceRoot() throws AbruptExitException {
    try {
      SymlinkForest.eagerlyPlantSymlinkForestSinglePackagePath(
          execroot, singleSourceRoot.asPath(), prefix, ignoredPaths, useSiblingRepositoryLayout);
    } catch (IOException e) {
      throwAbruptExitException(e);
    }
  }

  /** There is currently no use case for this method, and it should not be called. */
  @Override
  public ImmutableMap<PackageIdentifier, Root> getPackageRootsMap() {
    throw new UnsupportedOperationException(
        "IncrementalPackageRoots does not provide the package roots map directly.");
  }

  @Override
  public PackageRootLookup getPackageRootLookup() {
    return packageId ->
        packageId.getRepository().isMain()
            ? singleSourceRoot
            : threadSafeExternalRepoRootsMap.get(packageId.getRepository());
  }

  // Intentionally don't allow concurrent events here to prevent a race condition between planting
  // a symlink and starting an action that requires that symlink. This race condition is possible
  // because of the various memoizations we use to avoid repeated work.
  @Subscribe
  public void lazilyPlantSymlinks(TopLevelTargetReadyForSymlinkPlanting event)
      throws AbruptExitException {
    if (!allowExternalRepositories) {
      return;
    }
    Set<NestedSet.Node> doneRepoSetsLocalRef;
    Set<Path> lazilyPlantedSymlinksLocalRef;
    // May still race with analysisFinished, hence the synchronization.
    synchronized (stateLock) {
      if (doneRepoSets == null || lazilyPlantedSymlinks == null) {
        return;
      }
      doneRepoSetsLocalRef = doneRepoSets;
      lazilyPlantedSymlinksLocalRef = lazilyPlantedSymlinks;
    }

    // Initial capacity: arbitrarily chosen.
    // This list doesn't need to be thread-safe, as items are added sequentially.
    List<ListenableFuture<Void>> futures = new ArrayList<>(128);
    recursiveRegisterAndPlantMissingSymlinks(
        event.transitiveReposForSymlinkPlanting(),
        doneRepoSetsLocalRef,
        lazilyPlantedSymlinksLocalRef,
        futures);

    // Now wait on the futures. After that, we can be sure that the symlinks have been planted.
    try {
      Futures.whenAllSucceed(futures).call(() -> null, directExecutor()).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // Bail
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AbruptExitException) {
        throw (AbruptExitException) e.getCause();
      }
      throw new IllegalStateException("Unexpected exception", e);
    }
  }

  @Subscribe
  public void analysisFinished(AnalysisPhaseCompleteEvent unused) {
    shutdown(false);
  }

  /**
   * Lazily plant the symlinks to the external repositories transitively loaded by a top level
   * target, which couldn't be planted in the initial eager planting wave.
   */
  private void recursiveRegisterAndPlantMissingSymlinks(
      NestedSet<Package.RepoMetadata> repos,
      Set<Node> doneRepoSetsRef,
      Set<Path> lazilyPlantedSymlinksRef,
      List<ListenableFuture<Void>> futures) {
    // Optimization to prune subsequent traversals.
    // A false negative does not affect correctness.
    if (!doneRepoSetsRef.add(repos.toNode())) {
      return;
    }

    synchronized (symlinkPlantingPool) {
      // Some other thread shut down the executor, exit now.
      if (symlinkPlantingPool.isShutdown()) {
        return;
      }
      for (Package.RepoMetadata repo : repos.getLeaves()) {
        if (repo.repoName().isMain()) {
          // Symlinks to the main repository's directories were already planted eagerly.
          continue;
        }
        futures.add(
            symlinkPlantingPool.submit(
                () -> plantSingleSymlinkForExternalRepo(repo, lazilyPlantedSymlinksRef)));
      }
    }
    for (NestedSet<Package.RepoMetadata> transitive : repos.getNonLeaves()) {
      recursiveRegisterAndPlantMissingSymlinks(
          transitive, doneRepoSetsRef, lazilyPlantedSymlinksRef, futures);
    }
  }

  private Void plantSingleSymlinkForExternalRepo(
      Package.RepoMetadata repo, Set<Path> lazilyPlantedSymlinksRef) throws AbruptExitException {
    try {
      threadSafeExternalRepoRootsMap.putIfAbsent(repo.repoName(), repo.sourceRoot());
      SymlinkForest.plantSingleSymlinkForExternalRepo(
          repo.repoName(),
          repo.sourceRoot().asPath(),
          execroot,
          useSiblingRepositoryLayout,
          lazilyPlantedSymlinksRef);
    } catch (IOException e) {
      throwAbruptExitException(e);
    }
    return null;
  }

  private static void throwAbruptExitException(Exception e) throws AbruptExitException {
    throw new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage("Failed to prepare the symlink forest: " + e)
                .setSymlinkForest(
                    FailureDetails.SymlinkForest.newBuilder()
                        .setCode(FailureDetails.SymlinkForest.Code.CREATION_FAILED))
                .build()),
        e);
  }

  public void shutdown() {
    shutdown(true);
  }

  /**
   * Drops the intermediate states and stop receiving new events.
   *
   * <p>This essentially makes this instance read-only. Should be called when and only when all
   * analysis work is done in the build to free up some memory.
   */
  private void shutdown(boolean now) {
    // This instance is retained after a build via ArtifactFactory, so it's important that we remove
    // the reference to the eventBus here for it to be GC'ed.
    if (eventBus != null) {
      eventBus.unregister(this);
      eventBus = null;
    }
    synchronized (stateLock) {
      doneRepoSets = null;
      lazilyPlantedSymlinks = null;
    }
    synchronized (symlinkPlantingPool) {
      if (!symlinkPlantingPool.isShutdown()) {
        if (now) {
          symlinkPlantingPool.shutdownNow();
        } else {
          symlinkPlantingPool.shutdown();
        }
        awaitTerminationUninterruptibly(symlinkPlantingPool);
      }
    }
  }
}
