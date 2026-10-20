// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/** Utility methods related to repo fetching. */
public class RepositoryUtils {

  public static final String WORKSPACE_SYMLINK_NAME = "_main";

  private RepositoryUtils() {}

  public static boolean isValidRepoRoot(Path directory) throws IOException {
    // Keep in sync with //src/main/cpp/workspace_layout.h
    return directory.getRelative(LabelConstants.WORKSPACE_DOT_BAZEL_FILE_NAME).exists()
        || directory.getRelative(LabelConstants.WORKSPACE_FILE_NAME).exists()
        || directory.getRelative(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME).exists()
        || directory.getRelative(LabelConstants.REPO_FILE_NAME).exists();
  }

  @Nullable
  public static RootedPath getRootedPathFromLabel(Label label, Environment env)
      throws InterruptedException, EvalException {
    SkyKey pkgSkyKey = PackageLookupValue.key(label.getPackageIdentifier());
    PackageLookupValue pkgLookupValue = (PackageLookupValue) env.getValue(pkgSkyKey);
    if (pkgLookupValue == null) {
      return null;
    }
    if (!pkgLookupValue.packageExists()) {
      String message = pkgLookupValue.getErrorMsg();
      if (pkgLookupValue == PackageLookupValue.NO_BUILD_FILE_VALUE) {
        message = PackageLookupFunction.explainNoBuildFileValue(label.getPackageIdentifier(), env);
      }
      throw Starlark.errorf("Unable to load package for %s: %s", label, message);
    }

    // And now for the file
    Root packageRoot = pkgLookupValue.getRoot();
    return RootedPath.toRootedPath(packageRoot, label.toPathFragment());
  }

  protected static Path getExternalRepositoryDirectory(BlazeDirectories directories) {
    return directories.getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
  }

  /**
   * The result of {@link #replantSymlinks}.
   *
   * @param safeForLocalCache whether the repo is safe to cache in the local repo contents cache
   *     after replanting
   * @param safeForRemoteCache whether the repo is safe to cache in the remote repo contents cache
   *     after replanting.
   */
  public record ReplantSymlinksResult(boolean safeForLocalCache, boolean safeForRemoteCache) {}

