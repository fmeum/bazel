package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableMap;
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
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassId;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public final class SkygraftExecutor extends AbstractQueueVisitor {

  private static final Set<ConfiguredTargetKey> unaffectedCts = Sets.newConcurrentHashSet();
  private static Map<Label, Object> newStarlarkOptionValues = ImmutableMap.of();

  private final MemoizingEvaluator evaluator;
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
    SkygraftExecutor.unaffectedCts.clear();
    SkygraftExecutor.newStarlarkOptionValues = newStarlarkOptionValues;
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

  @Nullable
  public static ConfiguredTargetKey maybeGraft(ConfiguredTargetKey key) {
    if (!unaffectedCts.contains(key)) {
      return null;
    }
    BuildConfigurationKey oldConfigKey = key.getConfigurationKey();
    if (oldConfigKey == null) {
      return key;
    }
    BuildOptions oldOptions = oldConfigKey.getOptions();
    BuildOptions newOptions =
        oldOptions.toBuilder().addStarlarkOptions(newStarlarkOptionValues).build();
    if (oldOptions.equals(newOptions)) {
      return key;
    }
    BuildConfigurationKey newConfigKey = BuildConfigurationKey.create(newOptions);
    return key.toBuilder().setConfigurationKey(newConfigKey).build();
  }

  private void run() throws InterruptedException {
    // Phase 1: Find affected leaves and kick off upward propagation
    var start = Instant.now();
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
              if (node.getKey() instanceof ConfiguredTargetKey ctk && !affectedCts.contains(ctk)) {
                unaffectedCts.add(ctk);
              }
            });
    awaitQuiescence(true);

    evaluator.delete(affectedCts::contains);

    System.err.printf(
        "Options %s changed; affected %d targets (%d directly) out of %d total; grafted %d (%.2f%%) in %.3fs: %s%n",
        newStarlarkOptionValues.keySet(),
        affectedCts.size(),
        directlyAffectedCts.size(),
        allCtsCount.sum(),
        unaffectedCts.size(),
        100.0 * unaffectedCts.size() / allCtsCount.sum(),
        Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0,
        directlyAffectedCts.stream()
            .map(ConfiguredTargetKey::getLabel)
            .map(Label::toString)
            .sorted()
            .distinct()
            .collect(joining(", ")));
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

  private Rule getRule(RuleConfiguredTargetValue rctv) throws InterruptedException {
    var label = rctv.getConfiguredTarget().getLabel();
    var packageValue = (PackageValue) evaluator.getExistingValue(label.getPackageIdentifier());
    try {
      return packageValue.getPackageoid().getTarget(label.getName()).getAssociatedRule();
    } catch (NoSuchTargetException e) {
      throw new IllegalStateException("Target disappeared during Skygraft analysis: " + label, e);
    }
  }

  private boolean hasAffectedTransition(RuleConfiguredTargetValue rctv) {
    return affectedRuleClasses.computeIfAbsent(
        rctv.getConfiguredTarget().getRuleClassId(),
        unused -> {
          RuleClass ruleClass;
          try {
            ruleClass = getRule(rctv).getRuleClassObject();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
          }
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
