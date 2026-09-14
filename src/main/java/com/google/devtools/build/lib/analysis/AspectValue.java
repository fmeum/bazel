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

package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.RepositoryMetadata;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/** An aspect in the context of the Skyframe graph. */
@AutoCodec(deserializedInterface = DeserializedSkyValue.class)
public class AspectValue extends BasicActionLookupValue
    implements ConfiguredAspect, RuleConfiguredObjectValue {

  public static AspectValue create(
      AspectKey key,
      Aspect aspect,
      ConfiguredAspect configuredAspect,
      @Nullable NestedSet<RepositoryMetadata> transitiveRepositories,
      @Nullable NestedSet<String> transitiveTopLevelDirs) {
    return transitiveRepositories == null
        ? new AspectValue(aspect, configuredAspect)
        : new AspectValueWithTransitiveRepositories(
            key, aspect, configuredAspect, transitiveRepositories, transitiveTopLevelDirs);
  }

  public static AspectValue createForAlias(
      AspectKey key,
      Aspect aspect,
      ConfiguredAspect configuredAspect,
      @Nullable NestedSet<RepositoryMetadata> transitiveRepositories,
      @Nullable NestedSet<String> transitiveTopLevelDirs) {
    return transitiveRepositories == null
        ? new AspectValueForAlias(aspect, configuredAspect)
        : new AspectValueWithTransitiveRepositoriesForAlias(
            key, aspect, configuredAspect, transitiveRepositories, transitiveTopLevelDirs);
  }

  // These variables are only non-final because they may be clear()ed to save memory. They are null
  // only after they are cleared except for transitiveRepositoriesForPackageRootResolution.
  @Nullable private Aspect aspect;
  @Nullable private TransitiveInfoProviderMap providers;

  // We store this in a boolean because the aspect variable from which it comes may be cleared to
  // save memory.
  private final boolean writesOutputToMasterLog;

  private AspectValue(Aspect aspect, ConfiguredAspect configuredAspect) {
    this(
        configuredAspect.getActions(),
        checkNotNull(aspect),
        configuredAspect.getProviders(),
        aspect.getDefinition().getAttributes().containsKey("$print_to_master_log"));
  }

  @AutoCodec.Instantiator
  AspectValue(
      ImmutableList<ActionAnalysisMetadata> actions,
      Aspect aspect,
      TransitiveInfoProviderMap providers,
      boolean writesOutputToMasterLog) {
    super(actions);
    this.aspect = aspect;
    this.providers = providers;
    this.writesOutputToMasterLog = writesOutputToMasterLog;
  }

  public AspectKey getKeyForTransitiveRepositoryTracking() {
    throw new UnsupportedOperationException("Only supported if transitive repositories are tracked.");
  }

  public final Aspect getAspect() {
    return checkNotNull(aspect);
  }

  @Override
  public TransitiveInfoProviderMap getProviders() {
    return checkNotNull(providers);
  }

  public boolean getWritesOutputToMasterLog() {
    return writesOutputToMasterLog;
  }

  @Override
  public boolean isCleared() {
    return this.aspect == null;
  }

  /**
   * Clears data from this value.
   *
   * <p>Should only be used when user specifies --discard_analysis_cache. Must be called at most
   * once per value, after which this object's other methods cannot be called.
   */
  public void clear(boolean clearEverything) {
    if (clearEverything) {
      aspect = null;
      providers = null;
    }
  }

  @Nullable
  @Override
  public NestedSet<RepositoryMetadata> getTransitiveRepositories() {
    return null;
  }

  @Nullable
  @Override
  public NestedSet<String> getTransitiveTopLevelDirs() {
    return null;
  }

  @Override
  public final ProviderCollection getConfiguredObject() {
    return this;
  }

  @Override
  protected ToStringHelper getStringHelper() {
    return super.getStringHelper().add("aspect", aspect).add("providers", providers);
  }

  @Override
  public String toString() {
    return getStringHelper().toString();
  }

  private static class AspectValueWithTransitiveRepositories extends AspectValue {
    @Nullable
    private transient NestedSet<RepositoryMetadata> transitiveRepositories; // Null after clear().

    @Nullable
    private transient NestedSet<String> transitiveTopLevelDirs; // Null after clear().

    @Nullable private AspectKey key;

    private AspectValueWithTransitiveRepositories(
        AspectKey key,
        Aspect aspect,
        ConfiguredAspect configuredAspect,
        NestedSet<RepositoryMetadata> transitiveRepositories,
        NestedSet<String> transitiveTopLevelDirs) {
      super(aspect, configuredAspect);
      this.transitiveRepositories = checkNotNull(transitiveRepositories);
      this.transitiveTopLevelDirs = checkNotNull(transitiveTopLevelDirs);
      this.key = checkNotNull(key);
    }

    @Override
    public NestedSet<RepositoryMetadata> getTransitiveRepositories() {
      return transitiveRepositories;
    }

    @Override
    public NestedSet<String> getTransitiveTopLevelDirs() {
      return transitiveTopLevelDirs;
    }

    @Override
    public AspectKey getKeyForTransitiveRepositoryTracking() {
      return checkNotNull(key);
    }

    @Override
    public void clear(boolean clearEverything) {
      super.clear(clearEverything);
      transitiveRepositories = null;
      transitiveTopLevelDirs = null;
      key = null;
    }

    @Override
    protected ToStringHelper getStringHelper() {
      return super.getStringHelper()
          .add("key", key)
          .add("transitiveRepositories", transitiveRepositories);
    }
  }

  @AutoCodec(deserializedInterface = DeserializedSkyValue.class)
  @VisibleForSerialization
  static class AspectValueForAlias extends AspectValue {
    private AspectValueForAlias(Aspect aspect, ConfiguredAspect configuredAspect) {
      super(aspect, configuredAspect);
    }

    @AutoCodec.Instantiator
    AspectValueForAlias(
        ImmutableList<ActionAnalysisMetadata> actions,
        Aspect aspect,
        TransitiveInfoProviderMap providers,
        boolean writesOutputToMasterLog) {
      super(actions, aspect, providers, writesOutputToMasterLog);
    }
  }

  private static final class AspectValueWithTransitiveRepositoriesForAlias
      extends AspectValueWithTransitiveRepositories {
    private AspectValueWithTransitiveRepositoriesForAlias(
        AspectKey key,
        Aspect aspect,
        ConfiguredAspect configuredAspect,
        NestedSet<RepositoryMetadata> transitiveRepositories,
        NestedSet<String> transitiveTopLevelDirs) {
      super(key, aspect, configuredAspect, transitiveRepositories, transitiveTopLevelDirs);
    }
  }

  public static boolean isForAliasTarget(AspectValue aspectValue) {
    return aspectValue instanceof AspectValueForAlias
        || aspectValue instanceof AspectValueWithTransitiveRepositoriesForAlias;
  }
}