  /**
   * Replants the symlinks under the specified repository directory.
   *
   * <p>Re-writes symlinks that originally point to a path under the external root to relative
   * paths. Same-repo symlinks (those pointing back into {@code repoDir}) are replanted to
   * repo-relative paths that work regardless of the repo's physical location. Cross-repo symlinks
   * are replanted to relative paths going through {@code repoDirParentToExternalRoot}.
   *
   * @param repoDir The path to the repository directory.
   * @param workspace The path to the workspace directory.
   * @param externalRepoRoot The path to the root of external repositories.
   * @param repoDirParentToExternalRoot The relative path from the parent directory of {@code
   *     repoDir} to the external root (or a symlink to it), which is used to rewrite cross-repo
   *     symlink targets to relative paths.
   * @param replantSymlinksIntoMainRepo If true, symlinks pointing into the main repo are treated
   *     like cross-repo symlinks by routing them through a symlink to the workspace planted under
   *     the external root (used by vendoring, where the vendored repo has to keep working from a
   *     different checkout of the workspace). If false, such symlinks are left untouched.
   * @throws IOException If an I/O error occurs while replanting the symlinks.
   */
  @CanIgnoreReturnValue
  public static ReplantSymlinksResult replantSymlinks(
      Path repoDir,
      Path workspace,
      Path externalRepoRoot,
      PathFragment repoDirParentToExternalRoot,
      boolean replantSymlinksIntoMainRepo)
      throws IOException {
    boolean portableSymlinksOnly = true;
    // TODO(#30160): Repos with symlinks pointing out of the repo are currently excluded from the
    // remote repo contents cache since cross-FS resolution of symlinks proved tricky to get right.
    boolean symlinksResolveWithinRepo = true;
    try {
      Collection<Path> symlinks = FileSystemUtils.traverseTree(repoDir, Path::isSymbolicLink);
      Path workspaceSymlinkUnderExternal = externalRepoRoot.getChild(WORKSPACE_SYMLINK_NAME);
      if (replantSymlinksIntoMainRepo) {
        FileSystemUtils.ensureSymbolicLink(workspaceSymlinkUnderExternal, workspace);
      }
      for (Path symlink : symlinks) {
        PathFragment target = symlink.readSymbolicLink();
        PathFragment originalTarget = target;
        if (target.startsWith(workspace.asFragment())) {
          symlinksResolveWithinRepo = false;
          if (!replantSymlinksIntoMainRepo) {
            // Symlinks pointing into the main repo can't be replanted to a relative path that
            // stays under the external root. They make the repo unsafe to cache.
            portableSymlinksOnly = false;
            continue;
          }
          target =
              workspaceSymlinkUnderExternal
                  .asFragment()
                  .getRelative(target.relativeTo(workspace.asFragment()));
        }
        if (!target.isAbsolute()) {
          // A symlink that was created with a relative target by the repo rule. Resolve it
          // against the symlink's parent directory to determine whether it stays within the repo.
          if (!symlink.getParentDirectory().getRelative(target).startsWith(repoDir)) {
            portableSymlinksOnly = false;
            symlinksResolveWithinRepo = false;
          }
          continue;
        }
        if (!target.startsWith(externalRepoRoot.asFragment())) {
          // This symlink doesn't point into any Bazel repo, including the main repo, and thus its
          // target isn't managed by Bazel. We assume such symlinks are portable across machines
          // on which the repo is relevant (e.g. /lib/ld-linux.so* or /usr/bin/ld)
          symlinksResolveWithinRepo = false;
          continue;
        }
        PathFragment newTarget;
        if (target.startsWith(repoDir.asFragment())) {
          // Same-repo symlink: replant relative within the repo. This is always safe regardless
          // of where the repo is physically located.
          PathFragment targetRelativeToRepo = target.relativeTo(repoDir.asFragment());
          int depth = symlink.relativeTo(repoDir).segmentCount() - 1;
          newTarget = PathFragment.create("../".repeat(depth)).getRelative(targetRelativeToRepo);
        } else {
          // Cross-repo symlink: replant through the parent directory. On Unix, these relative paths
          // only resolve correctly when the repo is at its original location (under the external
          // root), not when moved to a shared cache. This is because symlink resolution is based
          // on physical paths. On Windows, where symlinks are resolved using logical paths, it may
          // be possible to use these symlinks portably, but this would likely require changes to
          // FileFunction to mimic this resolution behavior.
          portableSymlinksOnly = false;
          symlinksResolveWithinRepo = false;
          // Rewrite for consistency even if not portable. A mix of absolute and relative symlinks
          // would result in less predictable behavior and reduced test coverage.
          newTarget =
              PathFragment.create(
                      "../"
                          .repeat(
                              symlink.relativeTo(repoDir.getParentDirectory()).segmentCount() - 1))
                  .getRelative(repoDirParentToExternalRoot)
                  .getRelative(target.relativeTo(externalRepoRoot.asFragment()));
        }
        if (OS.getCurrent() == OS.WINDOWS) {
          // On Windows, FileSystemUtils.ensureSymbolicLink always resolves paths to absolute
          // paths. Use Files.createSymbolicLink here instead to preserve the relative target path.
          var symlinkNioPath = symlink.getFileSystem().getNioPath(symlink.asFragment());
          var newTargetNioPath = symlink.getFileSystem().getNioPath(newTarget);
          if (symlinkNioPath == null || newTargetNioPath == null) {
            portableSymlinksOnly = false;
            symlinksResolveWithinRepo = false;
            continue;
          }
          symlink.delete();
          try {
            Files.createSymbolicLink(symlinkNioPath, newTargetNioPath);
          } catch (IOException e) {
            // Creating a real symlink failed (likely no Developer Mode or symlink privilege).
            // Restore the original symlink/junction and mark as non-portable. The absolute target
            // also doesn't resolve within the repo when restored elsewhere.
            FileSystemUtils.ensureSymbolicLink(symlink, originalTarget);
            portableSymlinksOnly = false;
            symlinksResolveWithinRepo = false;
          }
        } else {
          FileSystemUtils.ensureSymbolicLink(symlink, newTarget);
        }
      }
    } catch (IOException e) {
      throw new IOException(
          String.format("Failed to rewrite symlinks under %s: %s", repoDir, e.getMessage()), e);
    }
    return new ReplantSymlinksResult(portableSymlinksOnly, symlinksResolveWithinRepo);
  }

