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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.buildModule;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SingleExtensionUsagesValue}. */
@RunWith(JUnit4.class)
public class SingleExtensionUsagesValueTest {

  private static final ModuleKey FOO = createModuleKey("foo", "1.0");

  private static byte[] hash(ImmutableMap<ModuleKey, RepositoryMapping> repoMappings)
      throws Exception {
    return SingleExtensionUsagesValue.hashForEvaluation(
        GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON,
        SingleExtensionUsagesValue.create(
            ImmutableMap.of(),
            "bar++ext",
            ImmutableList.of(
                abridgedModule(buildModule("", "").setKey(ModuleKey.ROOT)),
                abridgedModule(buildModule("foo", "1.0"))),
            repoMappings,
            ImmutableMap.of()));
  }

  private static AbridgedModule abridgedModule(Module.Builder module) {
    return AbridgedModule.from(module.setFlagAliases(ImmutableMap.of()).build());
  }

  private static RepositoryMapping repoMapping(String contextRepo, String apparentName) {
    return RepositoryMapping.create(
        ImmutableMap.of(apparentName, RepositoryName.createUnvalidated(apparentName + "+")),
        RepositoryName.createUnvalidated(contextRepo));
  }

  @Test
  public void hashForEvaluation_versionlessContextRepos_unaffectedByRepoMappings()
      throws Exception {
    // Lockfiles written by older versions of Bazel didn't take repo mappings into account at all.
    // Verify that the hash stays the same in the common case of no multiple_version_override.
    assertThat(
            hash(
                ImmutableMap.of(
                    ModuleKey.ROOT, repoMapping("", "bar"), FOO, repoMapping("foo+", "bar"))))
        .isEqualTo(hash(ImmutableMap.of()));
  }

  @Test
  public void hashForEvaluation_ignoresRepoMappingEntries() throws Exception {
    assertThat(
            hash(
                ImmutableMap.of(
                    ModuleKey.ROOT, repoMapping("", "bar"), FOO, repoMapping("foo+1.0", "bar"))))
        .isEqualTo(
            hash(
                ImmutableMap.of(
                    ModuleKey.ROOT,
                    repoMapping("", "baz"),
                    FOO,
                    repoMapping("foo+1.0", "baz"))));
  }

  @Test
  public void hashForEvaluation_tracksVersionedContextRepo() throws Exception {
    // With a multiple_version_override, the canonical name of foo's repo changes from foo+ to
    // foo+1.0 without its module key changing. Labels without a repo part in foo's tags resolve
    // to that repo, so the extension has to be reevaluated.
    byte[] versionless =
        hash(
            ImmutableMap.of(
                ModuleKey.ROOT, repoMapping("", "bar"), FOO, repoMapping("foo+", "bar")));
    byte[] versioned =
        hash(
            ImmutableMap.of(
                ModuleKey.ROOT, repoMapping("", "bar"), FOO, repoMapping("foo+1.0", "bar")));
    assertThat(versioned).isNotEqualTo(versionless);
  }
}
