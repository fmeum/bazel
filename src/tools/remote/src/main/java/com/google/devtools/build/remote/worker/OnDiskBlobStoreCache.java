// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.remote.worker;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkAbsolutePathStrategy;
import build.bazel.remote.execution.v2.SymlinkNode;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.CombinedCache;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link CombinedCache} backed by an {@link DiskCacheClient}. */
class OnDiskBlobStoreCache extends CombinedCache {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private record DigestAndInvocation(Digest digest, String invocationId) {}

  private final RemoteWorkerOptions remoteWorkerOptions;
  private final ConcurrentHashMap<DigestAndInvocation, Integer>
      numberOfDownloadsPerDigestAndInvocation = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Digest, Integer> numberOfUploadsPerLossCandidate =
      new ConcurrentHashMap<>();

  public OnDiskBlobStoreCache(
      Path cacheDir, DigestUtil digestUtil, RemoteWorkerOptions remoteWorkerOptions)
      throws IOException {
    super(
        /* remoteCacheClient= */ null,
        new DiskCacheClient(cacheDir, digestUtil),
        /* symlinkTemplate= */ null,
        digestUtil,
        /* chunkingEnabled= */ false);
    this.remoteWorkerOptions = remoteWorkerOptions;
  }

  @Override
  public CacheCapabilities getRemoteCacheCapabilities() {
    return CacheCapabilities.newBuilder()
        .setActionCacheUpdateCapabilities(
            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
        .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
        .build();
  }

  /** If the given blob exists, updates its mtime and returns true. Otherwise, returns false. */
  boolean refresh(Digest digest) throws IOException {
    return diskCacheClient.refresh(diskCacheClient.toPath(digest, Store.CAS));
  }

  @SuppressWarnings("ProtoParseWithRegistry")
  public void downloadTree(
      RemoteActionExecutionContext context, Digest rootDigest, Path rootLocation)
      throws IOException, InterruptedException {
    rootLocation.createDirectoryAndParents();
    Directory directory = Directory.parseFrom(getFromFuture(downloadBlob(context, rootDigest)));
    HashSet<Path> childrenSeen = new HashSet<>();
    for (FileNode file : directory.getFilesList()) {
      Path dst = rootLocation.getRelative(unicodeToInternal(file.getName()));
      if (!childrenSeen.add(dst)) {
        throw new IOException("Duplicate child '%s' in directory %s".formatted(dst, directory));
      }
      getFromFuture(downloadFile(context, dst, file.getDigest()));
      dst.setExecutable(file.getIsExecutable());
    }
    for (SymlinkNode symlink : directory.getSymlinksList()) {
      Path dst = rootLocation.getRelative(unicodeToInternal(symlink.getName()));
      if (!childrenSeen.add(dst)) {
        throw new IOException("Duplicate child '%s' in directory %s".formatted(dst, directory));
      }
      // TODO(fmeum): The following line is not generally correct: The remote execution API allows
      //  for non-normalized symlink targets, but the normalization applied by PathFragment.create
      //  does not take directory symlinks into account. However, Bazel's file system API does not
      //  currently offer a way to specify a raw String as a symlink target.
      // https://github.com/bazelbuild/bazel/issues/14224
      dst.createSymbolicLink(PathFragment.create(unicodeToInternal(symlink.getTarget())));
    }
    for (DirectoryNode child : directory.getDirectoriesList()) {
      Path dst = rootLocation.getRelative(unicodeToInternal(child.getName()));
      if (!childrenSeen.add(dst)) {
        throw new IOException("Duplicate child '%s' in directory %s".formatted(dst, directory));
      }
      downloadTree(context, child.getDigest(), dst);
    }
  }

  @Override
  public ListenableFuture<byte[]> downloadBlob(
      RemoteActionExecutionContext context, Digest digest) {
    if (remoteWorkerOptions.getErrorOnDuplicateDownloads()) {
      // Only populate numberOfDownloadsPerDigestAndInvocation when fakeErrorForDuplicatedDownloads
      // is enabled to avoid unnecessary unbounded memory growth.
      int numberOfDownloads =
          numberOfDownloadsPerDigestAndInvocation.merge(
              new DigestAndInvocation(digest, context.getRequestMetadata().getToolInvocationId()),
              1,
              Integer::sum);
      if (numberOfDownloads > 1) {
        return Futures.immediateFailedFuture(
            new IOException(
                String.format(
                    "Duplicate download of blob digest %s for invocation id %s",
                    DigestUtil.toString(digest),
                    context.getRequestMetadata().getToolInvocationId())));
      }
    }

    return super.downloadBlob(context, digest);
  }

  @Override
  public ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path file) {
    return maybeLoseAfterFirstUpload(digest, super.uploadFile(context, digest, file));
  }

  @Override
  public ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, Blob blob) {
    return maybeLoseAfterFirstUpload(digest, super.uploadBlob(context, digest, blob));
  }

  /**
   * Simulates a remote cache that loses CAS entries by deleting the blob with the given digest
   * from the CAS after its first successful upload (see {@code --lost_blob_percentage}).
   *
   * <p>Since the loss takes the form of an actual deletion, all kinds of requests referencing the
   * blob consistently observe it: findMissingBlobs reports it as missing, reads fail with
   * NOT_FOUND, executions report a FAILED_PRECONDITION with a MISSING violation when staging it as
   * an input, and action cache hits referencing it are treated as stale. Since only the first
   * upload of a blob is affected, clients can always recover by re-uploading or regenerating it.
   */
  private ListenableFuture<Void> maybeLoseAfterFirstUpload(
      Digest digest, ListenableFuture<Void> upload) {
    if (!isLossCandidate(digest)) {
      return upload;
    }
    return Futures.transform(
        upload,
        unused -> {
          if (numberOfUploadsPerLossCandidate.merge(digest, 1, Integer::sum) == 1) {
            try {
              diskCacheClient.toPath(digest, Store.CAS).delete();
              logger.atInfo().log(
                  "Simulated loss of CAS entry %s after its first upload",
                  DigestUtil.toString(digest));
            } catch (IOException e) {
              logger.atWarning().withCause(e).log(
                  "Failed to simulate loss of CAS entry %s", DigestUtil.toString(digest));
            }
          }
          return unused;
        },
        MoreExecutors.directExecutor());
  }

  private boolean isLossCandidate(Digest digest) {
    int lostBlobPercentage = remoteWorkerOptions.getLostBlobPercentage();
    // The empty blob is special-cased by clients and servers (including DiskCacheClient) as always
    // present, so it can't be lost.
    if (lostBlobPercentage == 0 || digest.getSizeBytes() == 0) {
      return false;
    }
    long hash =
        Hashing.murmur3_128()
            .newHasher()
            .putUnencodedChars(remoteWorkerOptions.getLostBlobSeed())
            .putUnencodedChars(digest.getHash())
            .hash()
            .asLong();
    return Math.floorMod(hash, 100) < lostBlobPercentage;
  }

  public DigestUtil getDigestUtil() {
    return digestUtil;
  }

  public DiskCacheClient getDiskCacheClient() {
    return checkNotNull(diskCacheClient);
  }
}
