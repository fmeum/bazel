// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import javax.annotation.Nullable;

/**
 * Represents the data needed for a specific target's use of toolchains and platforms, including
 * specific {@link ToolchainInfo} providers for each required toolchain type.
 */
@AutoValue
@Immutable
@ThreadSafe
public abstract class ResolvedToolchainContext
    implements ResolvedToolchainsDataInterface<ToolchainInfo> {

  /**
   * Finishes preparing the {@link ResolvedToolchainContext} by finding the specific toolchain
   * providers to be used for each toolchain type.
   *
   * @param toolchainTargets the toolchain dependencies, all in the configuration of the depending
   *     target. This overload can only be used if none of the toolchain types have a configuration
   *     transition, use {@link #load(UnloadedToolchainContext, String, String, Multimap)}
   *     otherwise.
   */
  public static ResolvedToolchainContext load(
      UnloadedToolchainContext unloadedToolchainContext,
      String targetDescription,
      ImmutableSet<ConfiguredTargetAndData> toolchainTargets)
      throws ToolchainException {
    Preconditions.checkArgument(
        unloadedToolchainContext.toolchainTypeConfigurations().isEmpty(),
        "Toolchain types with a configuration transition require loading by dependency kind: %s",
        unloadedToolchainContext.toolchainTypeConfigurations());
    ImmutableSetMultimap.Builder<ToolchainTypeInfo, ConfiguredTargetAndData> toolchainsByType =
        ImmutableSetMultimap.builder();
    for (ConfiguredTargetAndData target : toolchainTargets) {
      // Aliases are in toolchainTypeToResolved by the original alias label, not via the final
      // target's label.
      Label discoveredLabel = target.getConfiguredTarget().getOriginalLabel();
      for (ToolchainTypeInfo toolchainType :
          unloadedToolchainContext.toolchainTypeToResolved().inverse().get(discoveredLabel)) {
        toolchainsByType.put(toolchainType, target);
      }
    }
    return load(unloadedToolchainContext, targetDescription, toolchainsByType.build());
  }

  /**
   * Finishes preparing the {@link ResolvedToolchainContext} by finding the specific toolchain
   * providers to be used for each toolchain type among the dependencies of the depending target.
   *
   * <p>Toolchains of toolchain types with a configuration transition are built in the transitioned
   * configuration and thus have a different {@link DependencyKind} than the other toolchains of the
   * same execution group.
   *
   * @param execGroupName the name of the execution group of the {@code unloadedToolchainContext}
   * @param dependencies the dependencies of the depending target, keyed by dependency kind
   */
  public static ResolvedToolchainContext load(
      UnloadedToolchainContext unloadedToolchainContext,
      String targetDescription,
      String execGroupName,
      Multimap<DependencyKind, ConfiguredTargetAndData> dependencies)
      throws ToolchainException {
    ImmutableSetMultimap.Builder<ToolchainTypeInfo, ConfiguredTargetAndData> toolchainsByType =
        ImmutableSetMultimap.builder();
    for (var toolchainTypeToResolved :
        unloadedToolchainContext.toolchainTypeToResolved().asMap().entrySet()) {
      ToolchainTypeInfo toolchainType = toolchainTypeToResolved.getKey();
      DependencyKind dependencyKind =
          DependencyKind.forExecGroup(
              execGroupName,
              unloadedToolchainContext.toolchainTypeConfigurations().get(toolchainType));
      for (ConfiguredTargetAndData target : dependencies.get(dependencyKind)) {
        // Aliases are in toolchainTypeToResolved by the original alias label, not via the final
        // target's label.
        if (toolchainTypeToResolved
            .getValue()
            .contains(target.getConfiguredTarget().getOriginalLabel())) {
          toolchainsByType.put(toolchainType, target);
        }
      }
    }
    return load(unloadedToolchainContext, targetDescription, toolchainsByType.build());
  }

  private static ResolvedToolchainContext load(
      UnloadedToolchainContext unloadedToolchainContext,
      String targetDescription,
      ImmutableSetMultimap<ToolchainTypeInfo, ConfiguredTargetAndData> toolchainTargets)
      throws ToolchainException {

    ImmutableMap.Builder<ToolchainTypeInfo, ToolchainInfo> toolchainsBuilder =
        new ImmutableMap.Builder<>();
    ImmutableList.Builder<TemplateVariableInfo> templateVariableProviders =
        new ImmutableList.Builder<>();

    for (var entry : toolchainTargets.entries()) {
      ToolchainTypeInfo toolchainType = entry.getKey();
      ConfiguredTargetAndData target = entry.getValue();

      // If the toolchainType hadn't been resolved to an actual target, resolution would have
      // failed with an error much earlier. However, the target might still not be an actual
      // toolchain.
      ToolchainInfo toolchainInfo = PlatformProviderUtils.toolchain(target.getConfiguredTarget());
      if (toolchainInfo != null) {
        toolchainsBuilder.put(toolchainType, toolchainInfo);
      } else {
        throw new TargetNotToolchainException(
            toolchainType, target.getConfiguredTarget().getOriginalLabel());
      }

      // Find any template variables present for this toolchain.
      TemplateVariableInfo templateVariableInfo =
          target.getConfiguredTarget().get(TemplateVariableInfo.PROVIDER);
      if (templateVariableInfo != null) {
        templateVariableProviders.add(templateVariableInfo);
      }
    }

    ImmutableMap<ToolchainTypeInfo, ToolchainInfo> toolchains = toolchainsBuilder.buildOrThrow();

    // Verify that all mandatory toolchain type requirements are present.
    for (ToolchainTypeRequirement toolchainTypeRequirement :
        unloadedToolchainContext.toolchainTypes()) {
      if (toolchainTypeRequirement.mandatory()) {
        Label toolchainTypeLabel = toolchainTypeRequirement.toolchainType();
        ToolchainTypeInfo toolchainTypeInfo =
            unloadedToolchainContext.requestedLabelToToolchainType().get(toolchainTypeLabel);
        if (!toolchains.containsKey(toolchainTypeInfo)) {
          throw new MissingToolchainTypeRequirementException(toolchainTypeRequirement);
        }
      }
    }

    return new AutoValue_ResolvedToolchainContext(
        // super:
        unloadedToolchainContext.key(),
        unloadedToolchainContext.executionPlatform(),
        unloadedToolchainContext.targetPlatform(),
        unloadedToolchainContext.toolchainTypes(),
        unloadedToolchainContext.resolvedToolchainLabels(),
        // this:
        targetDescription,
        unloadedToolchainContext.requestedLabelToToolchainType(),
        toolchains,
        templateVariableProviders.build(),
        ImmutableSet.copyOf(toolchainTargets.values()));
  }

  public abstract ImmutableMap<ToolchainTypeInfo, ToolchainInfo> toolchains();

  /** Returns the template variables that these toolchains provide. */
  public abstract ImmutableList<TemplateVariableInfo> templateVariableProviders();

  /** Returns the actual prerequisites for this context, for use in validation. */
  public abstract ImmutableSet<ConfiguredTargetAndData> prerequisiteTargets();

  /**
   * Returns the toolchain for the given type, or {@code null} if the toolchain type was not
   * required in this context. Be careful if {@code ResolvedToolchainContext} is from the
   * default-exec-group (usually {@code RuleContext.getToolchainContext()}) because it will not have
   * toolchains after Automatic Exec Groups are enabled. In that case please use {@code
   * RuleContext.getToolchainInfo(toolchainTypeLabel)}.
   */
  @Override
  @Nullable
  public ToolchainInfo forToolchainType(Label toolchainTypeLabel) {
    ToolchainTypeInfo toolchainTypeInfo = requestedToolchainTypeLabels().get(toolchainTypeLabel);
    if (toolchainTypeInfo == null) {
      return null;
    }
    return toolchains().get(toolchainTypeInfo);
  }

  @Nullable
  public ToolchainInfo forToolchainType(ToolchainTypeInfo toolchainType) {
    return toolchains().get(toolchainType);
  }

  /**
   * Exception used when a toolchain type is requested but the resolved target does not have
   * ToolchainInfo.
   */
  static final class TargetNotToolchainException extends ToolchainException {
    TargetNotToolchainException(ToolchainTypeInfo toolchainType, Label resolvedTargetLabel) {
      super(
          String.format(
              "toolchain type %s resolved to target %s, but that target does not provide "
                  + ToolchainInfo.STARLARK_NAME,
              toolchainType.typeLabel(),
              resolvedTargetLabel));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.MISSING_PROVIDER;
    }
  }

  /** Exception used when a toolchain type is required but noimplementation was found. */
  private static class MissingToolchainTypeRequirementException extends ToolchainException {

    MissingToolchainTypeRequirementException(ToolchainTypeRequirement toolchainTypeRequirement) {
      super(
          String.format(
              "toolchain type %s was mandatory but is not present",
              toolchainTypeRequirement.toolchainType()));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.NO_MATCHING_TOOLCHAIN;
    }
  }
}
