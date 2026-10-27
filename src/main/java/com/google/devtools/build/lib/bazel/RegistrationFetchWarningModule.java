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

package com.google.devtools.build.lib.bazel;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.repository.ExpensiveRepositoryFetchEvent;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.toolchains.RegistrationDependencyFinder;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Warns about fetched repositories that are required to load registered toolchains or execution
 * platforms even though their repository rules call expensive {@code repository_ctx} methods.
 *
 * <p>Repositories of Bazel modules are not reported since modules commonly register toolchains
 * declared in their own repositories, which are typically small.
 *
 * <p>Since toolchain resolution loads all registered toolchains and execution platforms, such
 * repositories are fetched by every build that uses toolchain resolution, even if none of their
 * toolchains and execution platforms are used.
 */
public final class RegistrationFetchWarningModule extends BlazeModule {
  @Nullable private CommandEnvironment env;
  @Nullable private Map<RepositoryName, ImmutableSet<String>> expensiveFetches;

  @Override
  public void beforeCommand(CommandEnvironment env) {
    RepositoryOptions repoOptions = env.getOptions().getOptions(RepositoryOptions.class);
    if (repoOptions == null || !repoOptions.getWarnOnExpensiveRegistrationFetches()) {
      return;
    }
    this.env = env;
    this.expensiveFetches = new ConcurrentHashMap<>();
    env.getEventBus().register(this);
  }

  @Override
  public void afterCommand() {
    this.env = null;
    this.expensiveFetches = null;
  }

  @Subscribe
  @AllowConcurrentEvents
  public void expensiveFetch(ExpensiveRepositoryFetchEvent event) {
    expensiveFetches.put(event.repo(), event.expensiveCalls());
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) {
    if (expensiveFetches.isEmpty()) {
      return;
    }
    // The graph is no longer being modified at this point.
    InMemoryGraph graph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();
    ImmutableSet<RepositoryName> moduleRepos = getModuleRepos(graph);
    ImmutableList<RepositoryName> repos =
        expensiveFetches.keySet().stream()
            .filter(repo -> !moduleRepos.contains(repo))
            .sorted(comparing(RepositoryName::getName))
            .collect(toImmutableList());
    if (repos.isEmpty()) {
      return;
    }
    RepositoryMapping mainRepoMapping = getMainRepoMapping(graph);
    ImmutableMap<SkyKey, String> registrations =
        RegistrationDependencyFinder.findRegistrationsRequiring(
            graph,
            mainRepoMapping,
            repos.stream().map(RepositoryDirectoryValue::key).collect(toImmutableSet()));
    for (RepositoryName repo : repos) {
      String registration = registrations.get(RepositoryDirectoryValue.key(repo));
      if (registration != null) {
        env.getReporter()
            .handle(
                Event.warn(
                    formatWarning(
                        repo, graph, mainRepoMapping, expensiveFetches.get(repo), registration)));
      }
    }
  }

  private static String formatWarning(
      RepositoryName repo,
      InMemoryGraph graph,
      @Nullable RepositoryMapping mainRepoMapping,
      ImmutableSet<String> expensiveCalls,
      String registration) {
    StringBuilder message =
        new StringBuilder("Repository '").append(repo.getDisplayForm(mainRepoMapping)).append("'");
    String module = getAssociatedModule(graph, repo);
    if (module != null) {
      message.append(" (from module '").append(module).append("')");
    }
    return message
        .append(" is required by the registered ")
        .append(registration)
        .append(", but its repository rule called the expensive repository_ctx methods ")
        .append(String.join(", ", expensiveCalls))
        .append(". Toolchain resolution loads all registered toolchains and execution platforms,")
        .append(" so every build that uses it has to fetch this repository, even if it doesn't")
        .append(" use them. Consider declaring the toolchain() and platform() targets in a")
        .append(" separate repository that is cheap to fetch and referring to this repository")
        .append(" only via the 'toolchain' attribute of toolchain().")
        .toString();
  }

  private static ImmutableSet<RepositoryName> getModuleRepos(InMemoryGraph graph) {
    InMemoryNodeEntry entry = graph.getIfPresent(BazelDepGraphValue.KEY);
    if (entry != null
        && entry.isDone()
        && entry.getValue() instanceof BazelDepGraphValue depGraph) {
      return depGraph.getCanonicalRepoNameLookup().keySet();
    }
    return ImmutableSet.of();
  }

  @Nullable
  private static RepositoryMapping getMainRepoMapping(InMemoryGraph graph) {
    InMemoryNodeEntry entry = graph.getIfPresent(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (entry != null
        && entry.isDone()
        && entry.getValue() instanceof RepositoryMappingValue repositoryMappingValue) {
      return repositoryMappingValue.repositoryMapping();
    }
    return null;
  }

  @Nullable
  private static String getAssociatedModule(InMemoryGraph graph, RepositoryName repo) {
    InMemoryNodeEntry entry = graph.getIfPresent(RepositoryMappingValue.key(repo));
    if (entry != null
        && entry.isDone()
        && entry.getValue() instanceof RepositoryMappingValue repositoryMappingValue) {
      return repositoryMappingValue
          .associatedModuleName()
          .filter(name -> !name.isEmpty())
          .orElse(null);
    }
    return null;
  }
}
