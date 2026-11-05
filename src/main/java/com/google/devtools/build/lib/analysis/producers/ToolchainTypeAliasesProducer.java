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
package com.google.devtools.build.lib.analysis.producers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.InconsistentNullConfigException;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.rules.Alias;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Determines which of a set of labels are {@code alias}es of toolchain types.
 *
 * <p>Labels that refer to toolchain types outside of toolchain type requirements, such as those in
 * the {@code toolchains_aspects} of an aspect, may name an {@code alias} of a toolchain type rather
 * than the type itself, or vice versa. This is common when a toolchain type moves to a different
 * repository and an {@code alias} is left in its old location. Such labels are only directly
 * comparable to the requested toolchain types of a target after resolving the alias.
 *
 * <p>Resolution is best-effort: labels that don't refer to an existing {@code alias} target, or
 * that fail to resolve to a toolchain type, are omitted from the result without an error. Such
 * labels can't match any toolchain type and were previously accepted, so they must not cause new
 * failures. To avoid new errors with {@code --nokeep_going}, the existence of the package and the
 * target is checked before the alias is analyzed.
 */
final class ToolchainTypeAliasesProducer implements StateMachine {
  /** Receives the output of {@link ToolchainTypeAliasesProducer}. */
  interface ResultSink {
    /**
     * Accepts the map from each label that is an {@code alias} of a toolchain type to that type.
     */
    void acceptToolchainTypeAliases(ImmutableMap<Label, ToolchainTypeInfo> aliases);
  }

  // -------------------- Input --------------------
  private final ImmutableSet<Label> labels;
  private final BuildConfigurationKey configurationKey;
  private final TransitiveDependencyState transitiveState;

  // -------------------- Output --------------------
  private final ResultSink sink;

  // -------------------- Sequencing --------------------
  private final StateMachine runAfter;

  // -------------------- Internal State --------------------
  private final Map<Label, ToolchainTypeInfo> aliases = new HashMap<>();

  ToolchainTypeAliasesProducer(
      ImmutableSet<Label> labels,
      BuildConfigurationKey configurationKey,
      TransitiveDependencyState transitiveState,
      ResultSink sink,
      StateMachine runAfter) {
    this.labels = labels;
    this.configurationKey = configurationKey;
    this.transitiveState = transitiveState;
    this.sink = sink;
    this.runAfter = runAfter;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    for (Label label : labels) {
      tasks.enqueue(new AliasResolver(label));
    }
    return this::emitResult;
  }

  private StateMachine emitResult(Tasks tasks) {
    // Iterate over the input to emit the aliases in a deterministic order.
    var result = ImmutableMap.<Label, ToolchainTypeInfo>builder();
    for (Label label : labels) {
      ToolchainTypeInfo toolchainType = aliases.get(label);
      if (toolchainType != null) {
        result.put(label, toolchainType);
      }
    }
    sink.acceptToolchainTypeAliases(result.buildOrThrow());
    return runAfter;
  }

  /** Resolves a single label if it refers to an existing {@code alias} of a toolchain type. */
  private final class AliasResolver implements StateMachine {
    private final Label label;

    private boolean packageExists = false;
    @Nullable private Package pkg;

    private AliasResolver(Label label) {
      this.label = label;
    }

    @Override
    public StateMachine step(Tasks tasks) {
      // Unlike the package itself, the package lookup doesn't fail if the package or its
      // repository doesn't exist.
      tasks.lookUp(
          PackageLookupValue.key(label.getPackageIdentifier()),
          NoSuchPackageException.class,
          InconsistentFilesystemException.class,
          (value, noSuchPackageException, inconsistentFilesystemException) -> {
            if (value != null) {
              packageExists = ((PackageLookupValue) value).packageExists();
            }
          });
      return this::lookUpPackage;
    }

    private StateMachine lookUpPackage(Tasks tasks) {
      if (!packageExists) {
        return DONE;
      }
      tasks.lookUp(
          label.getPackageIdentifier(),
          NoSuchPackageException.class,
          (value, error) -> {
            if (value != null) {
              pkg = ((PackageValue) value).getPackage();
            }
          });
      return this::lookUpAlias;
    }

    private StateMachine lookUpAlias(Tasks tasks) {
      if (pkg == null) {
        return DONE;
      }
      Target target = pkg.getTargetOrNull(label.getName());
      if (!(target instanceof Rule rule) || !rule.getRuleClass().equals(Alias.RULE_NAME)) {
        // Only an alias can resolve to a toolchain type other than the one with this label.
        return DONE;
      }
      transitiveState.updateTransitivePackages(pkg.getMetadata());
      tasks.lookUp(
          ConfiguredTargetKey.builder()
              .setLabel(label)
              .setConfigurationKey(configurationKey)
              .build(),
          ConfiguredValueCreationException.class,
          NoSuchThingException.class,
          InconsistentNullConfigException.class,
          (value, creationException, noSuchThingException, nullConfigException) -> {
            if (value == null) {
              return;
            }
            var configuredTargetValue = (ConfiguredTargetValue) value;
            var configuredTarget = configuredTargetValue.getConfiguredTarget();
            ToolchainTypeInfo toolchainType = PlatformProviderUtils.toolchainType(configuredTarget);
            if (toolchainType == null || toolchainType.typeLabel().equals(label)) {
              return;
            }
            if (transitiveState.storeTransitivePackages()) {
              transitiveState.updateTransitivePackages(
                  ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
                  configuredTargetValue.getTransitivePackages());
            }
            aliases.put(label, toolchainType);
          });
      return DONE;
    }
  }
}
