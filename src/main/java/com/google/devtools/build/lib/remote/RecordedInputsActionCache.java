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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.whenAllSucceed;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.BulkTransfers.waitForBulkTransfer;
import static com.google.devtools.build.lib.unsafe.StringUnsafe.getInternalStringBytes;
import static java.util.stream.Collectors.joining;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A key-value store on top of the AC of an ordinary remote cache in which the full key is only
 * known after a computation has completed: it consists of a "predeclared input hash" known up front
 * and a list of {@link RepoRecordedInput}s that were discovered while the computation ran.
 *
 * <p>Entries are AC entries for a synthetic command with the current hash as the action's salt. If
 * no {@link RepoRecordedInput}s were recorded, the predeclared input hash directly keys the final
 * entry. Otherwise, the AC entry for the predeclared input hash is an intermediate entry that lists
 * (in its stdout) one or more batches of {@link RepoRecordedInput}s that a previous computation
 * with this hash consumed. A lookup requests the current values of these inputs and computes the
 * next hash to look up by a rolling construction that combines the previous hash with the string
 * representations of the {@link RepoRecordedInput.WithValue}s. This process is repeated until a
 * final entry is found or no matching entry exists.
 *
 * <p>By representing entries with recorded inputs as DAGs of AC entries, lookups are efficient
 * (they don't scale with the number of cached entries per predeclared input hash) and regular LRU
 * eviction policies remain effective for the most part. If a computation often requests different
 * inputs even with the same predeclared input hash and previously requested inputs and values, it
 * could result in large action results that grow over time. This is considered an acceptable
 * trade-off for simplicity for now and could be mitigated in the future by an explicit GC mechanism
 * such as "least recently added" eviction when the size of action result exceeds a certain
 * threshold.
 *
 * <p>The shape of final entries (which output files and directories they contain) is up to the
 * user of this class, which is also responsible for uploading them under the hash returned by
 * {@link #uploadIntermediateActionResults}.
 */
final class RecordedInputsActionCache {
  private static final Splitter SPLIT_ON_SPACE = Splitter.on(' ');
  private static final Splitter SPLIT_ON_NEWLINE = Splitter.on('\n').omitEmptyStrings();
  private static final Directory INPUT_ROOT = Directory.getDefaultInstance();

  private final BlazeDirectories directories;
  private final CombinedCache cache;
  private final Command command;
  private final ByteString commandBytes;
  private final Digest commandDigest;
  private final Action baseAction;

  /**
   * @param guid salts all cache keys; has to be unique per user of this class and has to change
   *     whenever previously cached entries may no longer be valid
   * @param outputFiles the paths of the output files of final entries
   * @param outputDirectories the paths of the output directories of final entries
   */
  // addOutputFiles and addOutputDirectories are deprecated in REAPI v2 in favor of addOutputPaths,
  // but are populated here for backwards compatibility with older RE backends.
  @SuppressWarnings("deprecation")
  RecordedInputsActionCache(
      BlazeDirectories directories,
      CombinedCache cache,
      UUID guid,
      ImmutableList<String> outputFiles,
      ImmutableList<String> outputDirectories) {
    this.directories = directories;
    this.cache = cache;
    this.command =
        Command.newBuilder()
            // A unique but nonsensical command that is valid on all platforms. It is never
            // executed, but should pass all checks that an RE backend may apply to commands.
            .addArguments(guid.toString())
            .addAllOutputPaths(outputFiles)
            .addAllOutputPaths(outputDirectories)
            .addAllOutputFiles(outputFiles)
            .addAllOutputDirectories(outputDirectories)
            .setPlatform(Platform.getDefaultInstance())
            .build();
    this.commandBytes = command.toByteString();
    this.commandDigest = cache.digestUtil().compute(command);
    this.baseAction =
        Action.newBuilder()
            .setCommandDigest(commandDigest)
            .setInputRootDigest(cache.digestUtil().compute(INPUT_ROOT))
            .setPlatform(Platform.getDefaultInstance())
            .build();
  }

  Command command() {
    return command;
  }

  Action buildAction(String inputHash) {
    // We choose to embed the hash into the salt simply because that results in a constant Command
    // message.
    return baseAction.toBuilder()
        .setSalt(ByteString.copyFrom(StringUnsafe.getByteArray(inputHash)))
        .build();
  }

  /**
   * Uploads the intermediate action results representing the inputs recorded at runtime and returns
   * the input hash to use for the final action result.
   */
  String uploadIntermediateActionResults(
      RemoteActionExecutionContext context,
      String predeclaredInputHash,
      List<RepoRecordedInput.WithValue> recordedInputValues)
      throws IOException, InterruptedException {
    // The command is shared by all action results and small enough that FindMissingBlobs is not
    // worthwhile. The REAPI spec requires the command to be uploaded before an action result that
    // references it.
    waitForBulkTransfer(
        ImmutableSet.of(
            cache.uploadBlob(
                context, commandDigest, (Blob) commandBytes::newInput, /* force= */ false)));

    String rollingHash = predeclaredInputHash;
    var batches = RepoRecordedInput.WithValue.splitIntoBatches(recordedInputValues);
    var futures = new ArrayList<ListenableFuture<Void>>(batches.size());
    for (var batch : batches) {
      futures.add(
          addToActionResult(
              context,
              buildAction(rollingHash),
              Collections2.transform(batch, RepoRecordedInput.WithValue::input)));
      for (var recordedInputValue : batch) {
        rollingHash = rollForwardHash(rollingHash, recordedInputValue);
      }
    }
    waitForBulkTransfer(futures);
    return rollingHash;
  }

  /**
   * Adds the given set of recorded inputs as one of the alternative paths to the action result for
   * the given action, if not already present.
   *
   * <p>Most computations with a fixed previous batch of hashes (in particular, the same predeclared
   * inputs hash) will request a fixed set of inputs in the next batch. Thus, most intermediate
   * action results will only contain a single set of recorded inputs.
   */
  private ListenableFuture<Void> addToActionResult(
      RemoteActionExecutionContext context,
      Action action,
      Collection<RepoRecordedInput> newInputs) {
    var actionKey = cache.digestUtil().computeActionKey(action);
    var currentInputsFuture =
        Futures.transformAsync(
            cache.downloadActionResultAsync(
                context, actionKey, /* inlineOutErr= */ true, ImmutableSet.of()),
            (currentResult) -> {
              if (currentResult == null || !hasStdout(currentResult.actionResult())) {
                return immediateFuture("");
              }
              return fetchStdout(context, currentResult.actionResult());
            },
            directExecutor());
    return Futures.transformAsync(
        currentInputsFuture,
        currentInputsString -> {
          // RepoRecordedInput.toString() is guaranteed to return a string that doesn't contain
          // spaces or newlines. We can thus safely use spaces to separate inputs within a batch
          // and newlines to separate different batches.
          var newInputString =
              newInputs.stream().map(RepoRecordedInput::toString).collect(joining(" "));
          if (SPLIT_ON_NEWLINE
              .splitToStream(currentInputsString)
              .anyMatch(newInputString::equals)) {
            // The current batch of inputs is already present, no need to update the action result.
            return immediateFuture(null);
          }
          // Add the new inputs to the top so that the most recently added inputs stay at the top.
          // This could be used to implement a simple "least recently added" eviction strategy in
          // the future in case the size of action results becomes a concern.
          //
          // Note that this update is inherently racy: multiple clients may add inputs concurrently,
          // resulting in some added inputs being lost since the REAPI does not provide a way to
          // update action results atomically. However, since different batches of inputs are
          // already rare and them being added concurrently even more so, the temporary loss of a
          // cache entry is an acceptable trade-off for simplicity.
          var newInputsString = newInputString + '\n' + currentInputsString;
          var stdoutBytes = getInternalStringBytes(newInputsString);
          var stdoutDigest = cache.digestUtil().compute(stdoutBytes);
          var actionResult =
              ActionResult.newBuilder().setExitCode(0).setStdoutDigest(stdoutDigest).build();
          return whenAllSucceed(
                  cache.uploadBlob(
                      context,
                      actionKey.digest(),
                      (Blob) action.toByteString()::newInput,
                      /* force= */ false),
                  cache.uploadBlob(
                      context,
                      stdoutDigest,
                      (Blob) ByteString.copyFrom(stdoutBytes)::newInput,
                      /* force= */ false))
              .callAsync(
                  () -> cache.uploadActionResult(context, actionKey, actionResult),
                  directExecutor());
        },
        directExecutor());
  }

  /** Represents a single AC entry in the internal format used by this cache. */
  private sealed interface CacheEntry {
    /** A final cache entry in the shape chosen by the user of this class. */
    record Final(ActionResult actionResult) implements CacheEntry {}

    /**
     * An intermediate cache entry that points to the keys of any number of further AC entries,
     * which can themselves be intermediate or final entries. They are tried in order.
     *
     * @param nextInputHashes the keys under which the next AC entries should be looked up
     */
    record Intermediate(ImmutableList<String> nextInputHashes) implements CacheEntry {}

    /**
     * The cache entry didn't match any of the formats expected by this version of the cache for the
     * given human-readable reason.
     */
    record Invalid(String reason) implements CacheEntry {}
  }

  /**
   * Fetches the action result of the final cache entry for the given predeclared input hash by
   * recursively following intermediate entries if needed or returns null if no final entry could be
   * found or a Skyframe restart is needed.
   *
   * @param inlineOutputFiles the output files of the final entry that the cache should attempt to
   *     inline into the action result
   * @param isFinal returns whether the given action result has the shape of a final entry
   */
  @Nullable
  ActionResult fetchFinalActionResult(
      SkyFunction.Environment env,
      RemoteActionExecutionContext context,
      String predeclaredInputHash,
      ImmutableSet<String> inlineOutputFiles,
      Predicate<ActionResult> isFinal)
      throws IOException, InterruptedException {
    var currentHashes = ImmutableList.of(predeclaredInputHash);
    while (!currentHashes.isEmpty()) {
      var nextHashes = ImmutableList.<String>builder();
      for (var hash : currentHashes) {
        switch (fetchCacheEntry(env, context, hash, inlineOutputFiles, isFinal)) {
          case CacheEntry.Final(ActionResult actionResult) -> {
            return actionResult;
          }
          case CacheEntry.Intermediate(ImmutableList<String> nextInputHashes) ->
              nextHashes.addAll(nextInputHashes);
          case CacheEntry.Invalid(String reason) -> env.getListener().handle(Event.warn(reason));
          case null -> {
            // Keep checking hashes to batch missing values in fewer restarts.
            Preconditions.checkState(env.valuesMissing());
          }
        }
      }
      if (env.valuesMissing()) {
        return null;
      }
      currentHashes = nextHashes.build();
    }
    return null;
  }

  // Returns null if and only if values are missing.
  @Nullable
  private CacheEntry fetchCacheEntry(
      SkyFunction.Environment env,
      RemoteActionExecutionContext context,
      String inputHash,
      ImmutableSet<String> inlineOutputFiles,
      Predicate<ActionResult> isFinal)
      throws IOException, InterruptedException {
    var actionKey = new ActionKey(cache.digestUtil().compute(buildAction(inputHash)));
    // If the action result is an intermediate node, the full result will be contained in the
    // stdout, which should thus be inlined.
    var cachedActionResult =
        cache.downloadActionResult(context, actionKey, /* inlineOutErr= */ true, inlineOutputFiles);
    if (cachedActionResult == null) {
      return new CacheEntry.Intermediate(ImmutableList.of());
    }
    var actionResult = cachedActionResult.actionResult();

    if (actionResult.getExitCode() != 0) {
      return new CacheEntry.Invalid(
          "Unexpected exit code in action result for remote cache entry of %s:\n%s"
              .formatted(context.getRequestMetadata().getActionId(), actionResult));
    }
    if (isFinal.test(actionResult)) {
      return new CacheEntry.Final(actionResult);
    }
    if (!(actionResult.getOutputFilesCount() == 0
        && actionResult.getOutputDirectoriesCount() == 0
        && actionResult.getOutputSymlinksCount() == 0
        && hasStdout(actionResult))) {
      return new CacheEntry.Invalid(
          "Unexpected intermediate action result for remote cache entry of %s:\n%s"
              .formatted(context.getRequestMetadata().getActionId(), actionResult));
    }
    var stdoutFuture = fetchStdout(context, actionResult);
    waitForBulkTransfer(ImmutableList.of(stdoutFuture));

    // The action result's stdout contains multiple lines, each representing a batch of
    // RepoRecordedInputs separated by spaces. A given batch is valid only if all inputs in the
    // batch are, but separate batches are tried independently.
    var nextInputBatches =
        SPLIT_ON_NEWLINE
            .splitToStream(stdoutFuture.resultNow())
            .map(
                line ->
                    SPLIT_ON_SPACE
                        .splitToStream(line)
                        .map(RepoRecordedInput::parse)
                        .collect(toImmutableList()))
            .collect(toImmutableList());
    var uniqueNextInputs =
        nextInputBatches.stream().flatMap(List::stream).collect(toImmutableSet());
    RepoRecordedInput.prefetch(env, directories, uniqueNextInputs);
    if (env.valuesMissing()) {
      return null;
    }
    var nextHashes = ImmutableList.<String>builder();
    nextBatch:
    for (var batch : nextInputBatches) {
      var rollingHash = inputHash;
      for (var input : batch) {
        var value = input.getValue(env, directories);
        // Values have been prefetched above.
        Preconditions.checkState(!env.valuesMissing());
        if (!(value instanceof RepoRecordedInput.MaybeValue.Valid(String valueString))) {
          continue nextBatch;
        }
        rollingHash =
            rollForwardHash(rollingHash, new RepoRecordedInput.WithValue(input, valueString));
      }
      nextHashes.add(rollingHash);
    }
    return new CacheEntry.Intermediate(nextHashes.build());
  }

  private static String rollForwardHash(String hash, RepoRecordedInput.WithValue inputWithValue) {
    return new Fingerprint()
        .addString(hash)
        .addString(inputWithValue.toString())
        .hexDigestAndReset();
  }

  /** Returns whether the action result has stdout, inline or in the CAS. */
  private static boolean hasStdout(ActionResult actionResult) {
    return !actionResult.getStdoutRaw().isEmpty()
        || actionResult.getStdoutDigest().getSizeBytes() > 0;
  }

  private ListenableFuture<String> fetchStdout(
      RemoteActionExecutionContext context, ActionResult actionResult) {
    if (!actionResult.getStdoutRaw().isEmpty()) {
      return immediateFuture(
          StringUnsafe.newInstance(actionResult.getStdoutRaw().toByteArray(), StringUnsafe.LATIN1));
    }
    return Futures.transform(
        cache.downloadBlob(context, actionResult.getStdoutDigest()),
        stdout -> StringUnsafe.newInstance(stdout, StringUnsafe.LATIN1),
        directExecutor());
  }
}
