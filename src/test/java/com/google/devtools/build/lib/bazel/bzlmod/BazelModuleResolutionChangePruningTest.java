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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionFunction;
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue;
import com.google.devtools.build.lib.bazel.repository.RepoMetadataRequirements;
import com.google.devtools.build.lib.bazel.repository.RepositoryFetchFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.bazel.repository.cache.LocalRepoContentsCache;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.GroupedDeps;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that changes to the lockfile that don't affect the result of module resolution are change
 * pruned by Skyframe before reaching {@link BazelModuleResolutionFunction}.
 */
@RunWith(JUnit4.class)
public class BazelModuleResolutionChangePruningTest extends FoundationTestCase {

  private static final class RecordingProgressReceiver implements EvaluationProgressReceiver {
    private final Map<SkyKey, EvaluationState> evaluated = new ConcurrentHashMap<>();
    private final Map<SkyKey, Boolean> computed = new ConcurrentHashMap<>();

    @Override
    public void stateStarting(SkyKey skyKey, NodeState nodeState) {
      if (nodeState == NodeState.COMPUTE) {
        computed.put(skyKey, true);
      }
    }

    @Override
    public void evaluated(
        SkyKey skyKey,
        EvaluationState state,
        @Nullable SkyValue newValue,
        @Nullable ErrorInfo newError,
        @Nullable GroupedDeps directDeps) {
      evaluated.put(skyKey, state);
    }

    void reset() {
      evaluated.clear();
      computed.clear();
    }

    boolean wasComputed(SkyKey key) {
      return computed.containsKey(key);
    }

    EvaluationState stateOf(SkyKey key) {
      return evaluated.get(key);
    }
  }

  @Rule public final TestHttpServer server = new TestHttpServer();

  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private RecordingProgressReceiver progressReceiver;

