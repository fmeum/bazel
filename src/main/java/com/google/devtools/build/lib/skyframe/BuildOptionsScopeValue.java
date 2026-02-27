// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Scope;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.LinkedHashMap;

/**
 * SkyValue returned by {@link com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction}.
 */
public record BuildOptionsScopeValue(LinkedHashMap<Label, Scope> scopes) implements SkyValue {

  /** Key for {@link com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue}. */
  @ThreadSafety.Immutable
  @AutoCodec
  public record Key(BuildOptions buildOptions) implements SkyKey {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    public static Key create(BuildOptions buildOptions) {
      return interner.intern(new Key(buildOptions));
    }

    @Override
    public SkyKeyInterner<?> getSkyKeyInterner() {
      return interner;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.BUILD_OPTIONS_SCOPE;
    }
  }

  /**
   * Returns the map of {@link com.google.devtools.build.lib.cmdline.Label} of scoped flags to their
   * {@link com.google.devtools.build.lib.analysis.config.Scope} including both {@link
   * com.google.devtools.build.lib.analysis.config.Scope.ScopeType} and {@link
   * com.google.devtools.build.lib.analysis.config.Scope.ScopeDefinition}.
   */
  @Override
  public LinkedHashMap<Label, Scope> scopes() {
    return scopes;
  }
}
