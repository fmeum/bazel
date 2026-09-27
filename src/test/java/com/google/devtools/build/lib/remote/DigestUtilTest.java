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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.bazel.BazelHashFunctions;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link DigestUtil}. */
@RunWith(TestParameterInjector.class)
public class DigestUtilTest {

  private static final class HashFunctionProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<DigestHashFunction> provideValues(Context context) {
      BazelHashFunctions.ensureRegistered();
      return ImmutableList.copyOf(DigestHashFunction.getPossibleHashFunctions());
    }
  }

  @Test
  public void compute_matchesHashFunction(
      @TestParameter(valuesProvider = HashFunctionProvider.class) DigestHashFunction hashFn,
      @TestParameter({"", "a", "hello world"}) String content) {
    DigestUtil digestUtil = new DigestUtil(SyscallCache.NO_CACHE, hashFn);
    byte[] bytes = content.getBytes(UTF_8);
    byte[] otherBytes = "other".getBytes(UTF_8);

    // Interleave with another computation to verify that no state leaks between calls.
    Digest digest = digestUtil.compute(bytes);
    Digest otherDigest = digestUtil.compute(otherBytes);
    Digest digestAgain = digestUtil.compute(bytes);

    assertThat(digest.getHash()).isEqualTo(hashFn.getHashFunction().hashBytes(bytes).toString());
    assertThat(digest.getSizeBytes()).isEqualTo(bytes.length);
    assertThat(otherDigest.getHash())
        .isEqualTo(hashFn.getHashFunction().hashBytes(otherBytes).toString());
    assertThat(digestAgain).isEqualTo(digest);
    assertThat(digestUtil.hash(bytes))
        .isEqualTo(hashFn.getHashFunction().hashBytes(bytes).asBytes());
  }
}
