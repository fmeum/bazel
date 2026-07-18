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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Maintains a symlink farm rooted at the fixed configuration segment of the output directory (e.g.
 * {@code bazel-out/cfg}) that makes exec paths mapped by {@link StrippingPathMapper} resolvable on
 * the local filesystem.
 *
 * <p>Path-mapped actions see and thus may embed mapped exec paths in their outputs, for example as
 * object file paths in macOS debug info or as source paths of generated files in DWARF. Since
 * outputs are materialized under their configuration-specific paths, such embedded paths don't
 * resolve on the local filesystem. This farm bridges the gap by maintaining symlinks from mapped
 * paths to the corresponding configuration-specific paths.
 *
 * <p>The farm starts out as a single symlink pointing to the configuration directory of the first
 * output reported to it. When an output is reported whose mapped path would currently resolve to a
 * file with different contents (or to no file at all), the symlink covering it is forked into a
 * real directory containing one symlink per entry of the directory it pointed to, repeatedly, until
 * the diverging path is covered by its own symlink. Which configuration directory a symlink points
 * to is thus irrelevant as long as all mapped paths under it resolve to files with the contents
 * their producing actions reported - in the common case of a single (relevant) configuration, the
 * farm remains a single symlink and reporting an output costs no I/O at all.
 *
 * <p>A mapped file path produced with different contents by two configurations cannot be resolved
 * correctly for both. Such paths are tombstoned with a dangling sentinel symlink so that they
 * resolve to nothing rather than to the wrong contents, including in future builds that only
 * re-execute one of the producing actions.
 *
 * <p>The farm's in-memory state mirrors exactly the symlinks it planted and is reconstructed from
 * disk on the first reported output, so its retained memory footprint is proportional to the amount
 * of cross-configuration divergence, not to the number of outputs in the build.
 */
public final class PathMappingSymlinkFarm {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * Target of the dangling symlink planted at a mapped path that has been produced with different
   * contents by multiple configurations. The target never exists, so the mapped path resolves to
   * nothing, and reloading the farm from disk recognizes the path as permanently unresolvable.
   */
  @VisibleForTesting
  static final PathFragment CONFLICT_SENTINEL = PathFragment.create(".path-mapping-conflict");

  private final Path execRoot;
  private final boolean enabled;

  // Initialized from the first reported output, whose first exec path segment names the output
  // directory (e.g. "bazel-out").
  @Nullable private PathFragment outputDirName;
  @Nullable private Path outputDir;
  @Nullable private Path farmRoot;

  // State of the farm root, mirroring the symlinks planted on disk. Null means the farm root
  // doesn't exist.
  @Nullable private Node root;

  private boolean broken = false;

  private sealed interface Node permits SymlinkNode, DirNode, ConflictNode {}

  /** A symlink to {@code target}, which is a path relative to {@link #outputDir}. */
  private record SymlinkNode(PathFragment target) implements Node {}

  private static final class DirNode implements Node {
    private final Map<String, Node> children = new HashMap<>();
  }

  private enum ConflictNode implements Node {
    INSTANCE
  }

  /**
   * Creates a farm for the output directory under the given exec root.
   *
   * @param enabled whether the farm should be maintained; if false, an existing farm is deleted
   *     instead so that it can't go stale and resolve mapped paths to the wrong contents
   */
  public PathMappingSymlinkFarm(Path execRoot, boolean enabled) {
    this.execRoot = execRoot;
    this.enabled = enabled;
  }

