// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.config;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Helper utility containing functions regarding configurations.ss */
@StarlarkBuiltin(
    name = "config_common",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "Functions for Starlark to interact with Blaze's configurability APIs.")
public interface ConfigStarlarkCommonApi extends StarlarkValue {

  @StarlarkMethod(
      name = "FeatureFlagInfo",
      doc = "The key used to retrieve the provider containing config_feature_flag's value.",
      structField = true)
  ProviderApi getConfigFeatureFlagProviderConstructor();

  @StarlarkMethod(
      name = "config_feature_flag_transition",
      documented = false,
      parameters = {
        @Param(
            name = "attribute",
            positional = true,
            named = false,
            doc = "string corresponding to rule attribute to read")
      })
  ConfigurationTransitionApi createConfigFeatureFlagTransitionFactory(String attribute);

  @StarlarkMethod(
      name = "toolchain_type",
      doc = "Declare a rule's dependency on a toolchain type.",
      parameters = {
        @Param(
            name = "name",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
            },
            named = false,
            doc = "The toolchain type that is required."),
        @Param(
            name = "mandatory",
            allowedTypes = {@ParamType(type = Boolean.class)},
            named = true,
            positional = false,
            defaultValue = "True",
            doc = "Whether the toolchain type is mandatory or optional."),
        @Param(
            name = "cfg",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = ConfigurationTransitionApi.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc =
                "The configuration transition to apply to the toolchain dependency. Either"
                    + " <code>\"target\"</code> (or <code>None</code>) to resolve and build the"
                    + " toolchain in the configuration of the depending target, or a transition"
                    + " created by <code>transition()</code>. The toolchain is resolved in the"
                    + " transitioned configuration, so the target platform, the registered"
                    + " toolchains and the <code>target_settings</code> of candidate toolchains"
                    + " are all evaluated after the transition has been applied, and the selected"
                    + " toolchain is built in that configuration. The execution platform is"
                    + " still selected once for the execution group that requires this toolchain"
                    + " type, so it is shared with the other toolchain types of the same execution"
                    + " group. The transition may read the configured attributes of the target"
                    + " (except in aspects), but must not be a split transition. Exec transitions"
                    + " are not supported since the execution platform is only known after"
                    + " toolchain resolution.")
      },
      useStarlarkThread = true)
  StarlarkToolchainTypeRequirement toolchainType(
      Object name, boolean mandatory, Object cfg, StarlarkThread thread) throws EvalException;
}
