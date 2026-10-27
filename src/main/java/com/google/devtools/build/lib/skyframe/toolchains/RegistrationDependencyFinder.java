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

package com.google.devtools.build.lib.skyframe.toolchains;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Finds the Skyframe nodes that are required to load the registered toolchains and execution
 * platforms, which toolchain resolution loads in their entirety.
 *
 * <p>This walks down the graph from the targets and target patterns that the {@code
 * RegisteredToolchainsValue} and {@link RegisteredExecutionPlatformsValue} nodes depend on, so its
 * cost is bounded by what is loaded for registration rather than by the size of the build.
 *
 * <p>Requires a graph that keeps edges and must not be used while an evaluation is in progress.
 */
public final class RegistrationDependencyFinder {

  /**
   * Deps that are needed to evaluate the registration nodes and nodes below them, but don't depend
   * on what is registered. In particular, this excludes the repos fetched for module resolution.
   */
  private static final ImmutableSet<SkyFunctionName> SKIPPED_FUNCTIONS =
      ImmutableSet.of(
          SkyFunctions.BAZEL_DEP_GRAPH,
          SkyFunctions.BAZEL_MODULE_RESOLUTION,
          SkyFunctions.BUILD_CONFIGURATION,
          SkyFunctions.MODULE_FILE,
          SkyFunctions.REPOSITORY_MAPPING);

  private RegistrationDependencyFinder() {}

  /**
   * Returns, for each of the given keys whose node is a transitive dependency of a registered
   * toolchain, execution platform or target pattern, a human-readable description of one such
   * registration.
   *
   * @param mainRepoMapping the main repository mapping, used to display labels, or {@code null} to
   *     display them in canonical form
   */
  public static ImmutableMap<SkyKey, String> findRegistrationsRequiring(
      InMemoryGraph graph,
      @Nullable RepositoryMapping mainRepoMapping,
      Set<? extends SkyKey> keys) {
    // Both registration functions depend on the module graph, which has comparatively few reverse
    // deps, so this is a cheap way to find them without scanning the entire graph.
    InMemoryNodeEntry depGraphEntry = graph.getIfPresent(BazelDepGraphValue.KEY);
    if (keys.isEmpty()
        || depGraphEntry == null
        || !depGraphEntry.isDone()
        || !depGraphEntry.keepsEdges()) {
      return ImmutableMap.of();
    }
    List<Map.Entry<SkyKey, String>> registrations = new ArrayList<>();
    for (SkyKey rdep : depGraphEntry.getReverseDepsForDoneEntry()) {
      String kind;
      if (rdep.functionName().equals(SkyFunctions.REGISTERED_TOOLCHAINS)) {
        kind = "toolchain";
      } else if (rdep.functionName().equals(SkyFunctions.REGISTERED_EXECUTION_PLATFORMS)) {
        kind = "execution platform";
      } else {
        continue;
      }
      InMemoryNodeEntry entry = graph.getIfPresent(rdep);
      if (entry == null || !entry.isDone()) {
        continue;
      }
      for (SkyKey dep : entry.getDirectDeps()) {
        String description = describeRegistered(kind, dep, mainRepoMapping);
        if (description != null) {
          registrations.add(Map.entry(dep, description));
        }
      }
    }
    // Sort to make the description reported for a node deterministic. This also ensures that
    // registered targets come before the target patterns that they are registered with.
    registrations.sort(Map.Entry.comparingByValue());

    // Breadth-first search, recording the registration via which each node was reached first.
    Map<SkyKey, String> registrationByNode = new HashMap<>();
    Queue<SkyKey> toVisit = new ArrayDeque<>();
    for (Map.Entry<SkyKey, String> registration : registrations) {
      if (registrationByNode.putIfAbsent(registration.getKey(), registration.getValue()) == null) {
        toVisit.add(registration.getKey());
      }
    }
    ImmutableMap.Builder<SkyKey, String> result = ImmutableMap.builder();
    int found = 0;
    while (!toVisit.isEmpty() && found < keys.size()) {
      SkyKey key = toVisit.remove();
      String registration = registrationByNode.get(key);
      if (keys.contains(key)) {
        result.put(key, registration);
        found++;
      }
      InMemoryNodeEntry entry = graph.getIfPresent(key);
      if (entry == null || !entry.isDone()) {
        continue;
      }
      for (SkyKey dep : entry.getDirectDeps()) {
        if (!SKIPPED_FUNCTIONS.contains(dep.functionName())
            && registrationByNode.putIfAbsent(dep, registration) == null) {
          toVisit.add(dep);
        }
      }
    }
    return result.buildOrThrow();
  }

  /**
   * Returns a description of {@code dep} if it is a registered target or target pattern, otherwise
   * {@code null}. Other deps of the registration functions (e.g. the module graph) are needed to
   * find out what is registered rather than because they are registered.
   */
  @Nullable
  private static String describeRegistered(
      String kind, SkyKey dep, @Nullable RepositoryMapping mainRepoMapping) {
    return switch (dep) {
      case ConfiguredTargetKey configuredTargetKey ->
          "%s %s".formatted(kind, configuredTargetKey.getLabel().getDisplayForm(mainRepoMapping));
      case TargetPatternKey targetPatternKey ->
          "%s target pattern %s"
              .formatted(
                  kind, displayPattern(targetPatternKey.getParsedPattern(), mainRepoMapping));
      default -> null;
    };
  }

  private static String displayPattern(
      TargetPattern pattern, @Nullable RepositoryMapping mainRepoMapping) {
    String originalPattern = pattern.getOriginalPattern();
    int packageStart = originalPattern.indexOf("//");
    if (packageStart == -1) {
      return originalPattern;
    }
    // Patterns registered by modules are relative to the module's repo, so display them in the
    // context of the main repo instead.
    return pattern.getRepository().getDisplayForm(mainRepoMapping)
        + originalPattern.substring(packageStart);
  }
}
