// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.metrics.criticalpath;

import com.google.common.base.Preconditions;
import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.StackSize;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionChangePrunedEvent;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.AggregatedSpawnMetrics;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.actions.DiscoveredInputsEvent;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewoundEvent;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Computes the critical path in the action graph based on events published to the event bus.
 *
 * <p>After instantiation, this object needs to be registered on the event bus to work.
 */
@ThreadSafe
public class CriticalPathComputer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Number of top actions to record. */
  static final int SLOWEST_COMPONENTS_SIZE = 30;

  private static final int LARGEST_MEMORY_COMPONENTS_SIZE = 20;
  private static final int LARGEST_INPUT_SIZE_COMPONENTS_SIZE = 20;
  private static final int LARGEST_INPUT_COUNT_COMPONENTS_SIZE = 20;

  /** Selects and returns the longer of two components (the first may be {@code null}). */
  private static final BinaryOperator<CriticalPathComponent> SELECT_LONGER_COMPONENT =
      (a, b) -> {
        if (a == null) {
          return b;
        }
        if (b == null) {
          return a;
        }
        return a.getAggregatedElapsedTime().compareTo(b.getAggregatedElapsedTime()) < 0 ? b : a;
      };

  // outputArtifactToComponent is accessed from multiple event handlers. It points at the component
  // of the latest execution of the generating action.
  private final ConcurrentMap<Artifact, CriticalPathComponent> outputArtifactToComponent =
      new ConcurrentHashMap<>();

  /**
   * Components of earlier executions of actions that executed again, e.g. after they were rewound.
   * They are no longer reachable through {@link #outputArtifactToComponent}.
   */
  private final Queue<CriticalPathComponent> supersededComponents = new ConcurrentLinkedQueue<>();

  /**
   * The components of failed executions that rewound an action, keyed by the action's primary
   * output. The next execution of the action depends on them.
   */
  private final ConcurrentMap<Artifact, Queue<CriticalPathComponent>> rewindTriggers =
      new ConcurrentHashMap<>();

  /**
   * Components that an execution depends on in addition to the generating actions of its inputs:
   * the earlier execution of the same action and the failed executions that rewound it. Consumed
   * when the execution finishes.
   */
  private final ConcurrentMap<CriticalPathComponent, ImmutableList<CriticalPathComponent>>
      executionPredecessors = new ConcurrentHashMap<>();
  private final ActionKeyContext actionKeyContext;
  @Nullable private final WalkableGraph graph;

  /** Maximum critical path found. */
  private final AtomicReference<CriticalPathComponent> maxCriticalPath = new AtomicReference<>();

  public CriticalPathComputer(ActionKeyContext actionKeyContext, @Nullable WalkableGraph graph) {
    this.actionKeyContext = actionKeyContext;
    this.graph = graph;
  }

  /**
   * Creates a critical path component for an action.
   *
   * @param action the action for the critical path component
   * @param relativeStartNanos time when the action started to run in nanos. Only meant to be used
   *     for computing time differences.
   */
  private CriticalPathComponent createComponent(Action action, long relativeStartNanos) {
    return new CriticalPathComponent(action, relativeStartNanos);
  }

  /**
   * Return the critical path stats for the current command execution.
   *
   * <p>This method allows us to calculate lazily the aggregate statistics of the critical path,
   * avoiding the memory and cpu penalty for doing it for all the actions executed.
   */
  public AggregatedCriticalPath aggregate() {
    CriticalPathComponent criticalPath = getMaxCriticalPath();
    if (criticalPath == null) {
      return AggregatedCriticalPath.EMPTY;
    }

    ImmutableList.Builder<CriticalPathComponent> components = ImmutableList.builder();
    AggregatedSpawnMetrics.Builder metricsBuilder = new AggregatedSpawnMetrics.Builder();
    CriticalPathComponent child = criticalPath;

    while (child != null) {
      AggregatedSpawnMetrics childSpawnMetrics = child.getSpawnMetrics();
      if (childSpawnMetrics != null) {
        metricsBuilder.addDurations(childSpawnMetrics);
        metricsBuilder.addNonDurations(childSpawnMetrics);
      }
      components.add(child);
      child = child.getChild();
    }

    return new AggregatedCriticalPath(
        criticalPath.getAggregatedElapsedTime(), metricsBuilder.build(), components.build());
  }

  public Map<Artifact, CriticalPathComponent> getCriticalPathComponentsMap() {
    return outputArtifactToComponent;
  }

  /** Changes the phase of the action */
  @Subscribe
  @AllowConcurrentEvents
  public void nextCriticalPathPhase(SpawnExecutedEvent.ChangePhase phase) {
    CriticalPathComponent stats =
        outputArtifactToComponent.get(phase.getAction().getPrimaryOutput());
    if (stats != null) {
      stats.changePhase();
    }
  }

  /** Adds spawn metrics to the action stats. */
  @Subscribe
  @AllowConcurrentEvents
  public void spawnExecuted(SpawnExecutedEvent event) {
    ActionAnalysisMetadata action = event.getActionMetadata();
    Artifact primaryOutput = action.getPrimaryOutput();
    if (primaryOutput == null) {
      // Despite the documentation to the contrary, the SpawnIncludeScanner creates an
      // ActionExecutionMetadata instance that returns a null primary output. That said, this
      // class is incorrect wrt. multiple Spawns in a single action. See b/111583707.
      return;
    }
    CriticalPathComponent stats =
        Preconditions.checkNotNull(outputArtifactToComponent.get(primaryOutput));

    SpawnResult spawnResult = event.getSpawnResult();
    stats.addSpawnResult(
        spawnResult.getMetrics(),
        spawnResult.getRunnerName(),
        spawnResult.getRunnerSubtype(),
        spawnResult.wasRemote());
  }

  /** Returns the list of components using the most memory. */
  public List<CriticalPathComponent> getLargestMemoryComponents() {
    return uniqueComponents()
        .collect(
            Comparators.greatest(
                LARGEST_MEMORY_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) ->
                        c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::memoryEstimate))));
  }

  /** Returns the list of components with the largest input sizes. */
  public List<CriticalPathComponent> getLargestInputSizeComponents() {
    return uniqueComponents()
        .collect(
            Comparators.greatest(
                LARGEST_INPUT_SIZE_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) -> c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputBytes))));
  }

  /** Returns the list of components with the largest input counts. */
  public List<CriticalPathComponent> getLargestInputCountComponents() {
    return uniqueComponents()
        .collect(
            Comparators.greatest(
                LARGEST_INPUT_COUNT_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) -> c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputFiles))));
  }

  /** Returns the list of slowest components. */
  public List<CriticalPathComponent> getSlowestComponents() {
    return uniqueComponents()
        .collect(
            Comparators.greatest(
                SLOWEST_COMPONENTS_SIZE,
                Comparator.comparingLong(CriticalPathComponent::getElapsedTimeNanos)));
  }

  /** Returns one component per execution of an action. */
  private Stream<CriticalPathComponent> uniqueComponents() {
    return Stream.concat(
        outputArtifactToComponent.entrySet().stream()
            .filter(e -> e.getValue().isPrimaryOutput(e.getKey()))
            .map(Map.Entry::getValue),
        supersededComponents.stream());
  }

  /** Creates a CriticalPathComponent and adds the duration of input discovery and changes phase. */
  @Subscribe
  @AllowConcurrentEvents
  public void discoverInputs(DiscoveredInputsEvent event) throws InterruptedException {
    CriticalPathComponent stats =
        tryAddComponent(createComponent(event.getAction(), event.getStartTimeNanos()));
    stats.addSpawnResult(event.getMetrics(), null, "", /* wasRemote= */ false);
    stats.changePhase();
  }

  /**
   * Record an action that has started to run. If the CriticalPathComponent has not been created,
   * initialize it and then start running. If an earlier execution of the action has finished, the
   * action is executing again (e.g. because it was rewound after a later action lost one of its
   * outputs), which is tracked by a new component so that both executions can be on the critical
   * path.
   *
   * @param event information about the started action
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionStarted(ActionStartedEvent event) throws InterruptedException {
    Action action = event.getAction();
    CriticalPathComponent component = createComponent(action, event.getNanoTimeStart());
    CriticalPathComponent stored = tryAddComponent(component);
    if (stored == component || !stored.hasFinished()) {
      stored.startRunning();
      return;
    }
    startNewExecution(stored, component);
    component.startRunning();
  }

  /**
   * Makes {@code component} the current component of its action, superseding {@code previous},
   * the component of the action's earlier execution.
   *
   * <p>The new execution depends on the earlier one and on the failed executions that rewound the
   * action, which is recorded when it finishes.
   */
  private void startNewExecution(
      CriticalPathComponent previous, CriticalPathComponent component) {
    Action action = component.getAction();
    for (Artifact output : action.getOutputs()) {
      outputArtifactToComponent.put(output, component);
      // Parent tree artifacts of template expansion outputs point at the longest sibling (see
      // finalizeActionStat). Keep them pointing at the latest execution of this action.
      Artifact parent = output.hasParent() ? output.getParent() : null;
      while (parent != null) {
        outputArtifactToComponent.replace(parent, previous, component);
        parent = parent.hasParent() ? parent.getParent() : null;
      }
    }
    supersededComponents.add(previous);
    ImmutableList.Builder<CriticalPathComponent> predecessors = ImmutableList.builder();
    predecessors.add(previous);
    Queue<CriticalPathComponent> triggers = rewindTriggers.remove(action.getPrimaryOutput());
    if (triggers != null) {
      predecessors.addAll(triggers);
    }
    executionPredecessors.put(component, predecessors.build());
  }

  /**
   * Try to add the component to the map of critical path components. If there is an existing
   * component for its primary output it uses that to update the rest of the outputs.
   *
   * @return The component to be used for updating the time stats.
   */
  @SuppressWarnings("ReferenceEquality")
  private CriticalPathComponent tryAddComponent(CriticalPathComponent newComponent)
      throws InterruptedException {
    Action newAction = newComponent.getAction();
    Artifact primaryOutput = newAction.getPrimaryOutput();
    CriticalPathComponent storedComponent =
        outputArtifactToComponent.putIfAbsent(primaryOutput, newComponent);
    if (storedComponent != null) {
      Action oldAction = storedComponent.getAction();
      // TODO(b/120663721) Replace this fragile reference equality check with something principled.
      if (oldAction != newAction && !Actions.canBeShared(actionKeyContext, newAction, oldAction)) {
        throw new IllegalStateException(
            "Duplicate output artifact found for unsharable actions."
                + "This can happen if a previous event registered the action.\n"
                + "Old action: "
                + oldAction
                + "\n\nNew action: "
                + newAction
                + "\n\nArtifact: "
                + primaryOutput
                + "\n");
      }
    } else {
      storedComponent = newComponent;
    }
    // Try to insert the existing component for the rest of the outputs even if we failed to be
    // the ones inserting the component so that at the end of this method we guarantee that all the
    // outputs have a component.
    for (Artifact output : newAction.getOutputs()) {
      if (output == primaryOutput) {
        continue;
      }
      CriticalPathComponent old = outputArtifactToComponent.putIfAbsent(output, storedComponent);
      // If two actions run concurrently maybe we find a component by primary output but we are
      // the first updating the rest of the outputs.
      Preconditions.checkState(
          old == null || old == storedComponent, "Inconsistent state for %s", newAction);
    }
    return storedComponent;
  }

  /**
   * Record an action that was not executed because it was in the (disk) cache. This is needed so
   * that we can calculate correctly the dependencies tree if we have some cached actions in the
   * middle of the critical path.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionCached(CachedActionEvent event) throws InterruptedException {
    Action action = event.getAction();
    CriticalPathComponent component =
        tryAddComponent(createComponent(action, event.getNanoTimeStart()));
    finalizeActionStat(
        event.getNanoTimeStart(), event.getNanoTimeFinish(), action, component, "action cache hit");
  }

  /**
   * Records the elapsed time stats for the action. For each input artifact, it finds the real
   * dependent artifacts and records the critical path stats.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionComplete(ActionCompletionEvent event) throws InterruptedException {
    Action action = event.getAction();
    CriticalPathComponent component =
        Preconditions.checkNotNull(
            outputArtifactToComponent.get(action.getPrimaryOutput()), action);
    finalizeActionStat(
        event.getRelativeActionStartTimeNanos(), event.getFinishTimeNanos(), action, component, "");
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionChangePruned(ActionChangePrunedEvent event) throws InterruptedException {
    if (graph == null) {
      return;
    }

    var actionLookupData = event.actionLookupData();
    if (!(Actions.getAction(graph, actionLookupData) instanceof Action action)) {
      return;
    }

    var component = tryAddComponent(createComponent(action, event.finishTimeNanos()));
    finalizeActionStat(
        event.finishTimeNanos(), event.finishTimeNanos(), action, component, "change pruned");
  }

  /**
   * Record that the failed rewound action is no longer running. The action may or may not start
   * again later.
   *
   * <p>The failed execution keeps its dependencies on the generating actions of its inputs, and the
   * actions that are rewound to regenerate the lost inputs execute again because of it, so their
   * next execution depends on the failed execution. This keeps the time lost to rewinding on the
   * critical path.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionRewound(ActionRewoundEvent event) {
    Action action = event.getFailedRewoundAction();
    CriticalPathComponent component =
        Preconditions.checkNotNull(outputArtifactToComponent.get(action.getPrimaryOutput()));
    finalizeActionStat(
        event.getRelativeActionStartTimeNanos(),
        event.getRelativeActionFinishTimeNanos(),
        action,
        component,
        "action rewound");
    for (ActionAnalysisMetadata dep : event.getDepsToRewind()) {
      Artifact primaryOutput = dep.getPrimaryOutput();
      if (primaryOutput != null) {
        rewindTriggers
            .computeIfAbsent(primaryOutput, unused -> new ConcurrentLinkedQueue<>())
            .add(component);
      }
    }
  }

  /** Maximum critical path component found during the build. */
  CriticalPathComponent getMaxCriticalPath() {
    return maxCriticalPath.get();
  }

  private void finalizeActionStat(
      long startTimeNanos,
      long finishTimeNanos,
      Action action,
      CriticalPathComponent component,
      String finalizeReason) {
    for (Artifact input : action.getInputs().toList()) {
      addArtifactDependency(component, input, finishTimeNanos);
    }
    ImmutableList<CriticalPathComponent> predecessors = executionPredecessors.remove(component);
    if (predecessors != null) {
      for (CriticalPathComponent predecessor : predecessors) {
        if (!predecessor.isRunning()) {
          component.addDepInfo(predecessor, finishTimeNanos);
        }
      }
    }
    if (Duration.ofNanos(finishTimeNanos - startTimeNanos).compareTo(Duration.ofMillis(-5)) < 0) {
      // See note in {@link Clock#nanoTime} about non increasing subsequent #nanoTime calls.
      logger.atWarning().withStackTrace(StackSize.MEDIUM).log(
          "Negative duration time for [%s] %s with start: %s, finish: %s.",
          action.getMnemonic(), action.getPrimaryOutput(), startTimeNanos, finishTimeNanos);
    }
    component.finishActionExecution(startTimeNanos, finishTimeNanos, finalizeReason);
    maxCriticalPath.accumulateAndGet(component, SELECT_LONGER_COMPONENT);

    if (isTemplateExpansionAction(action)) {
      for (Artifact output : action.getOutputs()) {
        if ((output instanceof TreeFileArtifact treeFileArtifact
                && !treeFileArtifact.isChildOfDeclaredDirectory())
            || output.isSubTreeArtifact()) {
          // If this action generates a template expansion TreeFileArtifact or sub-TreeArtifact,
          // the parent TreeArtifact is an output of action template expansion, and is not a direct
          // output of an action. As such, we need to keep track of the longest critical path of
          // this
          // parent TreeArtifact by updating the longest component for all whenever a template
          // action
          // completes.
          Artifact parent = output.getParent();
          while (parent != null) {
            outputArtifactToComponent.merge(parent, component, SELECT_LONGER_COMPONENT);
            parent = parent.hasParent() ? parent.getParent() : null;
          }
        }
      }
    }
  }

  private static boolean isTemplateExpansionAction(Action action) {
    Artifact primaryOutput = action.getPrimaryOutput();
    return primaryOutput instanceof DerivedArtifact derivedArtifact
        && derivedArtifact.hasGeneratingActionKey()
        && derivedArtifact.getGeneratingActionKey().getActionLookupKey()
            instanceof ActionTemplateExpansionKey;
  }

  /** If "input" is a generated artifact, link its critical path to the one we're building. */
  private void addArtifactDependency(
      CriticalPathComponent actionStats, Artifact input, long componentFinishNanos) {
    CriticalPathComponent depComponent = outputArtifactToComponent.get(input);
    if (depComponent != null && !depComponent.isRunning()) {
      actionStats.addDepInfo(depComponent, componentFinishNanos);
    }
    if (input.hasParent()) {
      // If the input is a nested artifact (e.g. a TreeFileArtifact), check its parent chain
      // (e.g. parent TreeArtifact). Sibling template expansion actions may take longer to finish
      // before the directory is available, so consider non-running parent components as potential
      // dependency bottlenecks as well.
      Artifact parent = input.getParent();
      while (parent != null) {
        CriticalPathComponent parentComponent = outputArtifactToComponent.get(parent);
        if (parentComponent != null && !parentComponent.isRunning()) {
          actionStats.addDepInfo(parentComponent, componentFinishNanos);
        }
        parent = parent.hasParent() ? parent.getParent() : null;
      }
    }
  }
}
