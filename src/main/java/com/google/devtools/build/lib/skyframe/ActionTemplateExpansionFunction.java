// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CompletionContext.PathResolverFactory;
import com.google.devtools.build.lib.actions.ImportantOutputHandler.LostArtifacts;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindException;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindStrategy;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The SkyFunction for {@link ActionTemplateExpansionValue}.
 *
 * <p>Given an action template, this function resolves its input TreeArtifact, then expands the
 * action template into a list of actions using the expanded {@link TreeFileArtifact}s under the
 * input TreeArtifact.
 */
public class ActionTemplateExpansionFunction implements SkyFunction {
  private final ActionKeyContext actionKeyContext;
  private final PathResolverFactory pathResolverFactory;
  private final SkyframeActionExecutor skyframeActionExecutor;
  private final ActionRewindStrategy actionRewindStrategy;

  @VisibleForTesting
  ActionTemplateExpansionFunction(
      ActionKeyContext actionKeyContext,
      PathResolverFactory pathResolverFactory,
      SkyframeActionExecutor skyframeActionExecutor,
      ActionRewindStrategy actionRewindStrategy) {
    this.actionKeyContext = actionKeyContext;
    this.pathResolverFactory = pathResolverFactory;
    this.skyframeActionExecutor = skyframeActionExecutor;
    this.actionRewindStrategy = actionRewindStrategy;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ActionTemplateExpansionFunctionException, InterruptedException {
    ActionTemplateExpansionKey key = (ActionTemplateExpansionKey) skyKey.argument();
    ActionLookupValue value = (ActionLookupValue) env.getValue(key.getActionLookupKey());
    if (value == null) {
      // Because of the phase boundary separating analysis and execution, all needed
      // ActionLookupValues must have already been evaluated, so a missing ActionLookupValue is
      // unexpected. However, we tolerate this case.
      BugReport.sendBugReport(new IllegalStateException("Unexpected absent value for " + key));
      return null;
    }
    ActionTemplate<?> actionTemplate = value.getActionTemplate(key.getActionIndex());

    ImmutableSet.Builder<SkyKey> inputKeysBuilder =
        ImmutableSet.<SkyKey>builder().addAll(actionTemplate.getInputTreeArtifacts());

    // Following b/143205147, we unwrap the top layer of the NestedSet and evaluate the first layer
    // of the NestedSet as direct Artifact(s) and transitive NestedSet(s).
    if (!actionTemplate.getInputs().isEmpty()) {
      for (Artifact leaf : actionTemplate.getInputs().getLeaves()) {
        inputKeysBuilder.add(Artifact.key(leaf));
      }
      for (NestedSet<Artifact> nonLeaf : actionTemplate.getInputs().getNonLeaves()) {
        inputKeysBuilder.add(ArtifactNestedSetKey.create(nonLeaf));
      }
    }
    ImmutableSet<SkyKey> inputKeys = inputKeysBuilder.build();

    SkyframeLookupResult result = env.getValuesAndExceptions(inputKeys);

    // Input TreeArtifact is not ready yet.
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableList.Builder<TreeFileArtifact> inputTreeFileArtifacts = ImmutableList.builder();
    ImmutableMap.Builder<SpecialArtifact, TreeArtifactValue> inputTreeArtifactValues =
        ImmutableMap.builder();
    for (SpecialArtifact inputTreeArtifact : actionTemplate.getInputTreeArtifacts()) {
      TreeArtifactValue treeArtifactValue;
      try {
        treeArtifactValue =
            (TreeArtifactValue)
                result.getOrThrow(inputTreeArtifact, ActionExecutionException.class);
      } catch (ActionExecutionException e) {
        throw reportAndWrapFailedExpansion(actionTemplate, e, env);
      }
      // b/507424770#comment10: To handle the case of a wrongly bubbled up exception causing a
      // null value, we return null here so that we don't crash with an NPE.
      if (treeArtifactValue == null) {
        return null;
      }
      inputTreeFileArtifacts.addAll(treeArtifactValue.getChildren());
      inputTreeArtifactValues.put(inputTreeArtifact, treeArtifactValue);
    }

    TreeFileArtifactReader inputFileReader =
        new TreeFileArtifactReader(inputTreeArtifactValues.buildOrThrow());
    ImmutableList<ActionAnalysisMetadata> actions;
    try {
      // Expand the action template using the list of expanded input TreeFileArtifacts.
      // TODO(rduan): Add a check to verify the inputs of expanded actions are subsets of inputs
      // of the ActionTemplate.
      actions =
          generateAndValidateActionsFromTemplate(
              actionTemplate,
              inputTreeFileArtifacts.build(),
              key,
              inputFileReader,
              env.getListener());
    } catch (ActionExecutionException e) {
      LostArtifacts lostInputs = inputFileReader.getLostInputs();
      if (!lostInputs.isEmpty()) {
        // The expansion failed because inputs it read are no longer available remotely. Rewind
        // their generating actions instead of failing the build.
        return handleLostInputs(
            key, actionTemplate, inputKeys, lostInputs, inputFileReader.getInputMetadata(), env);
      }
      throw reportAndWrapFailedExpansion(actionTemplate, e, env);
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ActionTemplateExpansionFunctionException(e);
    }
    try {
      checkActionAndArtifactConflicts(actions, key);
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ActionTemplateExpansionFunctionException(e);
    } catch (Actions.ArtifactGeneratedByOtherRuleException e) {
      throw new IllegalStateException(
          "Actions generated by template "
              + actionTemplate.describe()
              + " did not all output tree file artifacts belonging to the correct output tree"
              + " artifact + ("
              + skyKey
              + ")",
          e);
    }

    return new ActionTemplateExpansionValue(actions);
  }

  private static ActionTemplateExpansionFunctionException reportAndWrapFailedExpansion(
      ActionTemplate<?> actionTemplate, ActionExecutionException e, Environment env) {
    env.getListener()
        .handle(
            Event.error(
                actionTemplate.getOwner().getLocation(),
                actionTemplate.describe() + " failed: " + e.getMessage()));
    return new ActionTemplateExpansionFunctionException(
        new AlreadyReportedActionExecutionException(e));
  }

  /**
   * Returns a {@link SkyFunction.Reset} that rewinds the generating actions of the given lost
   * inputs, or null if the rewind plan is not ready yet, in which case this function is restarted.
   */
  @Nullable
  private SkyValue handleLostInputs(
      ActionTemplateExpansionKey key,
      ActionTemplate<?> actionTemplate,
      ImmutableSet<SkyKey> inputKeys,
      LostArtifacts lostInputs,
      InputMetadataProvider inputMetadataProvider,
      Environment env)
      throws ActionTemplateExpansionFunctionException, InterruptedException {
    try {
      return actionRewindStrategy
          .prepareRewindPlanForLostTemplateExpansionInputs(
              key, actionTemplate, inputKeys, lostInputs.byDigest(), inputMetadataProvider, env)
          .toNullIfMissingDependenciesElseReset();
    } catch (ActionRewindException e) {
      throw reportAndWrapFailedExpansion(
          actionTemplate,
          new ActionExecutionException(
              e, actionTemplate, /* catastrophe= */ false, e.getDetailedExitCode()),
          env);
    }
  }

  /**
   * Reads the contents of the children of the input tree artifacts of an {@link ActionTemplate}.
   *
   * <p>Reads go through an {@linkplain
   * com.google.devtools.build.lib.vfs.OutputService#createPathResolverForArtifactValues action
   * filesystem} if one is in use, which transparently downloads the contents of files that are only
   * available remotely.
   */
  private final class TreeFileArtifactReader implements ActionTemplate.InputFileReader {

    private final ImmutableMap<SpecialArtifact, TreeArtifactValue> inputTreeArtifactValues;

    // Most templates never read their inputs, so only set up the machinery to do so on first use.
    private final Supplier<ActionInputMap> inputMetadata = Suppliers.memoize(this::createInputMap);
    private final Supplier<ArtifactPathResolver> pathResolver =
        Suppliers.memoize(this::createPathResolver);

    TreeFileArtifactReader(
        ImmutableMap<SpecialArtifact, TreeArtifactValue> inputTreeArtifactValues) {
      this.inputTreeArtifactValues = inputTreeArtifactValues;
    }

    // The filesystem the reads went through, which records any inputs lost while reading them.
    // Reads happen on the single thread that runs the template's implementation function.
    @Nullable private FileSystem fileSystem;

    @Override
    public byte[] read(TreeFileArtifact file) throws IOException {
      Path path = pathResolver.get().toPath(file);
      fileSystem = path.getFileSystem();
      return FileSystemUtils.readContent(path);
    }

    /** Returns the artifacts that were found to be lost while serving a {@link #read}. */
    LostArtifacts getLostInputs() {
      return fileSystem == null
          ? LostArtifacts.EMPTY
          : skyframeActionExecutor.getLostArtifacts(fileSystem);
    }

    InputMetadataProvider getInputMetadata() {
      return inputMetadata.get();
    }

    private ActionInputMap createInputMap() {
      ActionInputMap inputMap = new ActionInputMap(inputTreeArtifactValues.size());
      for (Map.Entry<SpecialArtifact, TreeArtifactValue> entry :
          inputTreeArtifactValues.entrySet()) {
        ActionInputMapHelper.addToMap(
            inputMap, entry.getKey(), entry.getValue(), MetadataConsumerForMetrics.NO_OP);
      }
      return inputMap;
    }

    private ArtifactPathResolver createPathResolver() {
      return pathResolverFactory.createPathResolverForArtifactValues(inputMetadata.get());
    }
  }

  /** Exception thrown by {@link ActionTemplateExpansionFunction}. */
  private static final class ActionTemplateExpansionFunctionException extends SkyFunctionException {
    ActionTemplateExpansionFunctionException(ActionConflictException e) {
      super(e, Transience.PERSISTENT);
    }

    ActionTemplateExpansionFunctionException(ActionExecutionException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  private static ImmutableList<ActionAnalysisMetadata> generateAndValidateActionsFromTemplate(
      ActionTemplate<?> actionTemplate,
      ImmutableList<TreeFileArtifact> inputTreeFileArtifacts,
      ActionTemplateExpansionKey key,
      ActionTemplate.InputFileReader inputFileReader,
      EventHandler eventHandler)
      throws ActionConflictException, ActionExecutionException, InterruptedException {
    Collection<Artifact> outputs = actionTemplate.getOutputs();
    for (Artifact output : outputs) {
      Preconditions.checkState(
          output.isTreeArtifact(),
          "%s declares an output which is not a tree artifact: %s",
          actionTemplate,
          output);
    }
    ImmutableList<? extends Action> actions =
        actionTemplate.generateActionsForInputArtifacts(
            inputTreeFileArtifacts, key, inputFileReader, eventHandler);
    for (Action action : actions) {
      for (Artifact output : action.getOutputs()) {
        Preconditions.checkState(
            output.getArtifactOwner().equals(key),
            "%s generated an action with an output owned by the wrong owner %s not %s (%s)",
            actionTemplate,
            output.getArtifactOwner(),
            key,
            action);
        Preconditions.checkState(
            output.hasParent(),
            "%s generated an action which outputs a non-TreeFileArtifact %s (%s)",
            actionTemplate,
            output,
            action);
        SpecialArtifact outputTree =
            output.getParent().isSubTreeArtifact()
                ? output.getParent().getParent()
                : output.getParent();
        Preconditions.checkState(
            outputs.contains(outputTree),
            "%s generated an action with an output %s under an undeclared tree not in %s (%s)",
            actionTemplate,
            output,
            outputs,
            action);
      }
    }
    return ImmutableList.copyOf(actions); // Just a cast, no copy performed.
  }

  private void checkActionAndArtifactConflicts(
      ImmutableList<ActionAnalysisMetadata> actions, ActionTemplateExpansionKey key)
      throws ActionConflictException,
          InterruptedException,
          Actions.ArtifactGeneratedByOtherRuleException {
    Actions.assignOwnersAndThrowIfConflict(actionKeyContext, actions, key);
    Map<ActionAnalysisMetadata, ActionConflictException> artifactPrefixConflictMap =
        findArtifactPrefixConflicts(getMapForConsistencyCheck(actions));

    if (!artifactPrefixConflictMap.isEmpty()) {
      throw artifactPrefixConflictMap.values().iterator().next();
    }
  }

  private static ImmutableMap<Artifact, ActionAnalysisMetadata> getMapForConsistencyCheck(
      List<? extends ActionAnalysisMetadata> actions) {
    if (actions.isEmpty()) {
      return ImmutableMap.of();
    }
    HashMap<Artifact, ActionAnalysisMetadata> result =
        Maps.newHashMapWithExpectedSize(actions.size() * actions.get(0).getOutputs().size());
    for (ActionAnalysisMetadata action : actions) {
      for (Artifact output : action.getOutputs()) {
        result.put(output, action);
      }
    }
    return ImmutableMap.copyOf(result);
  }

  /**
   * Finds Artifact prefix conflicts between generated artifacts. An artifact prefix conflict
   * happens if one action generates an artifact whose path is a prefix of another artifact's path.
   * Those two artifacts cannot exist simultaneously in the output tree.
   *
   * @param generatingActions a map between generated artifacts and their associated generating
   *     actions.
   * @return a map between actions that generated the conflicting artifacts and their associated
   *     {@link ActionConflictException}.
   */
  private static Map<ActionAnalysisMetadata, ActionConflictException> findArtifactPrefixConflicts(
      Map<Artifact, ActionAnalysisMetadata> generatingActions) {
    return Actions.findArtifactPrefixConflicts(
        new MapBasedImmutableActionGraph(generatingActions), generatingActions.keySet());
  }

  private static class MapBasedImmutableActionGraph implements ActionGraph {
    private final Map<Artifact, ActionAnalysisMetadata> generatingActions;

    MapBasedImmutableActionGraph(Map<Artifact, ActionAnalysisMetadata> generatingActions) {
      this.generatingActions = ImmutableMap.copyOf(generatingActions);
    }

    @Nullable
    @Override
    public ActionAnalysisMetadata getGeneratingAction(Artifact artifact) {
      return generatingActions.get(artifact);
    }
  }
}
