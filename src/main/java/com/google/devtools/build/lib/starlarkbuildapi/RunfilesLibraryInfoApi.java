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

package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.StarlarkConstructor;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/**
 * Provider that signals to direct dependents that this target is a runfiles library and that they
 * should ensure that their compiled code has access to its canonical repository name, e.g.
 * via a generated constant.
 */
@StarlarkBuiltin(
    name = "RunfilesLibraryInfo",
    category = DocCategory.PROVIDER,
    doc = "Provider that signals to direct dependents that this target is a runfiles library and "
        + "that they should ensure that their compiled code has access to its canonical repository "
        + "name, e.g. via a generated constant.")
public interface RunfilesLibraryInfoApi extends StructApi {

  @StarlarkBuiltin(name = "Provider", category = DocCategory.PROVIDER, documented = false, doc = "")
  interface RunfilesLibraryInfoApiProvider extends ProviderApi {

    @StarlarkMethod(
        name = "RunfilesLibraryInfo",
        doc = "",
        documented = false,
        selfCall = true)
    @StarlarkConstructor
    RunfilesLibraryInfoApi constructor();
  }
}
