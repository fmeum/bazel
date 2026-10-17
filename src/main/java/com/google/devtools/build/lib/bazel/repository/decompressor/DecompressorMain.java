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

package com.google.devtools.build.lib.bazel.repository.decompressor;

import static com.google.devtools.build.lib.util.StringEncoding.platformToInternal;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts an archive in the same way as {@code repository_ctx.extract}.
 *
 * <p>This is used to extract archives on remote executors, which run it with the JDK and jar of the
 * Bazel server.
 *
 * <p>Usage: {@code DecompressorMain <archive> <type> <output directory> <symlinks file> <strip
 * prefix> <strip components> [<file to rename> <new name>]...}, where relative paths are resolved
 * against the working directory.
 *
 * <p>Since remote executors differ in how they handle symlinks in outputs, the extracted symlinks
 * are removed from the output directory and recorded in the symlinks file instead. It consists of a
 * NUL-terminated kind, path and target for each symlink, where paths are relative to the output
 * directory and all strings are encoded as the raw bytes of the file system. The kind {@code A}
 * marks a symlink with an absolute target in the output directory, which is recorded relative to
 * it, and the kind {@code R} marks any other symlink.
 */
public final class DecompressorMain {

  public static void main(String[] args) throws Exception {
    if (args.length < 6 || args.length % 2 != 0) {
      System.err.println(
          "Usage: DecompressorMain <archive> <type> <output directory> <symlinks file> <strip"
              + " prefix> <strip components> [<file to rename> <new name>]...");
      System.exit(2);
    }
    try {
      extract(args);
    } catch (RepositoryFunctionException | IOException | RuntimeException e) {
      // The caller falls back to extracting the archive locally, which reports errors in detail.
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  @VisibleForTesting
  static void extract(String[] args)
      throws RepositoryFunctionException, InterruptedException, IOException {
    var fileSystem = new Utf8OnlyFileSystem();
    Path workingDirectory = fileSystem.getPath(platformToInternal(System.getProperty("user.dir")));
    Path outputDirectory = workingDirectory.getRelative(platformToInternal(args[2]));
    var renameFiles = ImmutableMap.<String, String>builder();
    for (int i = 6; i < args.length; i += 2) {
      renameFiles.put(platformToInternal(args[i]), platformToInternal(args[i + 1]));
    }
    var descriptor =
        DecompressorDescriptor.builder()
            .setContext("remote extraction")
            .setArchivePath(workingDirectory.getRelative(platformToInternal(args[0])))
            .setDestinationPath(outputDirectory)
            .setStripComponents(Integer.parseInt(args[5]))
            .setRenameFiles(renameFiles.buildOrThrow());
    if (!args[4].isEmpty()) {
      descriptor.setPrefix(platformToInternal(args[4]));
    }
    outputDirectory.createDirectoryAndParents();
    var unused = DecompressorValue.decompress(descriptor.build(), Optional.of(args[1]));
    FileSystemUtils.writeContent(
        workingDirectory.getRelative(platformToInternal(args[3])),
        moveOutSymlinks(outputDirectory));
  }

  /** Deletes all symlinks below the given directory and returns their encoded representation. */
  private static byte[] moveOutSymlinks(Path directory) throws IOException {
    var symlinks = new ByteArrayOutputStream();
    var sortedSymlinks =
        new ArrayList<>(FileSystemUtils.traverseTree(directory, Path::isSymbolicLink));
    sortedSymlinks.sort(Comparator.comparing(Path::asFragment));
    for (Path symlink : sortedSymlinks) {
      PathFragment target = symlink.readSymbolicLink();
      String kind = "R";
      if (target.isAbsolute()) {
        if (!target.startsWith(directory.asFragment())) {
          throw new IOException(
              "%s has an absolute target outside of the output directory: %s"
                  .formatted(symlink, target));
        }
        kind = "A";
        target = target.relativeTo(directory.asFragment());
      }
      for (String field :
          new String[] {
            kind, symlink.relativeTo(directory).getPathString(), target.getPathString()
          }) {
        symlinks.write(field.getBytes(ISO_8859_1));
        symlinks.write(0);
      }
      symlink.delete();
    }
    return symlinks.toByteArray();
  }

  /**
   * A file system that fails on paths that aren't valid UTF-8 instead of altering them, as they
   * can't be represented in the outputs of a remote action. Paths that aren't ASCII are rejected if
   * the JVM can't represent them faithfully.
   */
  private static final class Utf8OnlyFileSystem extends JavaIoFileSystem {
    private static final boolean SUPPORTS_NON_ASCII_PATHS =
        Set.of(UTF_8, ISO_8859_1).contains(Charset.forName(System.getProperty("sun.jnu.encoding")));

    Utf8OnlyFileSystem() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public File getIoFile(PathFragment path) {
      checkUtf8(path);
      return super.getIoFile(path);
    }

    @Override
    public java.nio.file.Path getNioPath(PathFragment path) {
      checkUtf8(path);
      return super.getNioPath(path);
    }

    @Override
    public void createSymbolicLink(
        PathFragment linkPath, PathFragment targetFragment, SymlinkTargetType type)
        throws IOException {
      checkUtf8(targetFragment);
      super.createSymbolicLink(linkPath, targetFragment, type);
    }

    private static void checkUtf8(PathFragment path) {
      String pathString = path.getPathString();
      if (CharMatcher.ascii().matchesAllOf(pathString)) {
        return;
      }
      if (!SUPPORTS_NON_ASCII_PATHS || !Utf8.isWellFormed(pathString.getBytes(ISO_8859_1))) {
        throw new IllegalArgumentException("unsupported path: " + path);
      }
    }
  }

  private DecompressorMain() {}
}
