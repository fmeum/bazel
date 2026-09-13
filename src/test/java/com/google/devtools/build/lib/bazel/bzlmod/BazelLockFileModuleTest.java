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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Optional;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BazelLockFileModule}. */
@RunWith(JUnit4.class)
public class BazelLockFileModuleTest {

  private ModuleExtensionId extensionId;
  private LockFileModuleExtension nonReproducibleResult;
  private LockFileModuleExtension reproducibleResult;
  private ModuleExtensionEvalFactors evalFactors;
  private ModuleExtensionEvalFactors otherEvalFactors;

  @Before
  public void setUp() throws Exception {
    extensionId =
        ModuleExtensionId.create(
            Label.parseCanonicalUnchecked("//:ext.bzl"), "ext", Optional.empty());
    nonReproducibleResult =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(new byte[] {1, 2, 3})
            .setUsagesDigest(new byte[] {4, 5, 6})
            .setRecordedInputs(ImmutableList.of())
            .setGeneratedRepoSpecs(ImmutableMap.of())
            .build();
    reproducibleResult =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(new byte[] {1, 2, 3})
            .setUsagesDigest(new byte[] {4, 5, 6})
            .setRecordedInputs(ImmutableList.of())
            .setGeneratedRepoSpecs(ImmutableMap.of())
            .setModuleExtensionMetadata(
                LockfileModuleExtensionMetadata.of(
                    ModuleExtensionMetadata.create(
                        Starlark.NONE,
                        Starlark.NONE,
                        /* reproducible= */ true,
                        /* factsObj= */ Dict.empty())))
            .build();
    evalFactors = ModuleExtensionEvalFactors.create("linux", "x86_64");
    otherEvalFactors = ModuleExtensionEvalFactors.create("linux", "aarch64");
  }

  @Test
  public void combineModuleExtensionsReproducibleFactorAdded() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId,
            new LockFileModuleExtension.WithFactors(otherEvalFactors, reproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
        .isEqualTo(oldExtensionInfos);
  }

  @Test
  public void combineModuleExtensionsFactorBecomesReproducible() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId, new LockFileModuleExtension.WithFactors(evalFactors, reproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
        .isEmpty();
  }

