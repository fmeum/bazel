// Copyright 2025 The Bazel Authors. All rights reserved.
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

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.BulkTransfers.waitForBulkTransfer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.repository.DigestWriter;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.runtime.RemoteRepoContentsCache;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.protobuf.ExtensionRegistryLite;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * A cache for the contents of external repositories that is backed by an ordinary remote cache.
 *
 * <p>Upon a cache hit, the metadata of the files comprising the repository is downloaded and
 * injected into a {@link RemoteExternalOverlayFileSystem}. Downloads of file contents only occur
 * when Bazel needs to read a file (e.g., a BUILD or .bzl file) or if a file is an input to an
 * action executed locally. This can save both time taken to execute repo rules and compute file
 * digests and disk space required to store the contents of external repositories.
 *
 * <p>Repositories are cached as AC entries for a synthetic command with a special hash as the salt.
 * The contents are represented as an output file for the marker file and an output directory for
 * the contents.
 *
 * <p>Since the {@link RepoRecordedInput}s of a repo rule are only known after its evaluation, the
 * AC entries are organized as described in {@link RecordedInputsActionCache}, with the predeclared
 * inputs hash computed by {@link DigestWriter} as the starting point.
 */
public final class RemoteRepoContentsCacheImpl implements RemoteRepoContentsCache {
  // Salts all cache keys; change it whenever previously cached entries may no longer be valid.
  private static final UUID GUID = UUID.fromString("d1a7d99a-ee3a-49a7-8b42-ef6600768d03");
  private static final String MARKER_FILE_PATH = ".recorded_inputs";
  private static final String REPO_DIRECTORY_PATH = "repo_contents";

  private final BlazeDirectories directories;
  private final CombinedCache cache;
  private final String buildRequestId;
  private final String commandId;
  private final boolean acceptCached;
  private final boolean uploadLocalResults;
  private final boolean verboseFailures;
  private final RecordedInputsActionCache recordedInputsCache;

  public RemoteRepoContentsCacheImpl(
      BlazeDirectories directories,
      CombinedCache cache,
      String buildRequestId,
      String commandId,
      boolean acceptCached,
      boolean uploadLocalResults,
      boolean verboseFailures) {
    this.directories = directories;
    this.cache = cache;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.acceptCached = acceptCached;
    this.uploadLocalResults = uploadLocalResults;
    this.verboseFailures = verboseFailures;
    this.recordedInputsCache =
        new RecordedInputsActionCache(
            directories,
            cache,
            GUID,
            /* outputFiles= */ ImmutableList.of(MARKER_FILE_PATH),
            /* outputDirectories= */ ImmutableList.of(REPO_DIRECTORY_PATH));
  }

  @Override
  public void addToCache(
      RepositoryName repoName,
      Path fetchedRepoDir,
      Path fetchedRepoMarkerFile,
      String predeclaredInputHash,
      ExtendedEventHandler reporter)
      throws InterruptedException {
    if (!(fetchedRepoDir.getFileSystem() instanceof RemoteExternalOverlayFileSystem remoteFs)) {
      return;
    }
    var context = buildContext(repoName, CacheOp.UPLOAD);
    if (!context.getWriteCachePolicy().allowRemoteCache()) {
      return;
    }
    ImmutableList<RepoRecordedInput.WithValue> recordedInputValues;
    String markerFileContent;
    try {
      markerFileContent = FileSystemUtils.readContent(fetchedRepoMarkerFile, ISO_8859_1);
      var maybeRecordedInputValues =
          DigestWriter.readMarkerFile(markerFileContent, predeclaredInputHash);
      if (maybeRecordedInputValues.isEmpty()) {
        return;
      }
      recordedInputValues = maybeRecordedInputValues.get();
    } catch (IOException e) {
      reporter.handle(
          Event.warn(
              "Failed to read marker file for repo %s, skipping: %s"
                  .formatted(repoName, maybeGetStackTrace(e))));
      return;
    }
    try {
      // TODO: Consider uploading asynchronously.
      var finalHash =
          recordedInputsCache.uploadIntermediateActionResults(
              context, predeclaredInputHash, recordedInputValues);
      var action = recordedInputsCache.buildAction(finalHash);
      var actionKey = new ActionKey(cache.digestUtil().compute(action));
      var remotePathResolver = new RepoRemotePathResolver(fetchedRepoMarkerFile, fetchedRepoDir);
      var manifest =
          UploadManifest.create(
              cache.getRemoteCacheCapabilities(),
              cache.digestUtil(),
              remotePathResolver,
              actionKey,
              action,
              recordedInputsCache.command(),
              ImmutableList.of(fetchedRepoMarkerFile, fetchedRepoDir),
              /* outErr= */ null,
              /* exitCode= */ 0,
              /* startTime= */ Instant.now(),
              /* wallTimeInMs= */ 0,
              /* preserveExecutableBit= */ true);
      var result = manifest.upload(context, cache, reporter, /* force= */ false);
      // Relative repository-root symlinks have no directory Tree to install.
      if (result.getOutputDirectoriesCount() != 1) {
        return;
      }
      remoteFs.injectUploadedRepo(
          repoName,
          manifest.getDirectoryTree(result.getOutputDirectories(0).getTreeDigest()),
          markerFileContent);
    } catch (ExecException | IOException e) {
      reporter.handle(
          Event.warn(
              "Failed to upload repo contents to remote cache for repo %s: %s"
                  .formatted(repoName, maybeGetStackTrace(e))));
    }
  }