  /**
   * Reports an output of a successfully executed (or cached) action together with its content
   * digest and updates the farm so that the output's mapped exec path either resolves to contents
   * with that digest or to nothing.
   *
   * <p>A null digest is treated as unverifiable: the output's mapped path is never left resolving
   * to a file from a different configuration.
   */
  public synchronized void addOutput(PathFragment execPath, @Nullable byte[] digest, long size) {
    if (broken || execPath.segmentCount() < 3) {
      return;
    }
    if (!enabled) {
      deleteStaleFarm(execPath);
      return;
    }
    try {
      if (outputDirName == null) {
        outputDirName = execPath.subFragment(0, 1);
        outputDir = execRoot.getRelative(outputDirName);
        farmRoot = outputDir.getRelative(StrippingPathMapper.FIXED_CONFIG_SEGMENT);
        root = load(farmRoot, /* depth= */ 1);
      } else if (!execPath.startsWith(outputDirName)) {
        return;
      }
      String config = execPath.getSegment(1);
      if (config.equals(StrippingPathMapper.FIXED_CONFIG_SEGMENT) || config.startsWith(":")) {
        return;
      }
      insert(PathFragment.create(config), execPath.subFragment(2), digest, size);
    } catch (IOException e) {
      abandon(e);
    }
  }

  private void insert(
      PathFragment configDir, PathFragment suffix, @Nullable byte[] digest, long size)
      throws IOException {
    if (root == null) {
      root = plantSymlink(PathFragment.EMPTY_FRAGMENT, configDir);
      return;
    }
    Node node = root;
    DirNode parent = null;
    // Number of leading segments of suffix covered by the position of node in the farm.
    int consumed = 0;
    while (true) {
      switch (node) {
        case ConflictNode unused -> {
          return;
        }
        case SymlinkNode symlink -> {
          if (symlink.target().equals(configDir.getRelative(suffix.subFragment(0, consumed)))) {
            // The symlink points into this output's own configuration directory.
            return;
          }
          PathFragment remaining = suffix.subFragment(consumed);
          if (isConsistent(
              outputDir.getRelative(symlink.target()).getRelative(remaining), digest, size)) {
            return;
          }
          if (remaining.isEmpty()) {
            node = plantConflict(suffix);
            setNode(parent, suffix, consumed, node);
            return;
          }
          DirNode forked = fork(symlink, suffix.subFragment(0, consumed));
          setNode(parent, suffix, consumed, forked);
          node = forked;
        }
        case DirNode dir -> {
          if (consumed == suffix.segmentCount()) {
            // The mapped path of this output file is a directory in the farm, which can only
            // happen if another configuration nests outputs below a path at which this
            // configuration has a file. Keep the directory, which resolves more paths.
            return;
          }
          String name = suffix.getSegment(consumed);
          Node child = dir.children.get(name);
          consumed++;
          if (child == null) {
            dir.children.put(
                name,
                plantSymlink(
                    suffix.subFragment(0, consumed),
                    configDir.getRelative(suffix.subFragment(0, consumed))));
            return;
          }
          parent = dir;
          node = child;
        }
      }
    }
  }

  private void setNode(@Nullable DirNode parent, PathFragment suffix, int consumed, Node node) {
    if (parent == null) {
      root = node;
    } else {
      parent.children.put(suffix.getSegment(consumed - 1), node);
    }
  }

