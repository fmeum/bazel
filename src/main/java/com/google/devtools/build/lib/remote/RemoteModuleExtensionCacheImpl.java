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

import static com.google.devtools.build.lib.remote.util.BulkTransfers.waitForBulkTransfer;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.OutputFile;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.runtime.RemoteModuleExtensionCache;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * A cache for the results of reproducible module extensions that is backed by an ordinary remote
 * and/or disk cache.
 *
 * <p>Results are cached as AC entries for a synthetic command, organized as described in {@link
 * RecordedInputsActionCache}. A final entry has the opaque result as its single output file.
 *
 * <p>In contrast to {@link RemoteRepoContentsCacheImpl}, entries are also written to the disk cache
 * if there is one, as there is no other local cache that would persist them across server restarts.
 */
public final class RemoteModuleExtensionCacheImpl implements RemoteModuleExtensionCache {
  // Salts all cache keys; change it whenever previously cached entries may no longer be valid.
  private static final UUID GUID = UUID.fromString("6f0a5d1c-3f0e-4f0b-9d5e-2a8c1b7e4c93");
  private static final String RESULT_FILE_PATH = "extension_result.json";

  private final CombinedCache cache;
  private final String buildRequestId;
  private final String commandId;
  private final boolean acceptCached;
  private final boolean uploadLocalResults;
  private final boolean verboseFailures;
  private final RecordedInputsActionCache recordedInputsCache;

  public RemoteModuleExtensionCacheImpl(
      BlazeDirectories directories,
      CombinedCache cache,
      String buildRequestId,
      String commandId,
      boolean acceptCached,
      boolean uploadLocalResults,
      boolean verboseFailures) {
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
            /* outputFiles= */ ImmutableList.of(RESULT_FILE_PATH),
            /* outputDirectories= */ ImmutableList.of());
  }

  @Override
  @Nullable
  public byte[] lookup(
      String extensionId, String predeclaredInputHash, SkyFunction.Environment env)
      throws IOException, InterruptedException {
    if (!acceptCached) {
      return null;
    }
    var context = buildContext(extensionId);
    try {
      // The result is read right after and thus requested to be inlined.
      ActionResult finalEntry =
          recordedInputsCache.fetchFinalActionResult(
              env,
              context,
              predeclaredInputHash,
              /* inlineOutputFiles= */ ImmutableSet.of(RESULT_FILE_PATH),
              /* isFinal= */ actionResult ->
                  actionResult.getOutputFilesCount() == 1
                      && actionResult.getOutputDirectoriesCount() == 0
                      && actionResult.getOutputSymlinksCount() == 0);
      if (env.valuesMissing() || finalEntry == null) {
        return null;
      }
      OutputFile resultFile = finalEntry.getOutputFiles(0);
      // Inlining is an optional feature, so we have to be prepared to download the result.
      if (!resultFile.getContents().isEmpty()) {
        return resultFile.getContents().toByteArray();
      }
      ListenableFuture<byte[]> resultFuture =
          cache.downloadBlob(
              context, RESULT_FILE_PATH, /* execPath= */ null, resultFile.getDigest());
      waitForBulkTransfer(ImmutableList.of(resultFuture));
      return resultFuture.resultNow();
    } catch (IOException e) {
      throw new IOException(
          "Failed to look up module extension %s in the remote cache: %s"
              .formatted(extensionId, maybeGetStackTrace(e)),
          e);
    }
  }

  @Override
  public void put(
      String extensionId,
      String predeclaredInputHash,
      List<RepoRecordedInput.WithValue> recordedInputs,
      byte[] result,
      ExtendedEventHandler reporter)
      throws InterruptedException {
    if (!uploadLocalResults) {
      return;
    }
    var context = buildContext(extensionId);
    try {
      // TODO: Consider uploading asynchronously.
      var finalHash =
          recordedInputsCache.uploadIntermediateActionResults(
              context, predeclaredInputHash, recordedInputs);
      var action = recordedInputsCache.buildAction(finalHash);
      var actionKey = cache.digestUtil().computeActionKey(action);
      var resultDigest = cache.digestUtil().compute(result);
      // The REAPI spec requires all blobs referenced by an action result to be uploaded before it.
      waitForBulkTransfer(
          ImmutableList.of(
              cache.uploadBlob(
                  context,
                  actionKey.digest(),
                  (Blob) action.toByteString()::newInput,
                  /* force= */ false),
              cache.uploadBlob(
                  context,
                  resultDigest,
                  (Blob) ByteString.copyFrom(result)::newInput,
                  /* force= */ false)));
      waitForBulkTransfer(
          ImmutableList.of(
              cache.uploadActionResult(
                  context,
                  actionKey,
                  ActionResult.newBuilder()
                      .setExitCode(0)
                      .addOutputFiles(
                          OutputFile.newBuilder()
                              .setPath(RESULT_FILE_PATH)
                              .setDigest(resultDigest))
                      .build())));
    } catch (IOException e) {
      reporter.handle(
          Event.warn(
              "Failed to upload the result of module extension %s to the remote cache: %s"
                  .formatted(extensionId, maybeGetStackTrace(e))));
    }
  }

  private RemoteActionExecutionContext buildContext(String extensionId) {
    var metadata = TracingMetadataUtils.buildMetadata(buildRequestId, commandId, extensionId);
    return RemoteActionExecutionContext.create(metadata)
        .withReadCachePolicy(CachePolicy.ANY_CACHE)
        .withWriteCachePolicy(CachePolicy.ANY_CACHE);
  }

  private String maybeGetStackTrace(Exception e) {
    return verboseFailures ? Throwables.getStackTraceAsString(e) : e.getMessage();
  }
}