  @Override
  public boolean lookupCache(
      RepositoryName repoName,
      Path repoDir,
      String predeclaredInputHash,
      SkyFunction.Environment env)
      throws IOException, InterruptedException {
    try {
      return doLookupCache(repoName, repoDir, predeclaredInputHash, env);
    } catch (IOException e) {
      throw new IOException(
          "Failed to look up repo %s in the remote repo contents cache: %s"
              .formatted(repoName, maybeGetStackTrace(e)),
          e);
    }
  }

  private boolean doLookupCache(
      RepositoryName repoName,
      Path repoDir,
      String predeclaredInputHash,
      SkyFunction.Environment env)
      throws IOException, InterruptedException {
    if (!(repoDir.getFileSystem() instanceof RemoteExternalOverlayFileSystem remoteFs)) {
      return false;
    }

    var context = buildContext(repoName, CacheOp.DOWNLOAD);
    if (!context.getReadCachePolicy().allowRemoteCache()) {
      return false;
    }
    // The marker file is read right after and thus requested to be inlined.
    var finalEntry =
        recordedInputsCache.fetchFinalActionResult(
            env,
            context,
            predeclaredInputHash,
            /* inlineOutputFiles= */ ImmutableSet.of(MARKER_FILE_PATH),
            /* isFinal= */ actionResult ->
                actionResult.getOutputFilesCount() == 1
                    && actionResult.getOutputDirectoriesCount() == 1
                    && actionResult.getOutputSymlinksCount() == 0);
    if (env.valuesMissing() || finalEntry == null) {
      return false;
    }

    ListenableFuture<byte[]> markerFileContentFuture;
    var markerFile = finalEntry.getOutputFiles(0);
    // Inlining is an optional feature, so we have to be prepared to download the marker file.
    if (markerFile.getContents().isEmpty()) {
      markerFileContentFuture =
          cache.downloadBlob(
              context, MARKER_FILE_PATH, /* execPath= */ null, markerFile.getDigest());
    } else {
      markerFileContentFuture = immediateFuture(markerFile.getContents().toByteArray());
    }
    var repoDirectory = finalEntry.getOutputDirectories(0);
    var repoDirectoryContentFuture =
        transformAsync(
            cache.downloadBlobAsByteString(
                context, REPO_DIRECTORY_PATH, /* execPath= */ null, repoDirectory.getTreeDigest()),
            (treeBytes) ->
                immediateFuture(
                    Tree.parseFrom(treeBytes, ExtensionRegistryLite.getEmptyRegistry())),
            directExecutor());
    waitForBulkTransfer(ImmutableList.of(markerFileContentFuture, repoDirectoryContentFuture));

    String markerFileContent = new String(markerFileContentFuture.resultNow(), ISO_8859_1);
    var maybeRecordedInputs = DigestWriter.readMarkerFile(markerFileContent, predeclaredInputHash);
    if (maybeRecordedInputs.isEmpty()) {
      return false;
    }
    var outdatedReason =
        RepoRecordedInput.isAnyValueOutdated(env, directories, maybeRecordedInputs.get());
    if (env.valuesMissing() || outdatedReason.isPresent()) {
      env.getListener()
          .handle(
              Event.warn(
                  "Unexpectedly outdated cached repo %s: %s"
                      .formatted(repoName, outdatedReason.orElse("unknown reason"))));
      return false;
    }

    return remoteFs.injectRemoteRepo(
        repoName, repoDirectoryContentFuture.resultNow(), markerFileContent);
  }

  private enum CacheOp {
    DOWNLOAD,
    UPLOAD,
  }

  private RemoteActionExecutionContext buildContext(RepositoryName repoName, CacheOp cacheOp) {
    var metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, repoName.getName());
    // Don't upload local repo contents to the disk cache as the (local) `--repo_contents_cache` is
    // a better alternative for local caching. Do write through the disk cache for downloads from
    // the remote cache to speed up future usage.
    return RemoteActionExecutionContext.create(metadata)
        .withReadCachePolicy(acceptCached ? CachePolicy.ANY_CACHE : CachePolicy.NO_CACHE)
        .withWriteCachePolicy(
            switch (cacheOp) {
              case DOWNLOAD -> CachePolicy.ANY_CACHE;
              case UPLOAD ->
                  uploadLocalResults ? CachePolicy.REMOTE_CACHE_ONLY : CachePolicy.NO_CACHE;
            });
  }

  private String maybeGetStackTrace(Exception e) {
    return verboseFailures ? Throwables.getStackTraceAsString(e) : e.getMessage();
  }

  private record RepoRemotePathResolver(Path fetchedRepoMarkerFile, Path fetchedRepoDir)
      implements RemotePathResolver {

    @Override
    public String localPathToOutputPath(Path path) {
      // Map repo marker file and contents to fixed locations under the fake remote exec root.
      if (path.equals(fetchedRepoMarkerFile)) {
        return MARKER_FILE_PATH;
      }
      if (path.equals(fetchedRepoDir)) {
        return REPO_DIRECTORY_PATH;
      }
      return REPO_DIRECTORY_PATH + "/" + path.relativeTo(fetchedRepoDir).getPathString();
    }

    @Override
    public String localPathToOutputPath(PathFragment execPath) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public Path outputPathToLocalPath(String outputPath) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public PathFragment localPathToExecPath(PathFragment localPath) {
      throw new UnsupportedOperationException("Not used");
    }
  }
}
