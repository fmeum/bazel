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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.devtools.build.lib.remote.util.Futures.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.buildAction;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static com.google.devtools.build.lib.util.StringEncoding.platformToInternal;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.merkletree.MerkleTreeComputer;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** The remote package's implementation of {@link RepositoryRemoteExecutor}. */
public class RemoteRepositoryRemoteExecutor implements RepositoryRemoteExecutor {

  private final RemoteExecutionCache remoteCache;
  private final RemoteExecutionClient remoteExecutor;
  private final DigestUtil digestUtil;
  private final String buildRequestId;
  private final String commandId;
  private final String workspaceName;

  private final String remoteInstanceName;
  private final boolean acceptCached;
  private final boolean remoteExtraction;
  private final ImmutableMap<String, String> extractionExecProperties;
  private final FileSystem fileSystem;

  public RemoteRepositoryRemoteExecutor(
      RemoteExecutionCache remoteCache,
      RemoteExecutionClient remoteExecutor,
      DigestUtil digestUtil,
      String buildRequestId,
      String commandId,
      String workspaceName,
      String remoteInstanceName,
      boolean acceptCached,
      boolean remoteExtraction,
      ImmutableMap<String, String> extractionExecProperties,
      FileSystem fileSystem) {
    this.remoteCache = remoteCache;
    this.remoteExecutor = remoteExecutor;
    this.digestUtil = digestUtil;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.workspaceName = workspaceName;
    this.remoteInstanceName = remoteInstanceName;
    this.acceptCached = acceptCached;
    this.remoteExtraction = remoteExtraction;
    this.extractionExecProperties = extractionExecProperties;
    this.fileSystem = fileSystem;
  }

