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

package com.google.devtools.build.lib.analysis.platform;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.ResolvedToolchainData;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainInfoApi;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * A provider that supplies information about a specific language toolchain, including what platform
 * constraints are required for execution and for the target platform.
 *
 * <p>Unusually, ToolchainInfo exposes both its StarlarkCallable-annotated fields and a Map of
 * additional fields to Starlark code. Also, these are not disjoint.
 */
@Immutable
public final class ToolchainInfo extends StructImpl
    implements ToolchainInfoApi, ResolvedToolchainData {

  /** Name used in Starlark for accessing this provider. */
  public static final String STARLARK_NAME = "ToolchainInfo";

  /** Provider singleton constant. */
  public static final BuiltinProvider<ToolchainInfo> PROVIDER = new Provider();

  /** Provider for {@link ToolchainInfo} objects. */
  private static class Provider extends BuiltinProvider<ToolchainInfo>
      implements ToolchainInfoApi.Provider {
    private Provider() {
      super(STARLARK_NAME, ToolchainInfo.class);
    }

    @Override
    public ToolchainInfo toolchainInfo(Dict<String, Object> kwargs, StarlarkThread thread) {
      return new ToolchainInfo(kwargs, thread.getCallerLocation());
    }
  }

  @VisibleForSerialization final ImmutableSortedMap<String, Object> values;

  private final Location location;

  /** Constructs a ToolchainInfo. The {@code values} map itself is not retained. */
  protected ToolchainInfo(Map<String, Object> values, Location location) {
    this.location = location;
    this.values = copyValues(values);
  }

  @Override
  public BuiltinProvider<ToolchainInfo> getProvider() {
    return PROVIDER;
  }

  /**
   * Preprocesses a map of field values to convert the field names and field values to
   * Starlark-acceptable names and types.
   *
   * <p>Entries are ordered by key.
   */
  private static ImmutableSortedMap<String, Object> copyValues(Map<String, Object> values) {
    ImmutableSortedMap.Builder<String, Object> builder = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, Object> e : values.entrySet()) {
      builder.put(Attribute.getStarlarkName(e.getKey()), Starlark.fromJava(e.getValue(), null));
    }
    return builder.buildOrThrow();
  }

  @Override
  public Object getValue(String name) throws EvalException {
    return values.get(name);
  }

  @Override
  public ImmutableSortedSet<String> getFieldNames() {
    return values.keySet();
  }

  @Override
  public Location getCreationLocation() {
    return location;
  }
}
