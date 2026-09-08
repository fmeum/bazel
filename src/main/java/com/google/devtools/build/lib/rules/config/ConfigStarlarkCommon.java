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
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionType;
import com.google.devtools.build.lib.analysis.starlark.ComposedTransitionMaterializer;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.starlarkbuildapi.config.ComposedConfigurationTransition;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigStarlarkCommonApi;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkToolchainTypeRequirement;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Starlark namespace used to interact with Blaze's configurability APIs. */
public class ConfigStarlarkCommon implements ConfigStarlarkCommonApi {

  @Override
  public Provider getConfigFeatureFlagProviderConstructor() {
    return ConfigFeatureFlagProvider.STARLARK_CONSTRUCTOR;
  }

  @Override
  public ConfigurationTransitionApi createConfigFeatureFlagTransitionFactory(String attribute) {
    return new ConfigFeatureFlagTransitionFactory(attribute);
  }

  @Override
  public StarlarkToolchainTypeRequirement toolchainType(
      Object name, boolean mandatory, Object cfg, StarlarkThread thread) throws EvalException {

    Label label;
    if (name instanceof Label nameLabel) {
      label = nameLabel;
    } else if (name instanceof String) {
      LabelConverter converter = LabelConverter.forBzlEvaluatingThread(thread);
      try {
        label = converter.convert((String) name);
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf(
            "Unable to parse toolchain_type label '%s': %s", name, e.getMessage());
      }
    } else {
      throw Starlark.errorf(
          "config_common.toolchain_type() takes a Label or String, and instead got a %s",
          name.getClass().getSimpleName());
    }

    return ToolchainTypeRequirement.builder(label)
        .mandatory(mandatory)
        .transitionFactory(convertToolchainTypeCfg(cfg))
        .build();
  }

  /**
   * Converts the {@code cfg} parameter of {@code config_common.toolchain_type()} into a transition
   * factory, or {@code null} if no transition should be applied.
   *
   * <p>Exec transitions are rejected: the execution platform is only selected during toolchain
   * resolution, so it cannot be an input to the transition that determines the configuration in
   * which to resolve the toolchain type. Toolchains are always resolved for the execution platform
   * of their execution group, so an exec transition would also not be meaningful.
   */
  @Nullable
  private static TransitionFactory<AttributeTransitionData> convertToolchainTypeCfg(Object cfg)
      throws EvalException {
    if (cfg.equals(Starlark.NONE) || cfg.equals("target")) {
      return null;
    }
    if (cfg instanceof String) {
      if (cfg.equals("exec") || cfg.equals("host")) {
        throw Starlark.errorf(
            "cfg = \"%s\" is not supported for toolchain types: toolchains are always resolved"
                + " for the execution platform of the execution group that requires them, which"
                + " is only selected during toolchain resolution",
            cfg);
      }
      throw Starlark.errorf(
          "cfg must be either 'target' or a Starlark-defined transition created by the"
              + " transition() function, got \"%s\"",
          cfg);
    }
    TransitionFactory<AttributeTransitionData> transitionFactory =
        convertToolchainTypeTransition((ConfigurationTransitionApi) cfg);
    if (NoTransition.isInstance(transitionFactory)) {
      // config.target() is the same as "target".
      return null;
    }
    // Check the individual factories of a (possibly composed) transition.
    AtomicReference<String> error = new AtomicReference<>();
    transitionFactory.visit(
        factory -> {
          if (NoConfigTransition.isInstance(factory)) {
            error.compareAndSet(
                null,
                "config.none() is not supported for toolchain types: toolchains must be resolved"
                    + " in a configuration");
          } else if (factory.isTool()) {
            error.compareAndSet(
                null,
                "exec transitions are not supported for toolchain types: toolchains are always"
                    + " resolved for the execution platform of the execution group that requires"
                    + " them, which is only selected during toolchain resolution");
          } else if (factory instanceof StarlarkAttributeTransitionProvider provider
              && provider.getStarlarkDefinedConfigTransitionForTesting().isForAnalysisTesting()) {
            error.compareAndSet(
                null, "analysis_test_transition() is not supported for toolchain types");
          }
        });
    if (error.get() != null) {
      throw Starlark.errorf("%s", error.get());
    }
    return transitionFactory;
  }

  private static TransitionFactory<AttributeTransitionData> convertToolchainTypeTransition(
      ConfigurationTransitionApi cfg) throws EvalException {
    if (cfg instanceof StarlarkDefinedConfigTransition starlarkDefinedTransition) {
      return new StarlarkAttributeTransitionProvider(starlarkDefinedTransition);
    }
    if (cfg instanceof ComposedConfigurationTransition composition) {
      return ComposedTransitionMaterializer.fold(
          composition,
          ConfigStarlarkCommon::convertToolchainTypeTransition,
          "it contains a native transition that can only be used as a rule transition");
    }
    // Every ConfigurationTransitionApi must be a TransitionFactory instance to be usable.
    if (!(cfg instanceof TransitionFactory<?> transitionFactory)) {
      throw new IllegalStateException(
          "Every ConfigurationTransitionApi must be a TransitionFactory instance");
    }
    if (!transitionFactory.transitionType().isCompatibleWith(TransitionType.ATTRIBUTE)) {
      throw Starlark.errorf(
          "cfg must be either 'target' or a Starlark-defined transition created by the"
              + " transition() function, got a transition that can only be used as a rule"
              + " transition");
    }
    @SuppressWarnings("unchecked")
    TransitionFactory<AttributeTransitionData> attributeTransition =
        (TransitionFactory<AttributeTransitionData>) transitionFactory;
    return attributeTransition;
  }
}