  private ExecutionResult downloadOutErr(RemoteActionExecutionContext context, ActionResult result)
      throws IOException, InterruptedException {
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "download stdout/stderr")) {
      byte[] stdout = new byte[0];
      if (!result.getStdoutRaw().isEmpty()) {
        stdout = result.getStdoutRaw().toByteArray();
      } else if (result.hasStdoutDigest()) {
        stdout =
            getFromFuture(
                remoteCache.downloadBlob(
                    context, "<stdout>", /* execPath= */ null, result.getStdoutDigest()));
      }

      byte[] stderr = new byte[0];
      if (!result.getStderrRaw().isEmpty()) {
        stderr = result.getStderrRaw().toByteArray();
      } else if (result.hasStderrDigest()) {
        stderr =
            getFromFuture(
                remoteCache.downloadBlob(
                    context, "<stderr>", /* execPath= */ null, result.getStderrDigest()));
      }

      return new ExecutionResult(result.getExitCode(), stdout, stderr);
    }
  }

  @Override
  public ExecutionResult execute(
      ImmutableList<String> arguments,
      ImmutableSortedMap<PathFragment, Path> inputFiles,
      ImmutableMap<String, String> executionProperties,
      ImmutableMap<String, String> environment,
      String workingDirectory,
      Duration timeout)
      throws IOException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "repository_rule");
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    Platform platform = PlatformUtils.buildPlatformProto(executionProperties);

    Command.Builder commandBuilder = Command.newBuilder().addAllArguments(arguments);
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(environment.keySet());
    for (String var : variables) {
      commandBuilder.addEnvironmentVariablesBuilder().setName(var).setValue(environment.get(var));
    }
    if (platform != null) {
      commandBuilder.setPlatform(platform);
    }
    if (workingDirectory != null) {
      commandBuilder.setWorkingDirectory(workingDirectory);
    }

    Command command = commandBuilder.build();
    Digest commandHash = digestUtil.compute(command);
    var merkleTree =
        new MerkleTreeComputer(
                digestUtil,
                /* remoteExecutionCache= */ null,
                buildRequestId,
                commandId,
                workspaceName)
            .buildForFiles(inputFiles);
    Action action =
        buildAction(
            commandHash, merkleTree.digest(), platform, timeout, acceptCached, /* salt= */ null);
    Digest actionDigest = digestUtil.compute(action);
    ActionKey actionKey = new ActionKey(actionDigest);
    CachedActionResult cachedActionResult;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
      cachedActionResult =
          remoteCache.downloadActionResult(
              context,
              actionKey,
              /* inlineOutErr= */ true,
              /* inlineOutputFiles= */ ImmutableSet.of());
    }
    ActionResult actionResult = null;
    if (cachedActionResult != null) {
      actionResult = cachedActionResult.actionResult();
    }
    if (actionResult == null || actionResult.getExitCode() != 0) {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs")) {
        Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
        additionalInputs.put(actionDigest, action);
        additionalInputs.put(commandHash, command);

        remoteCache.ensureInputsPresent(
            context,
            merkleTree,
            additionalInputs,
            /* force= */ true,
            /* remotePathResolver= */ null);
      }

      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "execute remotely")) {
        ExecuteRequest executeRequest =
            ExecuteRequest.newBuilder()
                .setActionDigest(actionDigest)
                .setInstanceName(remoteInstanceName)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setSkipCacheLookup(!acceptCached)
                .build();

        ExecuteResponse response =
            remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.NO_OP);
        actionResult = response.getResult();
      }
    }
    return downloadOutErr(context, actionResult);
  }

  // The decompressor runs on the JDK and from the jar of the Bazel server, which are staged below
  // the following directory of the input root.
  private static final String DECOMPRESSOR_DIRECTORY = "decompressor";
  private static final String DECOMPRESSOR_JDK = "jdk";
  private static final String DECOMPRESSOR_JAR = "bazel.jar";
  private static final String DECOMPRESSOR_MAIN_CLASS =
      "com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorMain";
  private static final String EXTRACTION_OUTPUT_DIRECTORY = "out";
  // The symlinks extracted from the archive, see DecompressorMain.
  private static final String EXTRACTION_SYMLINKS_FILE = "symlinks";
  private static final Duration EXTRACTION_TIMEOUT = Duration.ofMinutes(15);

  @Nullable
  @Override
  public ExtractedArchive extractArchive(
      FileArtifactValue archive,
      String type,
      String stripPrefix,
      int stripComponents,
      ImmutableMap<String, String> renameFiles)
      throws IOException, InterruptedException {
    if (!remoteExtraction) {
      return null;
    }
    MerkleTree.Uploadable decompressorTree = buildDecompressorTree();
    if (decompressorTree == null) {
      return null;
    }
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "repository_extraction");
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    String archiveName = "archive." + type;
    // The decompressor can neither read nor modify any other inputs, so it is safe for the output
    // directory to be a sibling of the inputs.
    Directory inputRoot =
        Directory.newBuilder()
            .addFiles(
                FileNode.newBuilder()
                    .setName(archiveName)
                    .setDigest(DigestUtil.buildDigest(archive.getDigest(), archive.getSize())))
            .addDirectories(
                DirectoryNode.newBuilder()
                    .setName(DECOMPRESSOR_DIRECTORY)
                    .setDigest(decompressorTree.digest()))
            .build();
    Digest inputRootDigest = digestUtil.compute(inputRoot);

    var arguments =
        ImmutableList.<String>builder()
            .add(
                DECOMPRESSOR_DIRECTORY + "/" + DECOMPRESSOR_JDK + "/bin/java",
                // Match the JVM options used by the Bazel server that are required by or affect the
                // handling of strings.
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Dfile.encoding=ISO-8859-1",
                "-Duser.country=",
                "-Duser.language=",
                "-Duser.variant=",
                "-cp",
                DECOMPRESSOR_DIRECTORY + "/" + DECOMPRESSOR_JAR,
                DECOMPRESSOR_MAIN_CLASS,
                archiveName,
                type,
                EXTRACTION_OUTPUT_DIRECTORY,
                EXTRACTION_SYMLINKS_FILE,
                internalToUnicode(stripPrefix),
                Integer.toString(stripComponents));
    for (Map.Entry<String, String> entry : renameFiles.entrySet()) {
      arguments.add(internalToUnicode(entry.getKey()), internalToUnicode(entry.getValue()));
    }
    Platform platform = PlatformUtils.buildPlatformProto(extractionExecProperties);
    Command.Builder commandBuilder =
        Command.newBuilder()
            .addAllArguments(arguments.build())
            // The JVM uses the locale to determine the encoding of file names.
            .addEnvironmentVariables(
                Command.EnvironmentVariable.newBuilder().setName("LC_ALL").setValue("C.UTF-8"))
            .addOutputPaths(EXTRACTION_OUTPUT_DIRECTORY)
            .addOutputPaths(EXTRACTION_SYMLINKS_FILE);
    addDeprecatedOutputPaths(commandBuilder);
    if (platform != null) {
      commandBuilder.setPlatform(platform);
    }
    Command command = commandBuilder.build();
    Digest commandDigest = digestUtil.compute(command);
    Action action =
        buildAction(
            commandDigest,
            inputRootDigest,
            platform,
            EXTRACTION_TIMEOUT,
            /* cacheable= */ true,
            /* salt= */ null);
    Digest actionDigest = digestUtil.compute(action);
    ActionKey actionKey = new ActionKey(actionDigest);

    ActionResult actionResult = null;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
      if (acceptCached) {
        CachedActionResult cachedActionResult =
            remoteCache.downloadActionResult(
                context,
                actionKey,
                /* inlineOutErr= */ false,
                /* inlineOutputFiles= */ ImmutableSet.of());
        if (cachedActionResult != null && cachedActionResult.actionResult().getExitCode() == 0) {
          actionResult = cachedActionResult.actionResult();
        }
      }
    }
    if (actionResult == null) {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs")) {
        // The archive is not uploaded, but has been fetched into the remote cache.
        remoteCache.ensureInputsPresent(
            context,
            decompressorTree,
            ImmutableMap.of(
                inputRootDigest, inputRoot, commandDigest, command, actionDigest, action),
            /* force= */ false,
            /* remotePathResolver= */ null);
      }
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "extract remotely")) {
        ExecuteRequest executeRequest =
            ExecuteRequest.newBuilder()
                .setActionDigest(actionDigest)
                .setInstanceName(remoteInstanceName)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setSkipCacheLookup(!acceptCached)
                .build();
        ExecuteResponse response =
            remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.NO_OP);
        actionResult = response.getResult();
      }
    }
    if (actionResult.getExitCode() != 0) {
      ExecutionResult outErr = downloadOutErr(context, actionResult);
      throw new IOException(
          "remote extraction failed with exit code %d: %s"
              .formatted(actionResult.getExitCode(), new String(outErr.stderr(), UTF_8).strip()));
    }
    OutputDirectory outputDirectory = null;
    for (OutputDirectory directory : actionResult.getOutputDirectoriesList()) {
      if (directory.getPath().equals(EXTRACTION_OUTPUT_DIRECTORY)) {
        outputDirectory = directory;
      }
    }
    OutputFile symlinksFile = null;
    for (OutputFile file : actionResult.getOutputFilesList()) {
      if (file.getPath().equals(EXTRACTION_SYMLINKS_FILE)) {
        symlinksFile = file;
      }
    }
    if (outputDirectory == null || symlinksFile == null) {
      throw new IOException("remote extraction didn't produce the expected outputs");
    }
    Tree tree =
        Tree.parseFrom(
            getFromFuture(remoteCache.downloadBlob(context, outputDirectory.getTreeDigest())),
            ExtensionRegistryLite.getEmptyRegistry());
    var childMap =
        tree.getChildrenList().stream()
            .collect(toImmutableMap(digestUtil::compute, directory -> directory, (a, b) -> a));
    var entries = ImmutableList.<TreeEntry>builder();
    collectTreeEntries(PathFragment.EMPTY_FRAGMENT, tree.getRoot(), childMap, entries);
    collectSymlinks(
        getFromFuture(remoteCache.downloadBlob(context, symlinksFile.getDigest())), entries);
    return new RemoteExtractedArchive(entries.build(), context, remoteCache);
  }

  @SuppressWarnings("deprecation")
  private static void addDeprecatedOutputPaths(Command.Builder commandBuilder) {
    // Populated for backwards compatibility with older RE backends.
    commandBuilder
        .addOutputDirectories(EXTRACTION_OUTPUT_DIRECTORY)
        .addOutputFiles(EXTRACTION_SYMLINKS_FILE);
  }

  /**
   * Returns the Merkle tree of the JDK and jar of the Bazel server, or null if they can't be
   * determined.
   */
  @Nullable
  private MerkleTree.Uploadable buildDecompressorTree() throws IOException, InterruptedException {
    String javaHome = System.getProperty("java.home");
    String classPath = System.getProperty("java.class.path");
    if (javaHome == null || classPath == null || classPath.contains(File.pathSeparator)) {
      return null;
    }
    Path jdk = fileSystem.getPath(platformToInternal(javaHome));
    Path jar = fileSystem.getPath(platformToInternal(classPath));
    if (!jdk.isDirectory() || !jar.isFile()) {
      return null;
    }
    var files = ImmutableSortedMap.<PathFragment, Path>naturalOrder();
    files.put(PathFragment.create(DECOMPRESSOR_JAR), jar);
    PathFragment jdkDirectory = PathFragment.create(DECOMPRESSOR_JDK);
    for (Path file : FileSystemUtils.traverseTree(jdk, path -> true)) {
      // Symlinks are followed since the JDK doesn't rely on them being preserved.
      if (file.isFile(Symlinks.FOLLOW)) {
        files.put(jdkDirectory.getRelative(file.relativeTo(jdk)), file);
      }
    }
    // The digests of these files are cached by the file system, so this is cheap after the first
    // extraction.
    return new MerkleTreeComputer(
            digestUtil, /* remoteExecutionCache= */ null, buildRequestId, commandId, workspaceName)
        .buildForFiles(files.buildOrThrow());
  }

  private static void collectTreeEntries(
      PathFragment path,
      Directory directory,
      ImmutableMap<Digest, Directory> childMap,
      ImmutableList.Builder<TreeEntry> entries)
      throws IOException {
    for (FileNode file : directory.getFilesList()) {
      entries.add(
          new TreeEntry.File(
              getChildPath(path, file.getName()),
              FileArtifactValue.createForRemoteFile(
                  DigestUtil.toBinaryDigest(file.getDigest()),
                  file.getDigest().getSizeBytes(),
                  /* locationIndex= */ 1),
              file.getIsExecutable()));
    }
    if (directory.getSymlinksCount() > 0) {
      throw new IOException("unexpected symlinks in %s".formatted(path));
    }
    for (DirectoryNode subdirectoryNode : directory.getDirectoriesList()) {
      PathFragment subdirectoryPath = getChildPath(path, subdirectoryNode.getName());
      Directory subdirectory = childMap.get(subdirectoryNode.getDigest());
      if (subdirectory == null) {
        throw new IOException("directory %s not found in tree".formatted(subdirectoryPath));
      }
      entries.add(new TreeEntry.Directory(subdirectoryPath));
      collectTreeEntries(subdirectoryPath, subdirectory, childMap, entries);
    }
  }

  /** Parses the symlinks file written by {@code DecompressorMain}. */
  private static void collectSymlinks(byte[] symlinksFile, ImmutableList.Builder<TreeEntry> entries)
      throws IOException {
    // The file consists of raw bytes, which are represented as Latin-1 by internal strings.
    List<String> fields = Splitter.on('\0').splitToList(new String(symlinksFile, ISO_8859_1));
    if (fields.size() % 3 != 1 || !fields.getLast().isEmpty()) {
      throw new IOException("invalid symlinks file");
    }
    for (int i = 0; i + 2 < fields.size(); i += 3) {
      PathFragment path = PathFragment.create(fields.get(i + 1));
      PathFragment target = PathFragment.create(fields.get(i + 2));
      boolean targetUnderRoot =
          switch (fields.get(i)) {
            case "A" -> true;
            case "R" -> false;
            default -> throw new IOException("invalid symlinks file");
          };
      if (path.isEmpty()
          || path.isAbsolute()
          || path.containsUplevelReferences()
          || (targetUnderRoot && (target.isAbsolute() || target.containsUplevelReferences()))) {
        throw new IOException("invalid symlink %s -> %s".formatted(path, target));
      }
      entries.add(new TreeEntry.Symlink(path, target, targetUnderRoot));
    }
  }

  private static PathFragment getChildPath(PathFragment parent, String name) throws IOException {
    String internalName = unicodeToInternal(name);
    if (internalName.isEmpty()
        || PathFragment.containsSeparator(internalName)
        || internalName.equals(".")
        || internalName.equals("..")) {
      throw new IOException("invalid name in tree: " + name);
    }
    return parent.getChild(internalName);
  }

  private record RemoteExtractedArchive(
      ImmutableList<TreeEntry> entries,
      RemoteActionExecutionContext context,
      RemoteExecutionCache cache)
      implements ExtractedArchive {
    @Override
    public void downloadFile(FileArtifactValue metadata, Path destination)
        throws IOException, InterruptedException {
      getFromFuture(
          cache.downloadFile(
              context,
              destination,
              DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())));
    }
  }
}
