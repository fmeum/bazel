// Copyright 2023 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.Builder.PackageSettings;
import com.google.devtools.build.lib.packages.RepositoryMetadata;
import com.google.devtools.build.lib.packages.util.MockObjcSupport;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TransitiveDependencyStateTest {
  private static final Random rng = new Random(0);
  private static final Root fakeRoot =
      Root.fromPath(new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/fake"));

  @Test
  public void directlyAddedRepositories_areSortedAndDeduplicated() {
    var orderedPackages =
        ImmutableList.<Package.Metadata>of(
            createFakePackageMetadata("repo1", "package1"),
            createFakePackageMetadata("repo2", "package2"),
            createFakePackageMetadata("repo3", "package3"));
    var orderedRepositories =
        orderedPackages.stream().map(RepositoryMetadata::forPackage).collect(toImmutableList());
    var workingCopy = new ArrayList<>(orderedPackages);
    // Another package in a repository that is already present.
    workingCopy.add(createFakePackageMetadata("repo2", "other"));

    for (int i = 0; i < 3; i++) {
      var state = newTransitiveState();

      Collections.shuffle(workingCopy, rng);
      workingCopy.forEach(state::addPackage);

      assertThat(state.transitiveRepositories().toList())
          .containsExactlyElementsIn(orderedRepositories)
          .inOrder();
    }
  }

  @Test
  public void directlyAddedTopLevelDirs_areSortedAndDeduplicated_mainRepoOnly() {
    var packages =
        ImmutableList.<Package.Metadata>of(
            createFakePackageMetadata("", "c/pkg"),
            createFakePackageMetadata("", "a/deep/pkg"),
            createFakePackageMetadata("", "b"),
            createFakePackageMetadata("", "a/other"),
            // The root package has no top-level directory.
            createFakePackageMetadata("", ""),
            // Only main repository packages contribute top-level directories.
            createFakePackageMetadata("repo", "d/pkg"));
    var workingCopy = new ArrayList<>(packages);

    for (int i = 0; i < 3; i++) {
      var state = newTransitiveState();

      Collections.shuffle(workingCopy, rng);
      workingCopy.forEach(state::addPackage);

      assertThat(state.transitiveTopLevelDirs().toList())
          .containsExactly("a", "b", "c")
          .inOrder();
    }
  }

  @Test
  public void configuredTargetRepositories_areSorted() {
    ImmutableList<ConfiguredTargetKey> orderedKeys = getOrderedConfiguredTargetKeys();

    ImmutableList<RepositoryMetadata> orderedRepositories =
        createFakeRepositoryMetadataList(orderedKeys.size());
    ImmutableList<NestedSet<RepositoryMetadata>> orderedRepositoryNestedSets =
        asSingletonNestedSets(orderedRepositories);

    var shuffledIndices = new ArrayList<Integer>();
    for (int i = 0; i < orderedKeys.size(); i++) {
      shuffledIndices.add(i);
    }

    for (int i = 0; i < 3; ++i) {
      var state = newTransitiveState();

      // Adds the entries to `state` in random order.
      Collections.shuffle(shuffledIndices, rng);
      for (int index : shuffledIndices) {
        state.addDependency(
            orderedKeys.get(index),
            orderedRepositoryNestedSets.get(index),
            /* topLevelDirs= */ null);
      }

      // The result is always ordered.
      assertThat(state.transitiveRepositories().toList())
          .containsExactlyElementsIn(orderedRepositories)
          .inOrder();
    }
  }

  @Test
  public void aspectRepositories_areSorted() {
    ImmutableList<AspectKey> orderedKeys = getOrderedAspectKeys();

    ImmutableList<RepositoryMetadata> orderedRepositories =
        createFakeRepositoryMetadataList(orderedKeys.size());
    ImmutableList<NestedSet<RepositoryMetadata>> orderedRepositoryNestedSets =
        asSingletonNestedSets(orderedRepositories);

    var shuffledIndices = new ArrayList<Integer>();
    for (int i = 0; i < orderedKeys.size(); i++) {
      shuffledIndices.add(i);
    }

    for (int i = 0; i < 3; ++i) {
      var state = newTransitiveState();

      // Adds the entries to `state` in random order.
      Collections.shuffle(shuffledIndices, rng);
      for (int index : shuffledIndices) {
        state.addDependency(
            orderedKeys.get(index),
            orderedRepositoryNestedSets.get(index),
            /* topLevelDirs= */ null);
      }

      // The result is always ordered.
      assertThat(state.transitiveRepositories().toList())
          .containsExactlyElementsIn(orderedRepositories)
          .inOrder();
    }
  }

  @Test
  public void repositoryMetadata_isInterned() {
    var first = RepositoryMetadata.forPackage(createFakePackageMetadata("repo", "package1"));
    var second = RepositoryMetadata.forPackage(createFakePackageMetadata("repo", "package2"));

    assertThat(first).isSameInstanceAs(second);
  }

  @Test
  public void directRepositoryContainedInDependency_reusesDependencySet() {
    var depKey =
        ConfiguredTargetKey.builder().setLabel(Label.parseCanonicalUnchecked("//dep")).build();
    var depState = newTransitiveState();
    depState.addPackage(createFakePackageMetadata("", "dep"));
    NestedSet<RepositoryMetadata> depRepositories = depState.transitiveRepositories();
    NestedSet<String> depTopLevelDirs = depState.transitiveTopLevelDirs();

    var state = newTransitiveState();
    state.addPackage(createFakePackageMetadata("", "dep/pkg"));
    state.addPackage(createFakePackageMetadata("", "dep"));
    state.addDependency(depKey, depRepositories, depTopLevelDirs);

    assertThat(state.transitiveRepositories()).isSameInstanceAs(depRepositories);
    assertThat(state.transitiveTopLevelDirs()).isSameInstanceAs(depTopLevelDirs);
  }

  @Test
  public void directRepositoryNotContainedInDependency_isAdded() {
    var depKey =
        ConfiguredTargetKey.builder().setLabel(Label.parseCanonicalUnchecked("//dep")).build();
    var depState = newTransitiveState();
    depState.addPackage(createFakePackageMetadata("", "dep"));
    NestedSet<RepositoryMetadata> depRepositories = depState.transitiveRepositories();

    var state = newTransitiveState();
    var ownPackage = createFakePackageMetadata("other_repo", "pkg");
    state.addPackage(ownPackage);
    state.addDependency(depKey, depRepositories, /* topLevelDirs= */ null);

    assertThat(state.transitiveRepositories().toList())
        .containsExactly(
            RepositoryMetadata.forPackage(ownPackage),
            RepositoryMetadata.forPackage(createFakePackageMetadata("", "dep")));
  }

  @Test
  public void identicalTransitiveSets_areReusedWithoutNewNode() {
    var keys = getOrderedConfiguredTargetKeys();
    var set = asSingletonNestedSets(createFakeRepositoryMetadataList(1)).get(0);

    var state = newTransitiveState();
    state.addDependency(keys.get(0), set, /* topLevelDirs= */ null);
    state.addDependency(keys.get(1), set, /* topLevelDirs= */ null);

    assertThat(state.transitiveRepositories()).isSameInstanceAs(set);
  }

  @Test
  public void equalTransitiveSets_reuseTheFirstOne() {
    var keys = getOrderedConfiguredTargetKeys();
    var repository = createFakeRepositoryMetadataList(1).get(0);
    var first = NestedSetBuilder.<RepositoryMetadata>stableOrder().add(repository).build();
    var second = NestedSetBuilder.<RepositoryMetadata>stableOrder().add(repository).build();

    var state = newTransitiveState();
    state.addDependency(keys.get(0), first, /* topLevelDirs= */ null);
    state.addDependency(keys.get(1), second, /* topLevelDirs= */ null);

    assertThat(state.transitiveRepositories()).isSameInstanceAs(first);
  }

  @Test
  public void subsetTransitiveSets_reuseTheSuperset() {
    var keys = getOrderedConfiguredTargetKeys();
    var repositories = createFakeRepositoryMetadataList(2);
    var subset =
        NestedSetBuilder.<RepositoryMetadata>stableOrder().add(repositories.get(0)).build();
    var superset = NestedSetBuilder.<RepositoryMetadata>stableOrder().addAll(repositories).build();

    var state = newTransitiveState();
    state.addDependency(keys.get(0), subset, /* topLevelDirs= */ null);
    state.addDependency(keys.get(1), superset, /* topLevelDirs= */ null);
    // Directly added elements that are already contained do not prevent the reuse either.
    state.addPackage(createFakePackageMetadata(repositories.get(1).repository().getName(), "p"));

    assertThat(state.transitiveRepositories()).isSameInstanceAs(superset);
  }

  @Test
  public void dependencyChain_doesNotGrowNestedSet() {
    var keys = getOrderedConfiguredTargetKeys();
    NestedSet<RepositoryMetadata> previous = null;
    for (int i = 0; i < keys.size(); i++) {
      var state = newTransitiveState();
      state.addPackage(createFakePackageMetadata("", "pkg" + i));
      if (previous != null) {
        state.addDependency(keys.get(i), previous, /* topLevelDirs= */ null);
      }
      NestedSet<RepositoryMetadata> current = state.transitiveRepositories();
      if (previous != null) {
        assertThat(current).isSameInstanceAs(previous);
      }
      previous = current;
    }
  }

  private static TransitiveDependencyState newTransitiveState() {
    return new TransitiveDependencyState(
        /* storeTransitiveRepositories= */ true, /* prerequisitePackages= */ p -> null);
  }

  private static Package.Metadata createFakePackageMetadata(String repo, String pkg) {
    RepositoryName repository = RepositoryName.createUnvalidated(repo);
    PackageIdentifier id = PackageIdentifier.create(repository, PathFragment.create(pkg));
    return Package.Metadata.builder()
        .packageIdentifier(id)
        .buildFilename(
            RootedPath.toRootedPath(
                fakeRoot, fakeRoot.getRelative(id.getPackageFragment().getRelative("BUILD"))))
        .workspaceName("workspace")
        .repositoryMapping(RepositoryMapping.create(ImmutableMap.of(), repository))
        .succinctTargetNotFoundErrors(PackageSettings.DEFAULTS.succinctTargetNotFoundErrors())
        .build();
  }

  private static ImmutableList<RepositoryMetadata> createFakeRepositoryMetadataList(int count) {
    var orderedRepos = new ArrayList<String>(count);
    for (int i = 0; i < count; ++i) {
      orderedRepos.add("repo" + i);
    }
    // Scrambles the order so if the result is ordered it's not somehow due to repository sorting.
    Collections.shuffle(orderedRepos, rng);
    return orderedRepos.stream()
        .map(repo -> RepositoryMetadata.forPackage(createFakePackageMetadata(repo, "package")))
        .collect(toImmutableList());
  }

  private static ImmutableList<NestedSet<RepositoryMetadata>> asSingletonNestedSets(
      List<RepositoryMetadata> repositories) {
    return repositories.stream()
        .map(repo -> NestedSetBuilder.<RepositoryMetadata>stableOrder().add(repo).build())
        .collect(toImmutableList());
  }

  private static ImmutableSortedSet<BuildOptions> createTestOptions() {
    try {
      return ImmutableSortedSet.copyOf(
          comparing(BuildOptions::checksum),
          Arrays.<BuildOptions>asList(
              createTestOptions(ImmutableList.of("--platforms=" + TestConstants.PLATFORM_LABEL)),
              createTestOptions(ImmutableList.of("--platforms=" + MockObjcSupport.DARWIN_X86_64))));
    } catch (OptionsParsingException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static BuildOptions createTestOptions(List<String> args) throws OptionsParsingException {
    var fragments = ImmutableList.<Class<? extends FragmentOptions>>of(PlatformOptions.class);
    var optionsParser = OptionsParser.builder().optionsClasses(fragments).build();
    optionsParser.parse(args);
    return BuildOptions.of(fragments, optionsParser);
  }

  private static final ImmutableSortedSet<BuildOptions> TEST_OPTIONS = createTestOptions();
  private static final BuildOptions FIRST_OPTIONS = TEST_OPTIONS.iterator().next();
  private static final BuildOptions SECOND_OPTIONS = getLast(TEST_OPTIONS);

  private static ImmutableList<ConfiguredTargetKey> getOrderedConfiguredTargetKeys() {
    var label1 = Label.parseCanonicalUnchecked("//label1");
    var label2 = Label.parseCanonicalUnchecked("//label2");
    var platformLabel = Label.parseCanonicalUnchecked("//platforms:a");
    return ImmutableList.<ConfiguredTargetKey>of(
        ConfiguredTargetKey.builder().setLabel(label1).build(),
        ConfiguredTargetKey.builder()
            .setLabel(label1)
            .setConfigurationKey(BuildConfigurationKey.create(FIRST_OPTIONS))
            .build(),
        ConfiguredTargetKey.builder()
            .setLabel(label1)
            .setConfigurationKey(BuildConfigurationKey.create(SECOND_OPTIONS))
            .build(),
        ConfiguredTargetKey.builder()
            .setLabel(label1)
            .setExecutionPlatformLabel(platformLabel)
            .build(),
        ConfiguredTargetKey.builder()
            .setLabel(label1)
            .setExecutionPlatformLabel(platformLabel)
            .setConfigurationKey(BuildConfigurationKey.create(FIRST_OPTIONS))
            .build(),
        ConfiguredTargetKey.builder()
            .setLabel(label1)
            .setExecutionPlatformLabel(platformLabel)
            .setConfigurationKey(BuildConfigurationKey.create(SECOND_OPTIONS))
            .build(),
        ConfiguredTargetKey.builder().setLabel(label2).build());
  }

  private static final AspectClass ASPECT_CLASS1 = () -> "aspect1";
  private static final AspectClass ASPECT_CLASS2 = () -> "aspect2";
  private static final AspectClass ASPECT_CLASS3 = () -> "aspect3";
  private static final AspectClass ASPECT_CLASS4 = () -> "aspect4";

  private static ImmutableList<AspectDescriptor> getOrderedAspectDescriptors() {
    return ImmutableList.of(
        AspectDescriptor.of(ASPECT_CLASS1, AspectParameters.EMPTY),
        AspectDescriptor.of(
            ASPECT_CLASS1, new AspectParameters.Builder().addAttribute("foo", "bar").build()),
        AspectDescriptor.of(ASPECT_CLASS2, AspectParameters.EMPTY));
  }

  private static ImmutableList<AspectKey> getOrderedAspectKeys() {
    var descriptors = getOrderedAspectDescriptors();
    var builder = ImmutableList.<AspectKey>builder();

    var baseDescriptor1 = AspectDescriptor.of(ASPECT_CLASS3, AspectParameters.EMPTY);
    var baseDescriptor2 = AspectDescriptor.of(ASPECT_CLASS4, AspectParameters.EMPTY);

    for (var baseConfiguredTargetKey : getOrderedConfiguredTargetKeys()) {
      for (var descriptor : descriptors) {
        builder.add(AspectKeyCreator.createAspectKey(descriptor, baseConfiguredTargetKey));
      }

      // Constructs some additional keys that differ only in graph structure.
      var baseKey1 = AspectKeyCreator.createAspectKey(baseDescriptor1, baseConfiguredTargetKey);
      var baseKey2 = AspectKeyCreator.createAspectKey(baseDescriptor2, baseConfiguredTargetKey);

      builder.add(
          AspectKeyCreator.createAspectKey(
              getLast(descriptors), ImmutableList.of(baseKey1), baseConfiguredTargetKey));
      builder.add(
          AspectKeyCreator.createAspectKey(
              getLast(descriptors), ImmutableList.of(baseKey1, baseKey2), baseConfiguredTargetKey));
    }

    return builder.build();
  }
}