  @Before
  public void setup() throws Exception {
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build();
    progressReceiver = new RecordingProgressReceiver();
    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    ConfiguredRuleClassProvider ruleClassProvider = AnalysisMock.get().createRuleClassProvider();

    HttpDownloader httpDownloader = new HttpDownloader();
    DownloadManager downloadManager =
        new DownloadManager(new DownloadCache(), httpDownloader, httpDownloader, reporter);
    ModuleFileFunction moduleFileFunction =
        new ModuleFileFunction(
            ruleClassProvider.getBazelStarlarkEnvironment(), rootDirectory, ImmutableMap.of());
    moduleFileFunction.setDownloadManager(downloadManager);
    RepoSpecFunction repoSpecFunction = new RepoSpecFunction();
    repoSpecFunction.setDownloadManager(downloadManager);
    YankedVersionsFunction yankedVersionsFunction = new YankedVersionsFunction();
    yankedVersionsFunction.setDownloadManager(downloadManager);

    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(SkyFunctions.FILE, new FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    SkyFunctions.BAZEL_LOCK_FILE,
                    new BazelLockFileFunction(rootDirectory, directories.getOutputBase()))
                .put(SkyFunctions.MODULE_FILE, moduleFileFunction)
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY))
                .put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
                .put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, new LocalRepositoryLookupFunction())
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryFetchFunction(
                        ImmutableMap::of,
                        ImmutableMap::of,
                        directories,
                        new LocalRepoContentsCache()))
                .put(RepoDefinitionValue.REPO_DEFINITION, new RepoDefinitionFunction(directories))
                .put(
                    SkyFunctions.REGISTRY,
                    new RegistryFunction(
                        new RegistryFactoryImpl(Suppliers.ofInstance(ImmutableMap.of())),
                        directories.getWorkspace()))
                .put(SkyFunctions.REPO_SPEC, repoSpecFunction)
                .put(SkyFunctions.YANKED_VERSIONS, yankedVersionsFunction)
                .put(
                    SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                    new ModuleExtensionRepoMappingEntriesFunction())
                .put(
                    SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                    new ClientEnvironmentFunction(
                        new AtomicReference<>(ImmutableMap.of("BZLMOD_ALLOW_YANKED_VERSIONS", ""))))
                .buildOrThrow(),
            differencer,
            progressReceiver);

    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDirectoryValue.FETCH_DISABLED.set(differencer, false);
    RepositoryDirectoryValue.FORCE_FETCH.set(
        differencer, RepositoryDirectoryValue.FORCE_FETCH_DISABLED);
    RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, Optional.empty());
    RepoMetadataRequirements.REQUIRE_REPO_EXTENSION_METADATA.set(
        differencer, RepositoryOptions.RequireRepoExtensionMetadataMode.FALSE);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    RegistryFunction.MODULE_MIRRORS.set(differencer, ImmutableMap.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.OFF);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);

    server.serve("/bazel_registry.json", "{}");
    server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = 'foo', version = '1.0')");
    server.serve(
        "/modules/foo/1.0/source.json",
        "{\"url\": \"https://example.com/foo-1.0.zip\", \"integrity\": \"sha256-blah\"}");
    server.serve("/modules/foo/metadata.json", "{\"yanked_versions\": {}}");
    server.start();
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(server.getUrl()));

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name = 'root')",
        "bazel_dep(name = 'foo', version = '1.0')");
  }

  private BazelModuleResolutionValue evaluateResolution() throws Exception {
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);
    assertThat(result.hasError()).isFalse();
    return result.get(BazelModuleResolutionValue.KEY);
  }

  private void writeLockfileAndInvalidate(BazelLockFileValue lockfile) {
    BazelLockFileModule.updateLockfile(rootDirectory, lockfile);
    differencer.invalidate(
        ImmutableList.of(
            FileStateValue.key(
                RootedPath.toRootedPath(
                    Root.fromPath(rootDirectory), LabelConstants.MODULE_LOCKFILE_NAME))));
  }

  @Test
  public void newLockfileDoesNotRerunResolution() throws Exception {
    BazelModuleResolutionValue firstResult = evaluateResolution();
    assertThat(progressReceiver.wasComputed(BazelModuleResolutionValue.KEY)).isTrue();
    assertThat(firstResult.getRegistryFileHashes().keySet())
        .containsExactly(
            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
            server.getUrl() + "/bazel_registry.json",
            server.getUrl() + "/modules/foo/1.0/source.json");

    // Simulate the lockfile written by BazelLockFileModule after the first build.
    writeLockfileAndInvalidate(
        BazelLockFileValue.builder()
            .setRegistryFileHashes(ImmutableSortedMap.copyOf(firstResult.getRegistryFileHashes()))
            .setSelectedYankedVersions(firstResult.getSelectedYankedVersions())
            .build());
    progressReceiver.reset();

    BazelModuleResolutionValue secondResult = evaluateResolution();

    assertThat(secondResult).isSameInstanceAs(firstResult);
    assertThat(progressReceiver.stateOf(BazelLockFileValue.KEY))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_CHANGED);
    // The registry now knows about the file hashes and is thus a different value...
    assertThat(progressReceiver.stateOf(RegistryKey.create(server.getUrl())))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_CHANGED);
    // ... but the module file and repo spec obtained from it are unchanged...
    SkyKey moduleFileKey = ModuleFileValue.key(createModuleKey("foo", "1.0"));
    assertThat(progressReceiver.wasComputed(moduleFileKey)).isTrue();
    assertThat(progressReceiver.stateOf(moduleFileKey))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_UNCHANGED);
    SkyKey repoSpecKey = RepoSpecKey.create(createModuleKey("foo", "1.0"), server.getUrl());
    assertThat(progressReceiver.wasComputed(repoSpecKey)).isTrue();
    assertThat(progressReceiver.stateOf(repoSpecKey))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_UNCHANGED);
    SkyKey yankedVersionsKey =
        YankedVersionsValue.Key.create(createModuleKey("foo", "1.0"), server.getUrl());
    assertThat(progressReceiver.wasComputed(yankedVersionsKey)).isTrue();
    assertThat(progressReceiver.stateOf(yankedVersionsKey))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_UNCHANGED);
    // ... so module resolution is change pruned.
    assertThat(progressReceiver.wasComputed(BazelModuleResolutionValue.KEY)).isFalse();
    assertThat(progressReceiver.stateOf(BazelModuleResolutionValue.KEY))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_UNCHANGED);
  }

  @Test
  public void lockfileChangeUnrelatedToRegistriesDoesNotRerunRegistryFunctions()
      throws Exception {
    BazelModuleResolutionValue firstResult = evaluateResolution();
    BazelLockFileValue lockfile =
        BazelLockFileValue.builder()
            .setRegistryFileHashes(ImmutableSortedMap.copyOf(firstResult.getRegistryFileHashes()))
            .setSelectedYankedVersions(firstResult.getSelectedYankedVersions())
            .build();
    writeLockfileAndInvalidate(lockfile);
    BazelModuleResolutionValue secondResult = evaluateResolution();
    assertThat(secondResult).isSameInstanceAs(firstResult);

    // Simulate a change to the lockfile that only concerns module extensions.
    writeLockfileAndInvalidate(
        lockfile.toBuilder()
            .setFactsVersions(
                ImmutableMap.of(
                    ModuleExtensionId.create(
                        Label.parseCanonicalUnchecked("//:ext.bzl"), "ext", Optional.empty()),
                    1))
            .build());
    progressReceiver.reset();

    BazelModuleResolutionValue thirdResult = evaluateResolution();

    assertThat(thirdResult).isSameInstanceAs(firstResult);
    assertThat(progressReceiver.stateOf(BazelLockFileValue.KEY))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_CHANGED);
    SkyKey registryKey = RegistryKey.create(server.getUrl());
    assertThat(progressReceiver.wasComputed(registryKey)).isTrue();
    assertThat(progressReceiver.stateOf(registryKey))
        .isEqualTo(EvaluationProgressReceiver.EvaluationState.SUCCESS_VERSION_UNCHANGED);
    assertThat(progressReceiver.wasComputed(ModuleFileValue.key(createModuleKey("foo", "1.0"))))
        .isFalse();
    assertThat(
            progressReceiver.wasComputed(
                RepoSpecKey.create(createModuleKey("foo", "1.0"), server.getUrl())))
        .isFalse();
    assertThat(
            progressReceiver.wasComputed(
                YankedVersionsValue.Key.create(createModuleKey("foo", "1.0"), server.getUrl())))
        .isFalse();
    assertThat(progressReceiver.wasComputed(BazelModuleResolutionValue.KEY)).isFalse();
  }
}