  /**
   * Rewrites the symlinks under {@code repoDir} that point into a repo in the local repo contents
   * cache at {@code repoContentsCacheDir} to point into the directory of that repo under {@code
   * externalRepoRoot} instead, so that they keep resolving after the cached repo has been garbage
   * collected and fetched again.
   */
  public static void redirectSymlinksToCachedRepos(
      Path repoDir, Path externalRepoRoot, Path repoContentsCacheDir) throws IOException {
    if (repoDir.isSymbolicLink()) {
      // The repo is a directory that Bazel doesn't manage, e.g. that of a local_repository.
      return;
    }
    var symlinks = new ArrayList<Path>();
    collectSymlinks(repoDir, symlinks);
    if (symlinks.isEmpty()) {
      return;
    }
    // Resolving the path of a file in a cached repo, e.g. with repository_ctx.path(...).realpath,
    // results in a path below the resolved cache dir.
    var cacheDirs = new LinkedHashSet<PathFragment>();
    cacheDirs.add(repoContentsCacheDir.asFragment());
    try {
      cacheDirs.add(repoContentsCacheDir.resolveSymbolicLinks().asFragment());
    } catch (FileNotFoundException _) {
      // No repo has been cached yet.
      return;
    }
    Map<PathFragment, String> cachedRepos = null;
    for (Path symlink : symlinks) {
      PathFragment pathInCache = relativeToAny(symlink.readSymbolicLink(), cacheDirs);
      if (pathInCache == null || pathInCache.segmentCount() < 2) {
        continue;
      }
      if (cachedRepos == null) {
        cachedRepos = collectCachedRepos(externalRepoRoot, cacheDirs);
      }
      String repoName = cachedRepos.get(pathInCache.subFragment(0, 2));
      if (repoName != null) {
        FileSystemUtils.ensureSymbolicLink(
            symlink,
            externalRepoRoot
                .getChild(repoName)
                .getRelative(pathInCache.subFragment(2))
                .asFragment());
      }
    }
  }

  private static void collectSymlinks(Path dir, List<Path> symlinks) throws IOException {
    for (Dirent dirent : dir.readdir(Symlinks.NOFOLLOW)) {
      Path child = dir.getChild(dirent.getName());
      switch (dirent.getType()) {
        case SYMLINK -> symlinks.add(child);
        case DIRECTORY -> collectSymlinks(child, symlinks);
        default -> {}
      }
    }
  }

  /**
   * Returns the names of the repos under {@code externalRepoRoot} that are symlinks to a cached
   * repo, keyed by the path of the cached repo relative to the cache dir.
   */
  private static Map<PathFragment, String> collectCachedRepos(
      Path externalRepoRoot, Collection<PathFragment> cacheDirs) throws IOException {
    var cachedRepos = new HashMap<PathFragment, String>();
    for (Dirent dirent : externalRepoRoot.readdir(Symlinks.NOFOLLOW)) {
      if (dirent.getType() != Dirent.Type.SYMLINK) {
        continue;
      }
      PathFragment target;
      try {
        target = externalRepoRoot.getChild(dirent.getName()).readSymbolicLink();
      } catch (IOException _) {
        // The repo is being fetched concurrently.
        continue;
      }
      PathFragment pathInCache = relativeToAny(target, cacheDirs);
      if (pathInCache != null && pathInCache.segmentCount() == 2) {
        cachedRepos.put(pathInCache, dirent.getName());
      }
    }
    return cachedRepos;
  }

  @Nullable
  private static PathFragment relativeToAny(PathFragment path, Collection<PathFragment> dirs) {
    for (PathFragment dir : dirs) {
      if (path.startsWith(dir)) {
        return path.relativeTo(dir);
      }
    }
    return null;
  }
}
