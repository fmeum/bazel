// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.cmdline;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A class to distinguish repository mappings for repos from WORKSPACE and Bzlmod.
 *
 * <p>For repositories from the WORKSPACE file, if the requested repo doesn't exist in the mapping,
 * we fallback to the requested name. For repositories from Bzlmod, we return null to let the caller
 * decide what to do. This class won't be needed if one day we don't define external repositories in
 * the WORKSPACE file since {@code fallback} would always be false.
 */
@AutoValue
public abstract class RepositoryMapping {

  // Always fallback to the requested name
  public static final RepositoryMapping ALWAYS_FALLBACK = createAllowingFallback(ImmutableMap.of());

  abstract ImmutableSetMultimap<String, RepositoryName> repositoryMapping();

  /**
   * The owner repo of this repository mapping. It is for providing useful debug information when
   * repository mapping fails due to enforcing strict dependency, therefore it's only recorded when
   * we don't fallback to the requested repo name.
   */
  @Nullable
  abstract RepositoryName ownerRepo();

  public static RepositoryMapping create(
      Map<String, RepositoryName> repositoryMapping, RepositoryName ownerRepo) {
    return new AutoValue_RepositoryMapping(
        ImmutableSetMultimap.copyOf(Preconditions.checkNotNull(repositoryMapping).entrySet()),
        Preconditions.checkNotNull(ownerRepo));
  }

  public static RepositoryMapping createAllowingFallback(
      Map<String, RepositoryName> repositoryMapping) {
    return new AutoValue_RepositoryMapping(
        ImmutableSetMultimap.copyOf(Preconditions.checkNotNull(repositoryMapping).entrySet()), null);
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence.
   */
  public RepositoryMapping withAdditionalMappings(Map<String, RepositoryName> additionalMappings) {
    ImmutableSetMultimap.Builder<String, RepositoryName> allMappings = ImmutableSetMultimap.builder();
    allMappings.putAll(repositoryMapping());
    allMappings.putAll(additionalMappings.entrySet());
    return new AutoValue_RepositoryMapping(allMappings.build(), ownerRepo());
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence. The owner
   * repo of the given additional mappings is ignored.
   */
  public RepositoryMapping withAdditionalMappings(RepositoryMapping additionalMappings) {
    ImmutableSetMultimap.Builder<String, RepositoryName> allMappings = ImmutableSetMultimap.builder();
    allMappings.putAll(repositoryMapping());
    allMappings.putAll(additionalMappings.repositoryMapping().entries());
    return new AutoValue_RepositoryMapping(allMappings.build(), ownerRepo());
  }

  /**
   * Returns the canonical repository name associated with the given apparent repo name. The
   * provided apparent repo name is assumed to be valid.
   */
  public RepositoryName get(String preMappingName) {
    if (preMappingName.startsWith("@")) {
      // The given name is actually a canonical, post-mapping repo name already.
      return RepositoryName.createUnvalidated(preMappingName);
    }
    ImmutableSet<RepositoryName> canonicalRepoNames = repositoryMapping().get(preMappingName);
    if (!canonicalRepoNames.isEmpty()) {
      // Following the contracts of withAdditionalMappings, use the first entry.
      return canonicalRepoNames.iterator().next();
    }
    // If the owner repo is not present, that means we should fall back to the requested repo name.
    if (ownerRepo() == null) {
      return RepositoryName.createUnvalidated(preMappingName);
    } else {
      return RepositoryName.createUnvalidated(preMappingName).toNonVisible(ownerRepo());
    }
  }

  /**
   * Returns the apparent repository names used to refer to the given canonical repository name
   * under this mapping.
   */
  public ImmutableSet<String> getApparent(RepositoryName postMappingName) {
    return repositoryMapping().inverse().get(postMappingName);
  }

  public void fingerprint(Fingerprint fp) {
    fp.addInt(repositoryMapping().size());
    for (Map.Entry<String, RepositoryName> entry : repositoryMapping().entries()) {
      fp.addString(entry.getKey());
      fp.addString(entry.getValue().getName());
    }
  }
}