  @Test
  public void combineModuleExtensionsFactorBecomesNonReproducible() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, reproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId,
            new LockFileModuleExtension.WithFactors(evalFactors, nonReproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
        .isEqualTo(
            ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult)));
  }

  @Test
  public void updateLockfileWritesJsonWithTrailingNewline() throws Exception {
    Path workspaceRoot = new Scratch().dir("/workspace");

    BazelLockFileModule.updateLockfile(workspaceRoot, BazelLockFileValue.EMPTY_LOCKFILE);

    assertThat(FileSystemUtils.readContent(workspaceRoot.getRelative("MODULE.bazel.lock"), UTF_8))
        .isEqualTo(
            GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(BazelLockFileValue.EMPTY_LOCKFILE) + "\n");
  }

  @Test
  public void updateLockfileWithNullRepoRuleIdDoesNotThrow() throws Exception {
    Path workspaceRoot = new Scratch().dir("/workspace");
    RepoSpec repoSpecWithNullRuleId = new RepoSpec(null, AttributeValues.create(Dict.empty()));
    LockFileModuleExtension extensionWithNullRuleId =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(new byte[] {1, 2, 3})
            .setUsagesDigest(new byte[] {4, 5, 6})
            .setRecordedInputs(ImmutableList.of())
            .setGeneratedRepoSpecs(ImmutableMap.of("repo", repoSpecWithNullRuleId))
            .build();
    BazelLockFileValue lockfile =
        BazelLockFileValue.builder()
            .setModuleExtensions(
                ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, extensionWithNullRuleId)))
            .build();

    BazelLockFileModule.updateLockfile(workspaceRoot, lockfile);

    assertThat(workspaceRoot.getRelative("MODULE.bazel.lock").exists()).isTrue();
  }

  private static final RepoRuleId REPO_RULE_ID =
      new RepoRuleId(Label.parseCanonicalUnchecked("//:repo.bzl"), "my_repo");

  private static RepoSpec repoSpec(ImmutableMap<String, Object> attrs) {
    return new RepoSpec(REPO_RULE_ID, AttributeValues.create(Dict.immutableCopyOf(attrs)));
  }

  private static ReproducibleRepoAttrs reproducibleRepoAttrs(
      String definitionDigest, String commit) {
    return new ReproducibleRepoAttrs(
        definitionDigest,
        REPO_RULE_ID,
        AttributeValues.create(Dict.immutableCopyOf(ImmutableMap.of("commit", commit))));
  }

  @Test
  public void combineReproducibleRepoAttrs() {
    var oldAttrs =
        ImmutableMap.of(
            "kept", reproducibleRepoAttrs("1", "a"),
            "stale", reproducibleRepoAttrs("2", "b"),
            "updated", reproducibleRepoAttrs("3", "c"),
            "removed", reproducibleRepoAttrs("4", "d"));
    var updates =
        ImmutableMap.of(
            "updated", Optional.of(reproducibleRepoAttrs("5", "e")),
            "removed", Optional.<ReproducibleRepoAttrs>empty(),
            "added", Optional.of(reproducibleRepoAttrs("6", "f")));

    var combined =
        BazelLockFileModule.combineReproducibleRepoAttrs(oldAttrs, updates, "stale"::equals);

    assertThat(combined)
        .containsExactly(
            "added", reproducibleRepoAttrs("6", "f"),
            "kept", reproducibleRepoAttrs("1", "a"),
            "updated", reproducibleRepoAttrs("5", "e"))
        .inOrder();
  }

  @Test
  public void reproducibleRepoAttrsDigestIsIndependentOfAttributeOrder() {
    var spec = repoSpec(ImmutableMap.of("version", "1.0", "commit", "abc"));
    var reorderedSpec = repoSpec(ImmutableMap.of("commit", "abc", "version", "1.0"));
    var otherSpec = repoSpec(ImmutableMap.of("version", "1.0", "commit", "def"));

    assertThat(ReproducibleRepoAttrs.digestOf(spec))
        .isEqualTo(ReproducibleRepoAttrs.digestOf(reorderedSpec));
    assertThat(ReproducibleRepoAttrs.digestOf(spec))
        .isNotEqualTo(ReproducibleRepoAttrs.digestOf(otherSpec));
    var attrs = reproducibleRepoAttrs(ReproducibleRepoAttrs.digestOf(spec), "abc");
    assertThat(attrs.appliesTo(reorderedSpec)).isTrue();
    assertThat(attrs.appliesTo(otherSpec)).isFalse();
  }

  @Test
  public void reproducibleRepoAttrsRoundTripThroughJson() {
    BazelLockFileValue lockfile =
        BazelLockFileValue.builder()
            .setReproducibleRepoAttrs(
                ImmutableMap.of("+ext+repo", reproducibleRepoAttrs("digest", "abc")))
            .build();

    String json = GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(lockfile);

    assertThat(json).contains("\"reproducibleRepoAttrs\"");
    assertThat(GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson(json, BazelLockFileValue.class))
        .isEqualTo(lockfile);
  }

  @Test
  public void lockfileWithoutReproducibleRepoAttrsParsesAsEmpty() {
    String json =
        """
        {
          "lockFileVersion": %d,
          "registryFileHashes": {},
          "selectedYankedVersions": {},
          "moduleExtensions": {}
        }
        """
            .formatted(BazelLockFileValue.LOCK_FILE_VERSION);

    BazelLockFileValue lockfile =
        GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson(json, BazelLockFileValue.class);

    assertThat(lockfile.getReproducibleRepoAttrs()).isEmpty();
    assertThat(lockfile.getFacts()).isEmpty();
  }
}
