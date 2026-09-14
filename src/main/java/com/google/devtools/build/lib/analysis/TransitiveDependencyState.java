// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RepositoryMetadata;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.PrerequisitePackageFunction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** Groups state associated with transitive dependencies. */
public final class TransitiveDependencyState {
  private final NestedSetBuilder<Cause> transitiveRootCauses;

  /**
   * State for constructing the repositories transitively loaded for the value being built.
   *
   * <p>See {@link
   * com.google.devtools.build.lib.analysis.ConfiguredObjectValue#getTransitiveRepositories}.
   *
   * <p>Non-null when transitive repositories are tracked, determined by {@link
   * com.google.devtools.build.lib.skyframe.SkyframeExecutor#shouldStoreTransitiveRepositoriesInLoadingAndAnalysis}.
   */
  @Nullable private final Collector<RepositoryMetadata> repositoryCollector;

  /**
   * State for constructing the main repository top-level directories transitively loaded for the
   * value being built.
   *
   * <p>See {@link
   * com.google.devtools.build.lib.analysis.ConfiguredObjectValue#getTransitiveTopLevelDirs}.
   *
   * <p>Non-null iff {@link #repositoryCollector} is.
   */
  @Nullable private final Collector<String> topLevelDirCollector;

  /**
   * Retrieves packages that were previously requested by transitive dependencies.
   *
   * <p>When the {@link ConfiguredTargetFunction} computes a value, it depends on properties of its
   * dependencies. In some cases, those values are read directly out of the dependency's underlying
   * {@link Target}. All instances of this are to be restricted to where {@link
   * ConfiguredTargetAndData#target} is read.
   *
   * <p>More ideally, those properties would be conveyed via providers of those dependencies, but
   * doing so would adversely affect resting heap usage whereas {@link ConfiguredTargetAndData} is
   * ephemeral. Distributed implementations will include these properties in an extra provider. It
   * won't affect memory because the underlying package won't exist on the node loading it remotely.
   *
   * <p>It's valid to obtain {@link Package}s of dependencies from this function instead of creating
   * an edge in {@code Skyframe} due to the transitive dependency through the {@link
   * ConfiguredTarget}. Invalidation of the {@link Package} propagates upwards through the
   * dependency. This is compatible with bottom-up change pruning because {@link
   * ConfiguredTargetValue} uses identity equals.
   */
  private final PrerequisitePackageFunction prerequisitePackages;

  public TransitiveDependencyState(
      boolean storeTransitiveRepositories, PrerequisitePackageFunction prerequisitePackages) {
    this.transitiveRootCauses = NestedSetBuilder.stableOrder();
    this.repositoryCollector =
        storeTransitiveRepositories ? new Collector<>(REPOSITORY_ORDERING) : null;
    this.topLevelDirCollector =
        storeTransitiveRepositories ? new Collector<>(Comparator.<String>naturalOrder()) : null;
    this.prerequisitePackages = prerequisitePackages;
  }

  public static TransitiveDependencyState createForTesting() {
    return new TransitiveDependencyState(
        /* storeTransitiveRepositories= */ false,
        // Always returning null here causes the underlying code to fall back on declaring Package
        // edges for prerequisites, which is benign.
        /* prerequisitePackages= */ p -> null);
  }

  public NestedSetBuilder<Cause> transitiveRootCauses() {
    return transitiveRootCauses;
  }

  @Nullable
  public NestedSet<RepositoryMetadata> transitiveRepositories() {
    if (repositoryCollector == null) {
      return null;
    }
    return repositoryCollector.buildSet();
  }

  @Nullable
  public NestedSet<String> transitiveTopLevelDirs() {
    if (topLevelDirCollector == null) {
      return null;
    }
    return topLevelDirCollector.buildSet();
  }

  public void addTransitiveCauses(NestedSet<Cause> transitiveCauses) {
    transitiveRootCauses.addTransitive(transitiveCauses);
  }

