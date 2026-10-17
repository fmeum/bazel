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

package com.google.devtools.build.lib.runtime;

import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.Map;

/** A remote cache for the contents of external repositories. */
public interface RemoteRepoContentsCache {
  /** Adds a repository that has been fetched locally to the remote cache. */
  void addToCache(
      RepositoryName repoName,
      Path fetchedRepoDir,
      Path fetchedRepoMarkerFile,
      String predeclaredInputHash,
      ExtendedEventHandler reporter)
      throws InterruptedException;

  /**
   * Retrieves a repository from the remote cache if possible.
   *
   * <p>Callers have to check {@code env.valuesMissing()} after this method returns.
   *
   * @return true if there was a cache hit and the repository has been fetched into the given
   *     directory.
   */
  boolean lookupCache(
      RepositoryName repoName,
      Path repoDir,
      String predeclaredInputHash,
      SkyFunction.Environment env)
      throws IOException, InterruptedException;

  /**
   * Whether repository rules fetching into the given directory may download files lazily, that is,
   * only make them available in the remote cache.
   */
  boolean allowsLazyDownloads(Path repoDir);

  /**
   * Turns a repository that has just been fetched into one backed by the remote cache, in which the
   * given files are only available remotely.
   *
   * <p>The other files of the repository are uploaded to the remote cache, but no action cache
   * entries are written.
   *
   * @param markerFileContent the content of the repository's marker file, which must not have been
   *     written yet
   * @param remoteFiles empty placeholder files in the repository, keyed by their path relative to
   *     the repository directory, and the metadata of their actual contents in the remote cache
   * @return true if the repository is now backed by the remote cache; false if it has been left
   *     untouched, in which case the placeholders have to be replaced with the actual contents
   * @throws IOException if the repository could neither be backed by the remote cache nor be left
   *     untouched
   */
  boolean injectRepoWithRemoteFiles(
      RepositoryName repoName,
      Path repoDir,
      String markerFileContent,
      Map<PathFragment, FileArtifactValue> remoteFiles,
      ExtendedEventHandler reporter)
      throws IOException, InterruptedException;
}
