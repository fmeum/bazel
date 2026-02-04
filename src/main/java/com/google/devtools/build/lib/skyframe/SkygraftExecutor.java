package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassId;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public final class SkygraftExecutor extends AbstractQueueVisitor {

  private final MemoizingEvaluator evaluator;
  private final Map<Label, Object> newStarlarkOptionValues;
  private final Set<ConfiguredTargetKey> affectedCts = Sets.newConcurrentHashSet();

  private final Map<RuleClassId, Boolean> affectedRuleClasses = new ConcurrentHashMap<>();

  // Tracks the number of grafted (copied) values
  private final LongAdder graftedCount = new LongAdder();

  private SkygraftExecutor(
      MemoizingEvaluator evaluator, Map<Label, Object> newStarlarkOptionValues) {
    super(
        Runtime.getRuntime().availableProcessors(),
        /* keepAliveTime= */ 2,
        MINUTES,
        ExceptionHandlingMode.FAIL_FAST,
        /* poolName= */ "skygraft",
        ErrorClassifier.DEFAULT);
    this.evaluator = evaluator;
    this.newStarlarkOptionValues = newStarlarkOptionValues;
  }

  /**
   * Executes the skygraft process: identifies ConfiguredTargetKeys that are not transitively
   * affected by the changed starlark options and copies their values to new keys with updated
   * BuildOptions.
   *
   * @param evaluator the Skyframe evaluator containing the graph
   * @param changedStarlarkOptionValues map from starlark option labels to their new values
   */
  public static void execute(
      MemoizingEvaluator evaluator, Map<Label, Object> changedStarlarkOptionValues)
      throws InterruptedException {
    new SkygraftExecutor(evaluator, changedStarlarkOptionValues).run();
  }

  private void run() throws InterruptedException {
    // Phase 1: Find affected leaves and kick off upward propagation
    LongAdder allCtsCount = new LongAdder();
    Set<ConfiguredTargetKey> directlyAffectedCts = Sets.newConcurrentHashSet();
    evaluator
        .getInMemoryGraph()
        .parallelForEach(
            node -> {
              if (node.getKey() instanceof ConfiguredTargetKey ctk) {
                allCtsCount.increment();
                checkState(node.isDone(), "ConfiguredTargetKey not done: %s", ctk);
                if (isDirectlyAffected(node)) {
                  directlyAffectedCts.add(ctk);
                  markAffectedAndPropagateUp(ctk);
                }
              }
            });
    awaitQuiescenceWithoutShutdown(false);

    // Phase 2: Copy values for unaffected CTKs to new keys with updated BuildOptions
    evaluator
        .getInMemoryGraph()
        .parallelForEach(
            node -> {
              if (node.getKey() instanceof ConfiguredTargetKey ctk
                  && !affectedCts.contains(ctk)
                  && node.isDone()) {
                execute(() -> graftValue(ctk, node));
              }
            });
    awaitQuiescence(true);

    System.err.printf(
        "Options %s changed; affected %d targets (%d directly) out of %d total; grafted %d (%.2f%%): %s%n",
        newStarlarkOptionValues.keySet(),
        affectedCts.size(),
        directlyAffectedCts.size(),
        allCtsCount.sum(),
        graftedCount.sum(),
        100.0 * graftedCount.sum() / allCtsCount.sum(),
        directlyAffectedCts.stream()
            .map(ConfiguredTargetKey::getLabel)
            .map(Label::toString)
            .sorted()
            .distinct()
            .collect(joining(", ")));
  }

  /**
   * Copies the value from an existing ConfiguredTargetKey to a new key with updated BuildOptions.
   */
  private void graftValue(ConfiguredTargetKey oldCtk, InMemoryNodeEntry oldEntry) {
    BuildConfigurationKey oldConfigKey = oldCtk.getConfigurationKey();
    if (oldConfigKey == null) {
      // Null configuration (e.g., for source files) - no need to graft
      return;
    }

    // Create new BuildOptions with updated starlark option values
    BuildOptions oldOptions = oldConfigKey.getOptions();
    BuildOptions newOptions =
        oldOptions.toBuilder().addStarlarkOptions(newStarlarkOptionValues).build();

    // If options are the same (no actual change), skip
    if (oldOptions.equals(newOptions)) {
      return;
    }

    // Create new configuration key and ConfiguredTargetKey
    BuildConfigurationKey newConfigKey = BuildConfigurationKey.create(newOptions);
    ConfiguredTargetKey newCtk = oldCtk.toBuilder().setConfigurationKey(newConfigKey).build();

    // Get the value from the old entry (including any metadata).
    SkyValue value = oldEntry.getValueMaybeWithMetadata();
    if (value == null) {
      return;
    }

    // Inject the value into the graph under the new key
    try {
      injectValue(newCtk, value);
      graftedCount.increment();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Injects a value into the graph under the given key. The node is created with no dependencies
   * and marked as done.
   */
  private void injectValue(ConfiguredTargetKey key, SkyValue value) throws InterruptedException {
    InMemoryGraph graph = evaluator.getInMemoryGraph();

    // Create the node if it doesn't exist
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableSet.of(key));
    NodeEntry entry = graph.get(null, Reason.OTHER, key);
    if (entry == null) {
      return;
    }

    // If already done, don't overwrite
    if (entry.isDone()) {
      return;
    }

    // Initialize the node for evaluation
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();

    // Set the value - this marks the node as done
    entry.setValue(value, Version.constant(), null);
  }

  private boolean isDirectlyAffected(InMemoryNodeEntry entry) {
    if (!(entry.getValue() instanceof RuleConfiguredTargetValue rctv)) {
      return false;
    }
    var label = rctv.getConfiguredTarget().getLabel();
    // The *_flag target itself.
    if (newStarlarkOptionValues.containsKey(label)) {
      return true;
    }
    var configMatchingProvider =
        rctv.getConfiguredTarget().getProvider(ConfigMatchingProvider.class);
    if (configMatchingProvider != null
        && configMatchingProvider.flagSettingsMap().containsKey(label)) {
      return true;
    }
    // TODO: Handle transitions gracefully.
    if (hasAffectedTransition(rctv)) {
      throw new UnsupportedOperationException(
          "Transitions not yet supported in Skygraft analysis: " + label);
    }
    return false;
  }

  private Rule getRule(RuleConfiguredTargetValue rctv) {
    var label = rctv.getConfiguredTarget().getLabel();
    PackageValue packageValue;
    try {
      packageValue = (PackageValue) evaluator.getExistingValue(label.getPackageIdentifier());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during Skygraft analysis: " + label, e);
    }
    Rule rule;
    try {
      rule = packageValue.getPackageoid().getTarget(label.getName()).getAssociatedRule();
    } catch (NoSuchTargetException e) {
      throw new IllegalStateException("Target disappeared during Skygraft analysis: " + label, e);
    }
    return rule;
  }

  private boolean hasAffectedTransition(RuleConfiguredTargetValue rctv) {
    return affectedRuleClasses.computeIfAbsent(
        rctv.getConfiguredTarget().getRuleClassId(),
        unused -> {
          var ruleClass = getRule(rctv).getRuleClassObject();
          if (isAffectedTransition(ruleClass.getTransitionFactory())) {
            return true;
          }
          for (Attribute attribute : ruleClass.getAttributeProvider().getAttributes()) {
            if (isAffectedTransition(attribute.getTransitionFactory())) {
              return true;
            }
          }
          return false;
        });
  }

  private boolean isAffectedTransition(@Nullable TransitionFactory<?> transitionFactory) {
    if (!(transitionFactory instanceof StarlarkRuleTransitionProvider starlarkTransitionFactory)) {
      return false;
    }
    return !Collections.disjoint(
        Lists.transform(
            starlarkTransitionFactory.getStarlarkDefinedConfigTransitionForTesting().getInputs(),
            Label::parseCanonicalUnchecked),
        newStarlarkOptionValues.keySet());
  }

  private void markAffectedAndPropagateUp(SkyKey key) {
    if (key instanceof ConfiguredTargetKey ctk) {
      if (!affectedCts.add(ctk)) {
        return; // Already visited
      }
    }

    InMemoryNodeEntry entry = evaluator.getInMemoryGraph().getIfPresent(key);
    if (entry == null || !entry.isDone()) {
      return;
    }

    for (SkyKey rdep : entry.getReverseDepsForDoneEntry()) {
      execute(() -> markAffectedAndPropagateUp(rdep));
    }
  }
}