  public void addTransitiveCause(Cause cause) {
    transitiveRootCauses.add(cause);
  }

  public boolean hasRootCause() {
    return !transitiveRootCauses.isEmpty();
  }

  /** Whether transitive repositories and top-level directories are tracked. */
  public boolean storeTransitiveRepositories() {
    return repositoryCollector != null;
  }

  /**
   * Adds the repository and, for the main repository, the top-level directory of the given package
   * if {@link #storeTransitiveRepositories} is true.
   */
  public void addPackage(Package.Metadata pkg) {
    if (repositoryCollector == null) {
      return;
    }
    repositoryCollector.direct.add(RepositoryMetadata.forPackage(pkg));
    PackageIdentifier packageId = pkg.packageIdentifier();
    if (packageId.getRepository().isMain()) {
      String topLevelDir = packageId.getTopLevelDir();
      if (!topLevelDir.isEmpty()) {
        topLevelDirCollector.direct.add(topLevelDir);
      }
    }
  }

  /**
   * Adds the transitive repositories and top-level directories of a configured target dependency if
   * {@link #storeTransitiveRepositories} is true.
   *
   * <p>The sets may be null if the dependency's value does not track them (e.g. because it was
   * retrieved from a remote analysis cache).
   */
  public void addDependency(
      ConfiguredTargetKey key,
      @Nullable NestedSet<RepositoryMetadata> repositories,
      @Nullable NestedSet<String> topLevelDirs) {
    if (repositoryCollector == null) {
      return;
    }
    if (repositories != null) {
      repositoryCollector.configuredTargetSets.put(key, repositories);
    }
    if (topLevelDirs != null) {
      topLevelDirCollector.configuredTargetSets.put(key, topLevelDirs);
    }
  }

  /**
   * Adds the transitive repositories and top-level directories of an aspect dependency if {@link
   * #storeTransitiveRepositories} is true.
   *
   * <p>The sets may be null if the dependency's value does not track them (e.g. because it was
   * retrieved from a remote analysis cache).
   */
  public void addDependency(
      AspectKey key,
      @Nullable NestedSet<RepositoryMetadata> repositories,
      @Nullable NestedSet<String> topLevelDirs) {
    if (repositoryCollector == null) {
      return;
    }
    if (repositories != null) {
      repositoryCollector.aspectSets.put(key, repositories);
    }
    if (topLevelDirs != null) {
      topLevelDirCollector.aspectSets.put(key, topLevelDirs);
    }
  }

  @Nullable
  public Package getDependencyPackage(PackageIdentifier packageId) throws InterruptedException {
    return prerequisitePackages.getExistingPackage(packageId);
  }

  /**
   * Collects small sets of elements from direct and transitive dependencies to be unified in a
   * {@link NestedSet}.
   *
   * <p>Performs bookkeeping so the result is deterministic.
   *
   * <p>Work in Skyframe may complete in arbitrary order due to missing values and restarts. For
   * example, if a client requests {@code //foo} and {@code //bar}, it could receive any of the
   * following: {@code (//foo, null), (null, //bar), (//foo, //bar) or (null, null)}.
   *
   * <p>This class tracks how the elements are added so they can be given a deterministic order.
   * This is required for determinism of {@link ActionKeyComputer#computeKey}.
   *
   * <p>The sets collected here are tiny compared to the number of configured targets that collect
   * them, and the vast majority of configured targets end up with the same contents as their
   * dependencies. To let the nested sets be reused instead of growing by one node per configured
   * target, the collector drops transitive sets that are subsets of another transitive set and
   * direct elements that are contained in a transitive set. A configured target that adds nothing
   * to the (largest) set of one of its dependencies thus reuses that set as is. This requires
   * flattening the transitive sets and is only affordable because they are small. Structurally
   * identical sets that are still created are deduplicated by the generic {@link
   * com.google.devtools.build.lib.collect.nestedset.NestedSetInterner}.
   */
  private static class Collector<E> {
    private final Comparator<E> ordering;

