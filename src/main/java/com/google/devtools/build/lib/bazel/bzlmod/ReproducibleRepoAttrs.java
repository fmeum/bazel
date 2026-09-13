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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * The attributes that a repo rule reported via {@code
 * repository_ctx.repo_metadata(attrs_for_reproducibility = ...)} to make a repo reproducible, as
 * persisted in the lockfile.
 *
 * @param definitionDigest A digest of the repo's original definition (its repo rule and attribute
 *     values) at the time the reproducible attributes were reported. The recorded attributes are
 *     only used as long as the current definition still has this digest.
 * @param repoRuleId The repo rule backing the repo. This is always the same as that of the original
 *     definition, but is included to make the lockfile entry self-contained.
 * @param attributes The attribute values to use in place of the original ones.
 */
@AutoCodec
@GenerateTypeAdapter
public record ReproducibleRepoAttrs(
    String definitionDigest, RepoRuleId repoRuleId, AttributeValues attributes) {

  /** Returns whether these attributes were reported for the given definition of a repo. */
  public boolean appliesTo(RepoSpec originalDefinition) {
    return repoRuleId.equals(originalDefinition.repoRuleId())
        && definitionDigest.equals(digestOf(originalDefinition));
  }

  /**
   * Computes a digest of a repo definition that is stable across Bazel invocations and independent
   * of the order in which attributes were specified.
   */
  public static String digestOf(RepoSpec repoSpec) {
    Hasher hasher = Hashing.sha256().newHasher();
    hasher.putString(repoSpec.repoRuleId().toString(), UTF_8).putChar('\n');
    ImmutableSortedMap.copyOf(repoSpec.attributes().attributes())
        .forEach(
            (name, value) ->
                hasher
                    .putString(name, UTF_8)
                    .putChar('=')
                    .putString(Starlark.repr(value, StarlarkSemantics.DEFAULT), UTF_8)
                    .putChar('\n'));
    return hasher.hash().toString();
  }
}