  /**
   * Returns whether the file at the given path exists and has the given digest, i.e., whether a
   * mapped path resolving to it resolves to the correct contents.
   */
  private static boolean isConsistent(Path resolved, @Nullable byte[] digest, long size) {
    if (digest == null) {
      return false;
    }
    try {
      FileStatus status = resolved.statIfFound(Symlinks.FOLLOW);
      if (status == null || !status.isFile() || status.getSize() != size) {
        return false;
      }
      return Arrays.equals(resolved.getDigest(), digest);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Replaces the symlink at the mapped directory path {@code linkSuffix} with a real directory
   * containing one symlink per current entry of the directory the symlink pointed to, thus
   * preserving the resolution of all paths under it.
   */
  private DirNode fork(SymlinkNode symlink, PathFragment linkSuffix) throws IOException {
    Path link = farmRoot.getRelative(linkSuffix);
    link.delete();
    link.createDirectory();
    DirNode dir = new DirNode();
    Iterable<Dirent> entries;
    try {
      entries = outputDir.getRelative(symlink.target()).readdir(Symlinks.NOFOLLOW);
    } catch (IOException e) {
      // The target doesn't exist (e.g. it wasn't downloaded by a build without the bytes) or isn't
      // a directory. Its paths didn't resolve before, so there is nothing to preserve.
      return dir;
    }
    for (Dirent entry : entries) {
      String name = entry.getName();
      dir.children.put(
          name, plantSymlink(linkSuffix.getRelative(name), symlink.target().getRelative(name)));
    }
    return dir;
  }

  private SymlinkNode plantSymlink(PathFragment linkSuffix, PathFragment target)
      throws IOException {
    FileSystemUtils.ensureSymbolicLink(
        farmRoot.getRelative(linkSuffix),
        PathFragment.create("../".repeat(linkSuffix.segmentCount()) + target.getPathString()));
    return new SymlinkNode(target);
  }

  private ConflictNode plantConflict(PathFragment linkSuffix) throws IOException {
    Path link = farmRoot.getRelative(linkSuffix);
    link.delete();
    link.createSymbolicLink(CONFLICT_SENTINEL);
    return ConflictNode.INSTANCE;
  }

  /**
   * Reconstructs the in-memory state of the farm from disk, deleting any entries that the farm
   * could not have created itself.
   */
  @Nullable
  private Node load(Path path, int depth) throws IOException {
    FileStatus status = path.statIfFound(Symlinks.NOFOLLOW);
    if (status == null) {
      return null;
    }
    if (status.isSymbolicLink()) {
      Node node = parseLinkTarget(path.readSymbolicLink(), depth);
      if (node == null) {
        path.delete();
      }
      return node;
    }
    if (status.isDirectory()) {
      DirNode dir = new DirNode();
      for (Dirent entry : path.readdir(Symlinks.NOFOLLOW)) {
        Node child = load(path.getChild(entry.getName()), depth + 1);
        if (child != null) {
          dir.children.put(entry.getName(), child);
        }
      }
      return dir;
    }
    path.deleteTree();
    return null;
  }

  @Nullable
  private static Node parseLinkTarget(PathFragment target, int depth) {
    if (target.equals(CONFLICT_SENTINEL)) {
      return ConflictNode.INSTANCE;
    }
    if (target.isAbsolute()) {
      return null;
    }
    int ups = 0;
    while (ups < target.segmentCount() && target.getSegment(ups).equals("..")) {
      ups++;
    }
    // A link at depth N below the output directory points back up to it with N - 1 up-level
    // references, followed by a path into a sibling of the farm root.
    if (ups != depth - 1 || ups == target.segmentCount()) {
      return null;
    }
    PathFragment resolved = target.subFragment(ups);
    if (resolved.getSegment(0).equals(StrippingPathMapper.FIXED_CONFIG_SEGMENT)) {
      return null;
    }
    return new SymlinkNode(resolved);
  }

  /**
   * Deletes a farm left behind by a previous build now that it is no longer maintained and could
   * otherwise resolve mapped paths produced by this build to the wrong contents.
   */
  private void deleteStaleFarm(PathFragment execPath) {
    broken = true;
    Path staleFarmRoot =
        execRoot
            .getRelative(execPath.subFragment(0, 1))
            .getRelative(StrippingPathMapper.FIXED_CONFIG_SEGMENT);
    try {
      staleFarmRoot.deleteTree();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to delete stale path mapping symlink farm at %s", staleFarmRoot);
    }
  }

  /**
   * Stops maintaining the farm and deletes it: a farm that can no longer be updated may resolve
   * mapped paths produced later in the build to the wrong contents.
   */
  private void abandon(IOException cause) {
    broken = true;
    logger.atWarning().withCause(cause).log(
        "Failed to update path mapping symlink farm at %s, deleting it", farmRoot);
    root = null;
    try {
      farmRoot.deleteTree();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to delete path mapping symlink farm at %s; mapped paths may resolve to outputs"
              + " of previous builds",
          farmRoot);
    }
  }
}
