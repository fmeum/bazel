package com.google.devtools.build.lib.skyframe;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassId;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public final class SkygraftExecutor extends AbstractQueueVisitor {

  private final InMemoryMemoizingEvaluator evaluator;
  private final ImmutableSet<Label> starlarkOptions;
  private final Set<ConfiguredTargetKey> affectedCts = Sets.newConcurrentHashSet();
  private final Map<RuleClassId, Boolean> affectedRuleClasses = new ConcurrentHashMap<>();

  private SkygraftExecutor(
      InMemoryMemoizingEvaluator evaluator, ImmutableSet<Label> starlarkOptions) {
    super(
        Runtime.getRuntime().availableProcessors(),
        /* keepAliveTime= */ 2,
        MINUTES,
        ExceptionHandlingMode.FAIL_FAST,
        /* poolName= */ "skygraft",
        ErrorClassifier.DEFAULT);
    this.evaluator = evaluator;
    this.starlarkOptions = starlarkOptions;
  }

  public static void execute(
      InMemoryMemoizingEvaluator evaluator, ImmutableSet<Label> starlarkOptions)
      throws InterruptedException {
    var executor = new SkygraftExecutor(evaluator, starlarkOptions);
    executor.run();
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
                if (isDirectlyAffected(node)) {
                  directlyAffectedCts.add(ctk);
                  markAffectedAndPropagateUp(ctk);
                }
              }
            });
    awaitQuiescence(true);
    //    evaluator.delete(key -> key instanceof ConfiguredTargetKey ctk &&
    // affectedCts.contains(ctk));
    System.err.printf(
        "Options %s changed; affected %d targets (%d directly) out of %d total: %s%n",
        starlarkOptions,
        affectedCts.size(),
        directlyAffectedCts.size(),
        allCtsCount.sum(),
        directlyAffectedCts.stream()
            .map(ConfiguredTargetKey::getLabel)
            .map(Label::toString)
            .collect(joining(", ")));
  }

  private boolean isDirectlyAffected(InMemoryNodeEntry entry) {
    if (!entry.isDone()) {
      return false;
    }
    if (!(entry.getValue() instanceof RuleConfiguredTargetValue rctv)) {
      return false;
    }
    var label = rctv.getConfiguredTarget().getLabel();
    // The *_flag target itself.
    if (starlarkOptions.contains(label)) {
      return true;
    }
    var configMatchingProvider =
        rctv.getConfiguredTarget().getProvider(ConfigMatchingProvider.class);
    if (configMatchingProvider != null
        && configMatchingProvider.flagSettingsMap().containsKey(label)) {
      return true;
    }
    //    // Any select condition that depends on a changed Starlark option.
    //    for (var configCondition : rctv.getConfiguredTarget().getConfigConditions().values()) {
    //      if (!Collections.disjoint(configCondition.flagSettingsMap().keySet(), starlarkOptions))
    // {
    //        return true;
    //      }
    //    }
    //    boolean isRuleClassAffected =
    //        affectedRuleClasses.computeIfAbsent(
    //            rctv.getConfiguredTarget().getRuleClassId(),
    //            unused -> {
    //              var ruleClass = getRule(rctv).getRuleClassObject();
    //              return ruleClass.getBuildSetting() != null;
    //            });
    //    if (isRuleClassAffected) {
    //      return true;
    //    }
    var rule = getRule(rctv);
    rule.getRuleClassObject().getTransitionFactory()
  }

  private Rule getRule(RuleConfiguredTargetValue rctv) {
    var label = rctv.getConfiguredTarget().getLabel();
    var packageValue = (PackageValue) evaluator.getExistingValue(label.getPackageIdentifier());
    Rule rule;
    try {
      rule = packageValue.getPackageoid().getTarget(label.getName()).getAssociatedRule();
    } catch (NoSuchTargetException e) {
      throw new IllegalStateException("Target disappeared during Skygraft analysis: " + label, e);
    }
    return rule;
  }

  private boolean isAffectedTransition(@Nullable TransitionFactory<?> transitionFactory) {
    if (!(transitionFactory instanceof StarlarkRuleTransitionProvider starlarkTransitionFactory)) {
      return false;
    }
    return starlarkTransitionFactory.getStarlarkDefinedConfigTransitionForTesting().getInputs()
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
