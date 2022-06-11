// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.RunfilesLibraryInfoApi;

/**
 * Provider that signals to direct dependents that this target is a runfiles library and that they
 * should ensure that their compiled code has access to its canonical repository name, e.g.
 * via a generated constant.
 */
@Immutable
public final class RunfilesLibraryInfo extends NativeInfo implements RunfilesLibraryInfoApi {

  public static final RunfilesLibraryInfoProvider PROVIDER = new RunfilesLibraryInfoProvider();

  @Override
  public RunfilesLibraryInfoProvider getProvider() {
    return PROVIDER;
  }

  public static class RunfilesLibraryInfoProvider extends BuiltinProvider<RunfilesLibraryInfo>
      implements RunfilesLibraryInfoApi.RunfilesLibraryInfoApiProvider {

    private RunfilesLibraryInfoProvider() {
      super("RunfilesLibraryInfo", RunfilesLibraryInfo.class);
    }

    @Override
    public RunfilesLibraryInfoApi constructor() {
      return new RunfilesLibraryInfo();
    }
  }
}
