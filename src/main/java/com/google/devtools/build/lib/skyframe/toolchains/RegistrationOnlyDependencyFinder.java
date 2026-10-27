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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionValue.SingleToolchainResolutionKey;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Finds out whether a Skyframe node was only evaluated because all registered toolchains and
 * execution platforms have to be loaded during toolchain resolution, rather than because any of
 * them was used.
 *
 * <p>This walks the reverse deps of the node: if every path from the node to a node that was
 * requested directly passes through an edge from {@code RegisteredToolchainsValue} or {@link
 * RegisteredExecutionPlatformsValue} to a registered target or target pattern, or through an edge
 * from toolchain resolution to a registered execution platform that ends up not being selected,
 * then the node is only required because of registration. Target and host platforms are always
 * considered to be used.
 *
 * <p>Requires a graph that keeps edges and must not be used while an evaluation is in progress.
 * Nodes that aren't done (e.g., because they were invalidated but not requested again) are not
 * considered to be a reason for their deps to be evaluated.
 */
public final class RegistrationOnlyDependencyFinder {

  private final InMemoryGraph graph;
  @Nullable private final RepositoryMapping mainRepoMapping;
  private final Map<BuildConfigurationKey, ImmutableSet<ConfiguredTargetKey>>
      registeredExecutionPlatformsCache = new HashMap<>();

  /**
   * @param mainRepoMapping the main repository mapping, used to display labels, or {@code null} to
   *     display them in canonical form
   */
  public RegistrationOnlyDependencyFinder(
      InMemoryGraph graph, @Nullable RepositoryMapping mainRepoMapping) {
    this.graph = graph;
    this.mainRepoMapping = mainRepoMapping;
  }

  /**
   * Returns human-readable descriptions of the registered toolchains, execution platforms and
   * target patterns that require the node for {@code key}, or an empty set if the node is also
   * required for other reasons, isn't done or the graph doesn't keep edges.
   */
  public ImmutableSortedSet<String> findRegistrationsRequiring(SkyKey key) {
    InMemoryNodeEntry entry = graph.getIfPresent(key);
    if (entry == null || !entry.isDone() || !entry.keepsEdges()) {
      return ImmutableSortedSet.of();
    }
    Set<String> registrations = new TreeSet<>();
    Set<SkyKey> visited = new HashSet<>();
    Deque<InMemoryNodeEntry> toVisit = new ArrayDeque<>();
    visited.add(key);
    toVisit.push(entry);
    // Use DFS since it usually reaches a node without reverse deps quickly if there is one.
    while (!toVisit.isEmpty()) {
      InMemoryNodeEntry current = toVisit.pop();
      boolean hasDoneReverseDep = false;
      for (SkyKey rdep : current.getReverseDepsForDoneEntry()) {
        InMemoryNodeEntry rdepEntry = graph.getIfPresent(rdep);
        if (rdepEntry == null || !rdepEntry.isDone()) {
          continue;
        }
        hasDoneReverseDep = true;
        String registration = getRegistration(current.getKey(), rdep, rdepEntry);
        if (registration != null) {
          registrations.add(registration);
        } else if (visited.add(rdep)) {
          toVisit.push(rdepEntry);
        }
      }
      if (!hasDoneReverseDep) {
        // The node was requested directly, so the original node is required for reasons other than
        // registration.
        return ImmutableSortedSet.of();
      }
    }
    return ImmutableSortedSet.copyOf(registrations);
  }

  /**
   * Returns a description of the registration if the edge from {@code rdep} to {@code dep} only
   * exists because {@code dep} is registered, otherwise {@code null}.
   */
  @Nullable
  private String getRegistration(SkyKey dep, SkyKey rdep, InMemoryNodeEntry rdepEntry) {
    SkyFunctionName rdepFunction = rdep.functionName();
    if (rdepFunction.equals(SkyFunctions.REGISTERED_TOOLCHAINS)) {
      return describeRegistered("toolchain", dep);
    }
    if (rdepFunction.equals(SkyFunctions.REGISTERED_EXECUTION_PLATFORMS)) {
      return describeRegistered("execution platform", dep);
    }
    if (dep instanceof ConfiguredTargetKey platformKey
        && isUnselectedRegisteredExecutionPlatform(platformKey, rdep, rdepEntry)) {
      return describeRegistered("execution platform", dep);
    }
    return null;
  }

