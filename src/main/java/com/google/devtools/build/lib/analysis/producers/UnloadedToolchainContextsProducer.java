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
package com.google.devtools.build.lib.analysis.producers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.config.ConfigurationTransitionEvent;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionCreationException;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.DeclaredExecGroup;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.BaseTargetPrerequisitesSupplier;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.NoMatchingPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextKey;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Determines {@code ToolchainCollection<UnloadedToolchainContext>} from {@link
 * UnloadedToolchainContextsInputs}.
 *
 * <p>If any of the required toolchain types has a configuration transition (see {@link
 * ToolchainTypeRequirement#transitionFactory()}), the transition is applied first so that the
 * toolchain type is resolved in the transitioned configuration.
 */
public final class UnloadedToolchainContextsProducer implements StateMachine {
  /** Interface for accepting values produced by this class. */
  public interface ResultSink {
    void acceptUnloadedToolchainContexts(
        @Nullable ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts);

    void acceptUnloadedToolchainContextsError(ToolchainException error);
  }

  /**
   * Data required to apply the configuration transitions of toolchain types with a {@code cfg}.
   *
   * @param label the label of the target requiring the toolchains, used for error reporting
   * @param attributes the configured attributes of the rule requiring the toolchains, which the
   *     transitions may read, or {@code null} if they aren't available (for aspects)
   * @param transitionCache the cache for Starlark transition applications
   * @param eventHandler the handler for events emitted while applying transitions
   */
  public record ToolchainTypeTransitionData(
      Label label,
      @Nullable ConfiguredAttributeMapper attributes,
      StarlarkTransitionCache transitionCache,
      ExtendedEventHandler eventHandler) {}

  // -------------------- Input --------------------
  private final UnloadedToolchainContextsInputs unloadedToolchainContextsInputs;

  /**
   * Required to apply the configuration transitions of toolchain types. Must not be null if {@link
   * UnloadedToolchainContextsInputs#hasToolchainTypeTransitions()} is true.
   */
  @Nullable private final ToolchainTypeTransitionData transitionData;

  /**
   * Cache for {@link UnloadedToolchainContext}. Not null only for aspects evaluation.
   *
   * <p>Check {@link AspectFunction#baseTargetPrerequisitesSupplier} for more details
   */
  @Nullable private final BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier;

  // -------------------- Output --------------------
  private final ResultSink sink;

  // -------------------- Sequencing --------------------
  private final StateMachine runAfter;

  // -------------------- Internal State --------------------
  /**
   * The configurations resulting from applying the transitions of the toolchain types, keyed by
   * transition factory (as the same transition may be shared by multiple toolchain types).
   */
  private final Map<TransitionFactory<?>, BuildConfigurationKey> transitionedConfigurations =
      new LinkedHashMap<>();

  private ToolchainCollection.Builder<UnloadedToolchainContext> toolchainContextsBuilder;
  private boolean toolchainContextsHasError = false;

  UnloadedToolchainContextsProducer(
      UnloadedToolchainContextsInputs unloadedToolchainContextsInputs,
      @Nullable ToolchainTypeTransitionData transitionData,
      ResultSink sink,
      StateMachine runAfter) {
    this(
        unloadedToolchainContextsInputs,
        transitionData,
        /* baseTargetPrerequisitesSupplier= */ null,
        sink,
        runAfter);
  }

  /**
   * Constructor for {@link UnloadedToolchainContextsProducer} with {@code
   * baseTargetPrerequisitesSupplier} used by {@link AspectFunction}.
   */
  public UnloadedToolchainContextsProducer(
      UnloadedToolchainContextsInputs unloadedToolchainContextsInputs,
      @Nullable ToolchainTypeTransitionData transitionData,
      @Nullable BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier,
      ResultSink sink,
      StateMachine runAfter) {
    this.unloadedToolchainContextsInputs = unloadedToolchainContextsInputs;
    this.transitionData = transitionData;
    this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier;
    this.sink = sink;
    this.runAfter = runAfter;
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    var defaultToolchainContextKey = unloadedToolchainContextsInputs.targetToolchainContextKey();
    if (defaultToolchainContextKey == null) {
      // Doesn't use toolchain resolution and short-circuits.
      // TODO(bazel-team): return empty {@link ToolchainCollection} instead of {@code null} to help
      // consumers distinguish between not yet evaluated collections and collections evaluated to be
      // empty.
      sink.acceptUnloadedToolchainContexts(null);
      return runAfter;
    }

    if (unloadedToolchainContextsInputs.hasToolchainTypeTransitions()) {
      return applyToolchainTypeTransitions(tasks);
    }
    return lookupToolchainContexts(tasks);
  }

  /**
   * Applies the configuration transitions of all toolchain types that have one, starting from the
   * configuration of the toolchain context.
   */
  private StateMachine applyToolchainTypeTransitions(Tasks tasks) {
    checkNotNull(
        transitionData, "toolchain types with transitions require ToolchainTypeTransitionData");
    BuildConfigurationKey fromConfiguration =
        unloadedToolchainContextsInputs.targetToolchainContextKey().configurationKey();
    // The execution platform is not known before toolchain resolution and exec transitions are
    // rejected when the toolchain type is declared.
    AttributeTransitionData attributeTransitionData =
        AttributeTransitionData.builder().attributes(transitionData.attributes()).build();

    for (ToolchainTypeRequirement toolchainType : getAllToolchainTypes()) {
      TransitionFactory<?> transitionFactory = toolchainType.transitionFactory();
      if (transitionFactory == null || transitionedConfigurations.containsKey(transitionFactory)) {
        continue;
      }
      // Reserve the slot so that the same transition is only applied once.
      transitionedConfigurations.put(transitionFactory, null);

      ConfigurationTransition transition;
      try {
        // See ToolchainTypeRequirement#transitionFactory() for why this cast is safe.
        @SuppressWarnings("unchecked")
        var attributeTransitionFactory =
            (TransitionFactory<AttributeTransitionData>) transitionFactory;
        transition = attributeTransitionFactory.create(attributeTransitionData);
      } catch (TransitionCreationException e) {
        handleError(new ToolchainTypeTransitionException(toolchainType, e.getMessage()));
        return runAfter;
      }
      tasks.enqueue(
          new TransitionApplier(
              transitionData.label(),
              fromConfiguration,
              transition,
              transitionData.transitionCache(),
              new TransitionSink(toolchainType),
              transitionData.eventHandler(),
              /* runAfter= */ DONE));
    }
    return this::lookupToolchainContextsAfterTransitions;
  }

  private Set<ToolchainTypeRequirement> getAllToolchainTypes() {
    Set<ToolchainTypeRequirement> toolchainTypes = new LinkedHashSet<>();
    toolchainTypes.addAll(
        unloadedToolchainContextsInputs.targetToolchainContextKey().toolchainTypes());
    for (DeclaredExecGroup execGroup : unloadedToolchainContextsInputs.execGroups().values()) {
      toolchainTypes.addAll(execGroup.toolchainTypes());
    }
    return toolchainTypes;
  }

  /** Receives the result of applying the transition of a single toolchain type. */
  private final class TransitionSink implements TransitionApplier.ResultSink {
    private final ToolchainTypeRequirement toolchainType;

    private TransitionSink(ToolchainTypeRequirement toolchainType) {
      this.toolchainType = toolchainType;
    }

    @Override
    public void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionedConfigurations) {
      if (transitionedConfigurations.size() != 1) {
        handleError(
            new ToolchainTypeTransitionException(
                toolchainType,
                String.format(
                    "the transition must not be a split transition, but it produced %d"
                        + " configurations",
                    transitionedConfigurations.size())));
        return;
      }
      UnloadedToolchainContextsProducer.this.transitionedConfigurations.put(
          toolchainType.transitionFactory(),
          Iterables.getOnlyElement(transitionedConfigurations.values()));
    }

    @Override
    public void acceptTransitionError(TransitionException e) {
      handleError(new ToolchainTypeTransitionException(toolchainType, e.getMessage()));
    }

    @Override
    public void acceptOptionsParsingError(OptionsParsingException e) {
      handleError(new ToolchainTypeTransitionException(toolchainType, e.getMessage()));
    }

    @Override
    public void acceptPlatformMappingError(PlatformMappingException e) {
      handleError(new ToolchainTypeTransitionException(toolchainType, e.getMessage()));
    }

    @Override
    public void acceptPlatformFlagsError(InvalidPlatformException e) {
      handleError(e);
    }

    @Override
    public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
      handleError(new ToolchainTypeTransitionException(toolchainType, e.getMessage()));
    }
  }

  private StateMachine lookupToolchainContextsAfterTransitions(Tasks tasks)
      throws InterruptedException {
    if (toolchainContextsHasError) {
      return runAfter;
    }
    BuildConfigurationKey fromConfiguration =
        unloadedToolchainContextsInputs.targetToolchainContextKey().configurationKey();
    for (BuildConfigurationKey toConfiguration :
        new LinkedHashSet<>(transitionedConfigurations.values())) {
      if (!toConfiguration.equals(fromConfiguration)) {
        transitionData
            .eventHandler()
            .post(
                ConfigurationTransitionEvent.create(
                    fromConfiguration.getOptionsChecksum(), toConfiguration.getOptionsChecksum()));
      }
    }
    return lookupToolchainContexts(tasks);
  }

  private StateMachine lookupToolchainContexts(Tasks tasks) throws InterruptedException {
    var defaultToolchainContextKey = unloadedToolchainContextsInputs.targetToolchainContextKey();
    this.toolchainContextsBuilder =
        ToolchainCollection.builderWithExpectedSize(
            unloadedToolchainContextsInputs.execGroups().size() + 1);

    lookupToolchainContext(
        baseTargetPrerequisitesSupplier,
        withToolchainTypeConfigurations(defaultToolchainContextKey),
        DEFAULT_EXEC_GROUP_NAME,
        tasks);

    var keyBuilder =
        ToolchainContextKey.key()
            .configurationKey(defaultToolchainContextKey.configurationKey())
            .debugTarget(defaultToolchainContextKey.debugTarget());

    for (Map.Entry<String, DeclaredExecGroup> entry :
        unloadedToolchainContextsInputs.execGroups().entrySet()) {
      var execGroup = entry.getValue();
      var key =
          keyBuilder
              .toolchainTypes(execGroup.toolchainTypes())
              .execConstraintLabels(execGroup.execCompatibleWith())
              .build();
      lookupToolchainContext(
          baseTargetPrerequisitesSupplier,
          withToolchainTypeConfigurations(key),
          entry.getKey(),
          tasks);
    }

    return this::buildToolchainContexts;
  }

  /**
   * Adds the transitioned configurations of the toolchain types with a transition to the key.
   *
   * <p>A transition that doesn't change the configuration is treated as if there were none.
   */
  private ToolchainContextKey withToolchainTypeConfigurations(ToolchainContextKey key) {
    if (transitionedConfigurations.isEmpty()) {
      return key;
    }
    ImmutableMap.Builder<Label, BuildConfigurationKey> toolchainTypeConfigurationKeys =
        ImmutableMap.builder();
    ImmutableSet.Builder<ToolchainTypeRequirement> toolchainTypes = ImmutableSet.builder();
    for (ToolchainTypeRequirement toolchainType : key.toolchainTypes()) {
      if (!toolchainType.hasTransition()) {
        toolchainTypes.add(toolchainType);
        continue;
      }
      // Resolution depends only on the resulting configuration, not the transition that produced
      // it. In particular, no-op transitions should reuse the ordinary resolution key.
      toolchainTypes.add(toolchainType.toBuilder().transitionFactory(null).build());
      BuildConfigurationKey toConfiguration =
          checkNotNull(transitionedConfigurations.get(toolchainType.transitionFactory()));
      if (!toConfiguration.equals(key.configurationKey())) {
        toolchainTypeConfigurationKeys.put(toolchainType.toolchainType(), toConfiguration);
      }
    }
    return key.toBuilder()
        .toolchainTypes(toolchainTypes.build())
        .toolchainTypeConfigurationKeys(toolchainTypeConfigurationKeys.buildOrThrow())
        .build();
  }

  private void lookupToolchainContext(
      @Nullable BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier,
      ToolchainContextKey key,
      String execGroupName,
      Tasks tasks)
      throws InterruptedException {
    var toolchainContext =
        baseTargetPrerequisitesSupplier == null
            ? null
            : baseTargetPrerequisitesSupplier.getUnloadedToolchainContext(key);

    if (toolchainContext != null) {
      new ToolchainContextLookupCallback(execGroupName)
          .acceptValueOrException(toolchainContext, null);
    } else {
      tasks.lookUp(
          key, ToolchainException.class, new ToolchainContextLookupCallback(execGroupName));
    }
  }

  private class ToolchainContextLookupCallback
      implements StateMachine.ValueOrExceptionSink<ToolchainException> {
    private final String execGroupName;

    private ToolchainContextLookupCallback(String execGroupName) {
      this.execGroupName = execGroupName;
    }

    @Override
    public void acceptValueOrException(
        @Nullable SkyValue value, @Nullable ToolchainException error) {
      if (value != null) {
        var unloadedToolchainContext = (UnloadedToolchainContext) value;
        var errorData = unloadedToolchainContext.errorData();
        if (errorData != null) {
          handleError(new NoMatchingPlatformException(errorData));
          return;
        }
        toolchainContextsBuilder.addContext(execGroupName, unloadedToolchainContext);
        return;
      }
      if (error != null) {
        handleError(error);
        return;
      }
      throw new IllegalArgumentException("both inputs were null");
    }
  }

  private void handleError(ToolchainException error) {
    if (!toolchainContextsHasError) { // Only propagates the first error.
      toolchainContextsHasError = true;
      sink.acceptUnloadedToolchainContextsError(error);
    }
  }

  private StateMachine buildToolchainContexts(Tasks tasks) {
    if (toolchainContextsHasError) {
      return runAfter;
    }
    sink.acceptUnloadedToolchainContexts(toolchainContextsBuilder.build());
    return runAfter;
  }

  /** Exception used when the configuration transition of a toolchain type fails. */
  private static final class ToolchainTypeTransitionException extends ToolchainException {
    ToolchainTypeTransitionException(ToolchainTypeRequirement toolchainType, String message) {
      super(
          String.format(
              "Error applying the 'cfg' transition of toolchain type %s: %s",
              toolchainType.toolchainType(), message));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_TOOLCHAIN_TYPE;
    }
  }
}
