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
package com.google.devtools.build.lib.runtime;

import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A cache for the results of evaluating reproducible module extensions that is backed by a remote
 * and/or disk cache.
 *
 * <p>The cache is oblivious to the format of the results it stores. An entry is identified by a
 * hash of all inputs to the evaluation that are known before the extension runs and by the inputs
 * (and their values) that the extension recorded while it ran.
 */
public interface RemoteModuleExtensionCache {

  /**
   * Looks up the result of a previous evaluation of the extension with the same predeclared inputs
   * for which all recorded inputs are up-to-date.
   *
   * <p>Callers have to check {@code env.valuesMissing()} after this method returns.
   *
   * @param extensionId a human-readable identifier of the extension, only used for reporting
   * @return the cached result or null if there is no matching entry
   */
  @Nullable
  byte[] lookup(String extensionId, String predeclaredInputHash, SkyFunction.Environment env)
      throws IOException, InterruptedException;

  /**
   * Adds the result of an evaluation of the extension to the cache. Failures are reported as
   * warnings.
   *
   * @param extensionId a human-readable identifier of the extension, only used for reporting
   */
  void put(
      String extensionId,
      String predeclaredInputHash,
      List<RepoRecordedInput.WithValue> recordedInputs,
      byte[] result,
      ExtendedEventHandler reporter)
      throws InterruptedException;
}