  /**
   * Returns a description of {@code dep} if it is a registered target or target pattern, otherwise
   * {@code null}. Other deps of the registration functions (e.g. the module graph) are needed to
   * find out what is registered rather than because they are registered.
   */
  @Nullable
  private String describeRegistered(String kind, SkyKey dep) {
    return switch (dep) {
      case ConfiguredTargetKey configuredTargetKey ->
          "%s %s".formatted(kind, configuredTargetKey.getLabel().getDisplayForm(mainRepoMapping));
      case TargetPatternKey targetPatternKey ->
          "%s target pattern %s"
              .formatted(kind, displayPattern(targetPatternKey.getParsedPattern()));
      default -> null;
    };
  }

  private String displayPattern(TargetPattern pattern) {
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

  /**
   * Returns whether {@code rdep} is a toolchain resolution node that depends on {@code
   * platformKey} only because it is a registered execution platform, not because it is the target
   * or host platform or was selected as the execution platform.
   */
  private boolean isUnselectedRegisteredExecutionPlatform(
      ConfiguredTargetKey platformKey, SkyKey rdep, InMemoryNodeEntry rdepEntry) {
    BuildConfigurationKey configurationKey;
    switch (rdep) {
      case ToolchainContextKey toolchainContextKey -> {
        if (!(rdepEntry.getValue() instanceof UnloadedToolchainContext toolchainContext)
            || toolchainContext.executionPlatform().label().equals(platformKey.getLabel())
            || toolchainContext.targetPlatform().label().equals(platformKey.getLabel())) {
          return false;
        }
        configurationKey = toolchainContextKey.configurationKey();
      }
      case SingleToolchainResolutionKey singleToolchainResolutionKey -> {
        // This doesn't select an execution platform, toolchain resolution does that based on the
        // result and also depends on the selected platform.
        if (singleToolchainResolutionKey.targetPlatformKey().equals(platformKey)) {
          return false;
        }
        configurationKey = singleToolchainResolutionKey.configurationKey();
      }
      default -> {
        return false;
      }
    }
    if (!getRegisteredExecutionPlatforms(configurationKey).contains(platformKey)) {
      // E.g. a toolchain type, a constraint value or the host platform.
      return false;
    }
    PlatformConfiguration platformConfiguration = getPlatformConfiguration(configurationKey);
    return platformConfiguration != null
        && !platformKey.getLabel().equals(platformConfiguration.getTargetPlatform())
        && !platformKey.getLabel().equals(platformConfiguration.getHostPlatform());
  }

  private ImmutableSet<ConfiguredTargetKey> getRegisteredExecutionPlatforms(
      BuildConfigurationKey configurationKey) {
    return registeredExecutionPlatformsCache.computeIfAbsent(
        configurationKey,
        k -> {
          var platforms = ImmutableSet.<ConfiguredTargetKey>builder();
          for (boolean debug : new boolean[] {false, true}) {
            InMemoryNodeEntry entry =
                graph.getIfPresent(RegisteredExecutionPlatformsValue.key(k, debug));
            if (entry != null
                && entry.isDone()
                && entry.getValue() instanceof RegisteredExecutionPlatformsValue value) {
              platforms.addAll(value.registeredExecutionPlatformKeys());
            }
          }
          return platforms.build();
        });
  }

  @Nullable
  private PlatformConfiguration getPlatformConfiguration(BuildConfigurationKey configurationKey) {
    InMemoryNodeEntry entry = graph.getIfPresent(configurationKey);
    if (entry == null
        || !entry.isDone()
        || !(entry.getValue() instanceof BuildConfigurationValue configuration)) {
      return null;
    }
    return configuration.getFragment(PlatformConfiguration.class);
  }
}
