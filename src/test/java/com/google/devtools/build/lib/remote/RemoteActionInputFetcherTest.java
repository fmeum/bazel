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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventBusEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteActionInputFetcher}. */
@RunWith(JUnit4.class)
public class RemoteActionInputFetcherTest extends ActionInputPrefetcherTestBase {
  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());

  private DigestUtil digestUtil;

  @Override
  public void setUp() throws IOException {
    super.setUp();
    Path dev = fs.getPath("/dev");
    dev.createDirectory();
    dev.setWritable(false);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION);
  }

  @Override
  protected AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas) {
    CombinedCache combinedCache = newCombinedCache(digestUtil, cas);
    return new RemoteActionInputFetcher(
        new Reporter(new EventBusEventHandler(eventBus)),
        "none",
        "none",
        combinedCache,
        execRoot,
        tempPathGenerator,
        DUMMY_REMOTE_OUTPUT_CHECKER,
        ActionOutputDirectoryHelper.createForTesting(),
        OutputPermissions.READONLY);
  }

  @Test
  public void testStagingVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(EventBusEventHandler.createWithNewEventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);
    VirtualActionInput a = ActionsTestUtil.createVirtualActionInput("file1", "hello world");

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(a),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert
    Path p = execRoot.getRelative(a.getExecPath());
    assertThat(FileSystemUtils.readContent(p, StandardCharsets.UTF_8)).isEqualTo("hello world");
    assertThat(p.isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingEmptyVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(EventBusEventHandler.createWithNewEventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(VirtualActionInput.EMPTY_MARKER),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert that nothing happened
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_missingFiles_failsWithSpecificMessage() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    var error =
        assertThrows(
            BulkTransferException.class,
            () ->
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(a),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    var m = metadata.get(a);
    var digest = DigestUtil.buildDigest(m.getDigest(), m.getSize());
    assertThat(error)
        .hasMessageThat()
        .contains(String.format("%s/%s", digest.getHash(), digest.getSizeBytes()));
  }

  @Test
  public void injectRemoteRepo_invalidPath_throwsIOException() {
    // Tests that RemoteExternalOverlayFileSystem and RemoteActionInputFetcher
    // maintain path containment within the repository directory.
    PathFragment externalDir = PathFragment.create("/output_base/external");
    InMemoryFileSystem hostFs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    RemoteExternalOverlayFileSystem overlayFs =
        new RemoteExternalOverlayFileSystem(externalDir, hostFs);
    overlayFs.beforeCommand(
        newCombinedCache(digestUtil, new HashMap<>()),
        /* inputPrefetcher= */ null,
        new Reporter(EventBusEventHandler.createWithNewEventBus()),
        "none",
        "none",
        /* evaluator= */ null,
        /* remoteCacheTtl= */ Duration.ofHours(1));
    for (String invalidPath :
        ImmutableList.of("../../../../../.bashrc.bzl", "/etc/cron.d/evil.bzl")) {
      Tree tree =
          Tree.newBuilder()
              .setRoot(
                  Directory.newBuilder()
                      .addFiles(
                          FileNode.newBuilder()
                              .setName(invalidPath)
                              .setDigest(
                                  digestUtil.compute("payload\n".getBytes(StandardCharsets.UTF_8)))
                              .build())
                      .build())
              .build();
      assertThrows(
          IOException.class,
          () ->
              overlayFs.injectRemoteRepo(
                  RepositoryName.createUnvalidated("repo"), tree, "MARKER\n"));
    }
  }

  @Test
  public void injectRemoteRepo_reinjection_keepsMatchingNativeEntries() throws Exception {
    PathFragment externalDir = PathFragment.create("/output_base/external");
    RepositoryName repo = RepositoryName.createUnvalidated("repo");
    Path repoDir = fs.getPath("/output_base/external/repo");
    Map<HashCode, byte[]> cas = new HashMap<>();
    Digest defsDigest = addBlob(cas, "x = 1\n");
    Digest aDigest = addBlob(cas, "aaa");
    Digest bDigest = addBlob(cas, "bbb");
    Digest cDigest = addBlob(cas, "ccc");
    Digest eDigest = addBlob(cas, "eee");
    Directory sub = Directory.newBuilder().addFiles(fileNode("c.txt", cDigest)).build();
    Directory d = Directory.newBuilder().addFiles(fileNode("e.txt", eDigest)).build();
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(fileNode("defs.bzl", defsDigest))
                    .addFiles(fileNode("a.txt", aDigest))
                    .addFiles(fileNode("b.txt", bDigest))
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("a.txt"))
                    .addDirectories(dirNode("sub", sub))
                    .addDirectories(dirNode("d", d)))
            .addChildren(sub)
            .addChildren(d)
            .build();

    // Inject the repo and materialize all of it, as if local actions had consumed its files.
    RemoteExternalOverlayFileSystem overlayFs = newOverlayFs(externalDir, cas);
    assertThat(overlayFs.injectRemoteRepo(repo, tree, "MARKER\n")).isTrue();
    overlayFs.ensureSubtreeMaterialized(repoDir.asFragment());
    overlayFs.afterCommand();
    assertThat(readNative(repoDir, "b.txt")).isEqualTo("bbb");
    assertThat(readNative(repoDir, "sub/c.txt")).isEqualTo("ccc");
    assertThat(repoDir.getChild("link").readSymbolicLink()).isEqualTo(PathFragment.create("a.txt"));
    // Add entries that no injection would create.
    FileSystemUtils.writeContent(repoDir.getChild("extra.txt"), StandardCharsets.UTF_8, "extra");
    repoDir.getChild("extradir").createDirectory();
    FileSystemUtils.writeContent(
        repoDir.getRelative("extradir/f.txt"), StandardCharsets.UTF_8, "f");

    // A new server injects changed contents: b.txt has a different size, sub is a file now, link
    // points elsewhere and n.txt is new. defs.bzl, a.txt and d/e.txt are unchanged.
    Digest bNewDigest = digestUtil.compute("bbbb".getBytes(StandardCharsets.UTF_8));
    Digest subDigest = digestUtil.compute("sub".getBytes(StandardCharsets.UTF_8));
    Digest nDigest = digestUtil.compute("nnn".getBytes(StandardCharsets.UTF_8));
    Tree newTree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(fileNode("defs.bzl", defsDigest))
                    .addFiles(fileNode("a.txt", aDigest))
                    .addFiles(fileNode("b.txt", bNewDigest))
                    .addFiles(fileNode("sub", subDigest))
                    .addFiles(fileNode("n.txt", nDigest))
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("b.txt"))
                    .addDirectories(dirNode("d", d)))
            .addChildren(d)
            .build();
    // The empty CAS ensures that the injection can only succeed if the previously materialized
    // defs.bzl is verified and reused rather than prefetched again.
    RemoteExternalOverlayFileSystem newOverlayFs = newOverlayFs(externalDir, new HashMap<>());
    assertThat(newOverlayFs.injectRemoteRepo(repo, newTree, "MARKER\n")).isTrue();
    newOverlayFs.afterCommand();

    assertThat(readNative(repoDir, "defs.bzl")).isEqualTo("x = 1\n");
    assertThat(readNative(repoDir, "a.txt")).isEqualTo("aaa");
    assertThat(readNative(repoDir, "d/e.txt")).isEqualTo("eee");
    assertThat(repoDir.getChild("b.txt").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(repoDir.getChild("sub").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(repoDir.getChild("link").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(repoDir.getChild("n.txt").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(repoDir.getChild("extra.txt").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(repoDir.getChild("extradir").exists(Symlinks.NOFOLLOW)).isFalse();
    // The overlay serves the new contents regardless of what was kept.
    Path overlaidRepoDir = newOverlayFs.getPath(repoDir.asFragment());
    assertThat(overlaidRepoDir.getChild("b.txt").getFileSize()).isEqualTo(4);
    assertThat(overlaidRepoDir.getChild("sub").isFile()).isTrue();
    assertThat(overlaidRepoDir.getChild("n.txt").getFileSize()).isEqualTo(3);
    assertThat(overlaidRepoDir.getChild("link").readSymbolicLink())
        .isEqualTo(PathFragment.create("b.txt"));
  }

  @Test
  public void injectRemoteRepo_repoDirIsSymlink_replaced() throws Exception {
    PathFragment externalDir = PathFragment.create("/output_base/external");
    RepositoryName repo = RepositoryName.createUnvalidated("repo");
    Path repoDir = fs.getPath("/output_base/external/repo");
    // The repo directory may be a symlink into the local repo contents cache.
    Path cacheDir = fs.getPath("/cache/repo");
    cacheDir.createDirectoryAndParents();
    FileSystemUtils.writeContent(cacheDir.getChild("a.txt"), StandardCharsets.UTF_8, "aaa");
    repoDir.getParentDirectory().createDirectoryAndParents();
    repoDir.createSymbolicLink(cacheDir);
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        fileNode(
                            "a.txt", digestUtil.compute("aaa".getBytes(StandardCharsets.UTF_8)))))
            .build();

    RemoteExternalOverlayFileSystem overlayFs = newOverlayFs(externalDir, new HashMap<>());
    assertThat(overlayFs.injectRemoteRepo(repo, tree, "MARKER\n")).isTrue();
    overlayFs.afterCommand();

    assertThat(repoDir.isSymbolicLink()).isFalse();
    assertThat(repoDir.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(repoDir.getChild("a.txt").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(readNative(cacheDir, "a.txt")).isEqualTo("aaa");
  }

  private RemoteExternalOverlayFileSystem newOverlayFs(
      PathFragment externalDir, Map<HashCode, byte[]> cas) {
    RemoteExternalOverlayFileSystem overlayFs =
        new RemoteExternalOverlayFileSystem(externalDir, fs);
    CombinedCache combinedCache = newCombinedCache(digestUtil, cas);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBusEventHandler(eventBus)),
            "none",
            "none",
            combinedCache,
            overlayFs.getPath(execRoot.getPathString()),
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);
    overlayFs.beforeCommand(
        combinedCache,
        actionInputFetcher,
        new Reporter(new EventBusEventHandler(eventBus)),
        "none",
        "none",
        /* evaluator= */ null,
        /* remoteCacheTtl= */ Duration.ofHours(1));
    return overlayFs;
  }

  private Digest addBlob(Map<HashCode, byte[]> cas, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    Digest digest = digestUtil.compute(bytes);
    cas.put(HashCode.fromString(digest.getHash()), bytes);
    return digest;
  }

  private static FileNode fileNode(String name, Digest digest) {
    return FileNode.newBuilder().setName(name).setDigest(digest).build();
  }

  private DirectoryNode dirNode(String name, Directory directory) {
    return DirectoryNode.newBuilder()
        .setName(name)
        .setDigest(digestUtil.compute(directory))
        .build();
  }

  private static String readNative(Path repoDir, String relativePath) throws IOException {
    return FileSystemUtils.readContent(repoDir.getRelative(relativePath), StandardCharsets.UTF_8);
  }

  @Test
  public void rewoundActionOutput_execRootOnOverlayFileSystem_redownloaded() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world", metadata, cas);
    // When the remote repo contents cache is enabled, the exec root lies on the file system
    // overlaying the host file system that downloads are written to.
    RemoteExternalOverlayFileSystem overlayFs =
        new RemoteExternalOverlayFileSystem(PathFragment.create("/output_base/external"), fs);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBusEventHandler(eventBus)),
            "none",
            "none",
            newCombinedCache(digestUtil, cas),
            overlayFs.getPath(execRoot.getPathString()),
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));
    assertThat(FileSystemUtils.readContent(a.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("hello world");

    // Rewinding deletes the output and requires it to be downloaded again.
    a.getPath().delete();
    actionInputFetcher.handleRewoundActionOutputs(ImmutableList.of(a));

    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));
    assertThat(FileSystemUtils.readContent(a.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("hello world");
  }

  private CombinedCache newCombinedCache(DigestUtil digestUtil, Map<HashCode, byte[]> cas) {
    Map<Digest, byte[]> cacheEntries = Maps.newHashMapWithExpectedSize(cas.size());
    for (Map.Entry<HashCode, byte[]> entry : cas.entrySet()) {
      cacheEntries.put(
          DigestUtil.buildDigest(entry.getKey().asBytes(), entry.getValue().length),
          entry.getValue());
    }
    return new CombinedCache(
        new InMemoryCacheClient(cacheEntries),
        /* diskCacheClient= */ null,
        /* symlinkTemplate= */ null,
        digestUtil,
        /* chunkingFunction= */ null,
        new ChunkLocationMap());
  }
}
