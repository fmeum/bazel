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

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
import com.google.devtools.build.lib.buildtool.SymlinkForest.SymlinkPlantingException;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSet.Node;
import com.google.devtools.build.lib.packages.RepositoryMetadata;
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
 * An implementation of PackageRoots that allows incremental updating of the package roots.
 *
 * <p>This class is also in charge of planting the necessary symlinks: the symlinks for the main
 * repository are planted eagerly at the start of the build (see {@link
 * #eagerlyPlantSymlinksToSingleSourceRoot}), while the symlinks for external repositories and for
 * main repository top-level directories that could not be planted eagerly are planted lazily as the
 * transitive repositories and top-level directories of top-level targets become known.
 */
public class IncrementalPackageRoots implements PackageRoots {
  // This work is I/O bound: set the parallelism to something similar to the default number of
  // loading threads.
  private static final int SYMLINK_PLANTING_PARALLELISM = 200;

  // We only keep track of external repos here as a memory optimization: packages belonging to the
  // main repository all share the same root, which is singleSourceRoot.
  private final Map<RepositoryName, Root> threadSafeExternalRepoRootsMap;

  @GuardedBy("stateLock")
  @Nullable
  private Set<NestedSet.Node> doneNodes = Sets.newConcurrentHashSet();

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

  private final boolean allowExternalRepositories;
  @Nullable private EventBus eventBus;
  @Nullable private final PackageRootLookup fallbackPackageRootLookup;

  // "maybe" because some conflicts in a case-insensitive FS may not be in a case-sensitive one.
  private ImmutableSet<String> maybeConflictingBaseNamesLowercase = ImmutableSet.of();

  private IncrementalPackageRoots(
      Path execroot,
      Root singleSourceRoot,
      EventBus eventBus,
      String prefix,
      IgnoredSubdirectories ignoredPaths,
      boolean allowExternalRepositories,
      @Nullable PackageRootLookup fallbackPackageRootLookup) {
    this.threadSafeExternalRepoRootsMap = new ConcurrentHashMap<>();
    this.execroot = execroot;
    this.singleSourceRoot = singleSourceRoot;
    this.prefix = prefix;
    this.ignoredPaths = ignoredPaths;
    this.eventBus = eventBus;
    this.allowExternalRepositories = allowExternalRepositories;
    this.fallbackPackageRootLookup = fallbackPackageRootLookup;
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
      boolean allowExternalRepositories) {
    return createAndRegisterToEventBus(
        execroot,
        singleSourceRoot,
        eventBus,
        prefix,
        ignoredSubdirectories,
        allowExternalRepositories,
        /* fallbackPackageRootLookup= */ null);
  }

  public static IncrementalPackageRoots createAndRegisterToEventBus(
      Path execroot,
      Root singleSourceRoot,
      EventBus eventBus,
      String prefix,
      IgnoredSubdirectories ignoredSubdirectories,
      boolean allowExternalRepositories,
      @Nullable PackageRootLookup fallbackPackageRootLookup) {
    IncrementalPackageRoots incrementalPackageRoots =
        new IncrementalPackageRoots(
            execroot,
            singleSourceRoot,
            eventBus,
            prefix,
            ignoredSubdirectories,
            allowExternalRepositories,
            fallbackPackageRootLookup);
    eventBus.register(incrementalPackageRoots);
    return incrementalPackageRoots;
  }

  /**
   * Eagerly plant the symlinks to the directories under the single source root. It's possible that
   * there's a conflict when we plant symlinks eagerly. In that case, we skip planting the
   * conflicting symlinks eagerly and wait until later in the build to see which of the conflicting
   * dir we actually need.
   *
   * <p>Eagerly planting the symlinks is much cheaper, hence we'd like to do it as much as possible
   * and only resort to the other route when really necessary.
   *
   * <p>Example: when we plant symlinks in a case-insensitive FS, "foo" and "Foo" would conflict:
   *
   * <pre>
   * /sourceroot
   *    ├── noclash
   *    ├── foo
   *    └── Foo
   *
   * /execroot
   *    ├── noclash -> /sourceroot/noclash
   *    ├── foo -> /sourceroot/foo
   *    └── Foo (clashing with foo in a case-insensitive FS)
   * </pre>
   *
   * We'd plant the symlink to "noclash" first, then wait to see whether we need "foo" or "Foo". If
   * we end up needing both, throw an error. See {@link #lazilyPlantSymlinks}.
   */
  public void eagerlyPlantSymlinksToSingleSourceRoot() throws AbruptExitException {
    try {
      maybeConflictingBaseNamesLowercase =
          SymlinkForest.eagerlyPlantSymlinkForestSinglePackagePath(
              execroot, singleSourceRoot.asPath(), prefix, ignoredPaths);
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

  /**
   * Returns a lookup function for package roots.
   *
   * <p>For packages in the main repository, the lookup unconditionally returns {@link
   * #singleSourceRoot}.
   *
   * <p>For external repositories, the lookup consults {@link #threadSafeExternalRepoRootsMap} and
   * falls back to {@code fallbackPackageRootLookup} if present. Both are required:
   *
   * <ol>
   *   <li>Why {@code fallbackPackageRootLookup} is necessary: External packages evaluated in
   *       earlier builds or retained across an analysis cache discard (such as toolchains whose
   *       transitive repositories were cleared to save heap memory) may not be included in the
   *       current build's {@link TopLevelTargetReadyForSymlinkPlanting} events. The fallback
   *       queries Skyframe's graph directly to recover roots for any completed {@code PackageValue}
   *       nodes.
   *   <li>Why {@link #threadSafeExternalRepoRootsMap} cannot be replaced:
   *       <ul>
   *         <li>In memory-saving modes (e.g. {@code --notrack_incremental_state} or node dropping),
   *             Skyframe may discard {@code PackageValue} nodes before or during execution. Storing
   *             roots during analysis symlink planting preserves them throughout execution.
   *         <li>It acts as a fast thread-safe cache for concurrent action execution (e.g. parallel
   *             C++ header discovery), avoiding repeated lookups against Skyframe's node graph.
   *             Successful fallback lookups are memoized into this map via {@code putIfAbsent}.
   *       </ul>
   * </ol>
   */
  @Override
  public PackageRootLookup getPackageRootLookup() {
    return packageId -> {
      RepositoryName repository = packageId.getRepository();
      if (repository.isMain()) {
        return singleSourceRoot;
      }
      Root root = threadSafeExternalRepoRootsMap.get(repository);
      if (root != null) {
        return root;
      }
      if (fallbackPackageRootLookup != null) {
        root = fallbackPackageRootLookup.getRootForPackage(packageId);
        if (root != null) {
          threadSafeExternalRepoRootsMap.putIfAbsent(repository, root);
          return root;
        }
      }
      return null;
    };
  }

  @Subscribe
  public void lazilyPlantSymlinks(TopLevelTargetReadyForSymlinkPlanting event)
      throws AbruptExitException {
    if (!allowExternalRepositories && maybeConflictingBaseNamesLowercase.isEmpty()) {
      return;
    }
    Set<NestedSet.Node> doneNodesLocalRef;
    Set<Path> lazilyPlantedSymlinksLocalRef;
    synchronized (stateLock) {
      if (doneNodes == null || lazilyPlantedSymlinks == null) {
        return;
      }
      doneNodesLocalRef = doneNodes;
      lazilyPlantedSymlinksLocalRef = lazilyPlantedSymlinks;
    }

    List<ListenableFuture<Void>> futures = new ArrayList<>(128);
    if (allowExternalRepositories) {
      recursiveRegisterAndPlantExternalRepoSymlinks(
          event.transitiveRepositories(), doneNodesLocalRef, lazilyPlantedSymlinksLocalRef, futures);
    }
    if (!maybeConflictingBaseNamesLowercase.isEmpty()) {
      recursiveRegisterAndPlantTopLevelDirSymlinks(
          event.transitiveTopLevelDirs(), doneNodesLocalRef, lazilyPlantedSymlinksLocalRef, futures);
    }

    try {
      Futures.whenAllSucceed(futures).call(() -> null, directExecutor()).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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

  /** Lazily plant the symlinks to the external repositories used by a top-level target. */
  private void recursiveRegisterAndPlantExternalRepoSymlinks(
      NestedSet<RepositoryMetadata> repositories,
      Set<Node> doneNodesRef,
      Set<Path> lazilyPlantedSymlinksRef,
      List<ListenableFuture<Void>> futures) {
    if (!doneNodesRef.add(repositories.toNode())) {
      return;
    }

    synchronized (symlinkPlantingPool) {
      if (symlinkPlantingPool.isShutdown()) {
        return;
      }
      for (RepositoryMetadata repository : repositories.getLeaves()) {
        if (repository.repository().isMain()) {
          continue;
        }
        futures.add(
            symlinkPlantingPool.submit(
                () -> plantSymlinkForExternalRepository(repository, lazilyPlantedSymlinksRef)));
      }
    }
    for (NestedSet<RepositoryMetadata> transitive : repositories.getNonLeaves()) {
      recursiveRegisterAndPlantExternalRepoSymlinks(
          transitive, doneNodesRef, lazilyPlantedSymlinksRef, futures);
    }
  }

  /**
   * Lazily plant the symlinks to the main repository top-level directories used by a top-level
   * target whose names clash with those of other directories on a case-insensitive file system.
   */
  private void recursiveRegisterAndPlantTopLevelDirSymlinks(
      NestedSet<String> topLevelDirs,
      Set<Node> doneNodesRef,
      Set<Path> lazilyPlantedSymlinksRef,
      List<ListenableFuture<Void>> futures) {
    if (!doneNodesRef.add(topLevelDirs.toNode())) {
      return;
    }

    synchronized (symlinkPlantingPool) {
      if (symlinkPlantingPool.isShutdown()) {
        return;
      }
      for (String topLevelDir : topLevelDirs.getLeaves()) {
        if (!maybeConflictingBaseNamesLowercase.contains(Ascii.toLowerCase(topLevelDir))) {
          // We should have already eagerly planted a symlink for this.
          continue;
        }
        futures.add(
            symlinkPlantingPool.submit(
                () -> plantSymlinkForTopLevelDir(topLevelDir, lazilyPlantedSymlinksRef)));
      }
    }
    for (NestedSet<String> transitive : topLevelDirs.getNonLeaves()) {
      recursiveRegisterAndPlantTopLevelDirSymlinks(
          transitive, doneNodesRef, lazilyPlantedSymlinksRef, futures);
    }
  }

  private Void plantSymlinkForTopLevelDir(String topLevelDir, Set<Path> lazilyPlantedSymlinksRef)
      throws AbruptExitException {
    // As Skymeld only supports single package path at the moment, we only seek to symlink to the
    // top-level dir i.e. what's directly under the source root.
    Path link = execroot.getRelative(topLevelDir);
    Path target = singleSourceRoot.getRelative(topLevelDir);
    if (!SymlinkForest.symlinkShouldBePlanted(prefix, ignoredPaths, topLevelDir, target)) {
      return null;
    }
    if (!lazilyPlantedSymlinksRef.add(link)) {
      return null;
    }
    try {
      link.createSymbolicLink(target);
    } catch (IOException e) {
      StringBuilder errorMessage =
          new StringBuilder(String.format("Failed to plant a symlink: %s -> %s", link, target));
      try {
        if (link.exists() && link.isSymbolicLink()) {
          // If the link already exists, it must mean that we're planting from a case-insensitive
          // file system and this is a legitimate conflict.
          // TODO(b/295300378) We technically can go deeper here and try to create the subdirs to
          // try to resolve the conflict, but the complexity isn't worth it at the moment and the
          // non-skymeld code path isn't doing any better. Revisit if necessary.
          Path existingTarget = link.resolveSymbolicLinks();
          if (!existingTarget.equals(target)) {
            errorMessage.append(
                String.format(
                    ". Found an existing conflicting symlink: %s -> %s", link, existingTarget));
          }
        }
      } catch (IOException ignored) {
        // Report the original error.
      }
      throwAbruptExitException(new SymlinkPlantingException(errorMessage.toString(), e));
    }
    return null;
  }

  private Void plantSymlinkForExternalRepository(
      RepositoryMetadata repository, Set<Path> lazilyPlantedSymlinksRef)
      throws AbruptExitException {
    threadSafeExternalRepoRootsMap.putIfAbsent(repository.repository(), repository.sourceRoot());
    try {
      SymlinkForest.plantSingleSymlinkForExternalRepo(
          repository.repository(),
          repository.sourceRoot().asPath(),
          execroot,
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
      doneNodes = null;
      lazilyPlantedSymlinks = null;
      maybeConflictingBaseNamesLowercase = ImmutableSet.of();
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