    /** Keeps elements that were added directly. These will be sorted. */
    private final Set<E> direct = new HashSet<>();

    /** Stores transitive sets of {@link ConfiguredTargetValues}s. */
    private final TreeMap<ConfiguredTargetKey, NestedSet<E>> configuredTargetSets =
        new TreeMap<>(ConfiguredTargetKey.ORDERING);

    /** Stores transitive sets of {@link AspectValue}s. */
    private final TreeMap<AspectKey, NestedSet<E>> aspectSets = new TreeMap<>(AspectKey.ORDERING);

    private Collector(Comparator<E> ordering) {
      this.ordering = ordering;
    }

    /**
     * Constructs the deterministically ordered result.
     *
     * <p>It's safe to call this multiple times.
     */
    private NestedSet<E> buildSet() {
      List<NestedSet<E>> transitive =
          dropSubsumed(
              ImmutableList.<NestedSet<E>>builder()
                  .addAll(configuredTargetSets.values())
                  .addAll(aspectSets.values())
                  .build());

      Set<E> contained = new HashSet<>();
      for (NestedSet<E> set : transitive) {
        contained.addAll(set.toList());
      }
      TreeSet<E> sortedDirect = new TreeSet<>(ordering);
      for (E element : direct) {
        if (!contained.contains(element)) {
          sortedDirect.add(element);
        }
      }

      if (sortedDirect.isEmpty()) {
        if (transitive.isEmpty()) {
          return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
        }
        if (transitive.size() == 1) {
          return transitive.get(0);
        }
      }
      var result = NestedSetBuilder.<E>stableOrder();
      result.addAll(sortedDirect);
      for (NestedSet<E> set : transitive) {
        result.addTransitive(set);
      }
      return result.build();
    }

    /**
     * Deduplicates the given sets by identity and drops empty sets as well as sets whose elements
     * are all contained in another one of the sets. Among sets with equal contents, the first one
     * is kept.
     */
    private static <E> List<NestedSet<E>> dropSubsumed(List<NestedSet<E>> sets) {
      List<NestedSet<E>> candidates = new ArrayList<>(sets.size());
      Set<NestedSet<E>> seen = new HashSet<>();
      for (NestedSet<E> set : sets) {
        if (!set.isEmpty() && seen.add(set)) {
          candidates.add(set);
        }
      }
      if (candidates.size() <= 1) {
        return candidates;
      }
      List<ImmutableList<E>> contents = new ArrayList<>(candidates.size());
      List<ImmutableSet<E>> contentSets = new ArrayList<>(candidates.size());
      for (NestedSet<E> set : candidates) {
        ImmutableList<E> list = set.toList();
        contents.add(list);
        contentSets.add(ImmutableSet.copyOf(list));
      }
      boolean[] subsumed = new boolean[candidates.size()];
      List<NestedSet<E>> result = new ArrayList<>(candidates.size());
      for (int i = 0; i < candidates.size(); i++) {
        ImmutableList<E> mine = contents.get(i);
        for (int j = 0; j < candidates.size() && !subsumed[i]; j++) {
          if (i == j || subsumed[j]) {
            continue;
          }
          int otherSize = contents.get(j).size();
          // Only a set that is at least as large can contain all of my elements. Among equal
          // sets, the earlier one wins.
          if (otherSize < mine.size() || (otherSize == mine.size() && j > i)) {
            continue;
          }
          if (contentSets.get(j).containsAll(mine)) {
            subsumed[i] = true;
          }
        }
        if (!subsumed[i]) {
          result.add(candidates.get(i));
        }
      }
      return result;
    }
  }

  /** Orders repository metadata deterministically. */
  private static final Comparator<RepositoryMetadata> REPOSITORY_ORDERING =
      comparing((RepositoryMetadata repo) -> repo.repository().getName())
          .thenComparing(repo -> repo.sourceRoot().asPath().getPathString());
}
