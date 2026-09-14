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
package com.google.devtools.build.lib.packages;

import com.google.common.collect.Interner;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSetsShouldBeInternedByEquality;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.vfs.Root;

/**
 * The per-repository information that is tracked transitively through analysis.
 *
 * <p>This is all that consumers of the transitive package closure of a configured target actually
 * need: the repository mapping of each repository (for the repo mapping manifest of executables)
 * and the source root of each external repository (for planting symlinks into the execroot with
 * Skymeld). Tracking this instead of {@link Package.Metadata} keeps the nested sets small, since
 * the number of repositories in a build is typically orders of magnitude smaller than the number
 * of packages.
 *
 * <p>Instances are interned so that all packages of a repository share a single instance, and
 * nested sets of this type are interned by equality, which lets {@link
 * com.google.devtools.build.lib.analysis.TransitiveDependencyState} reuse nested sets across
 * configured targets.
 *
 * @param repository the repository
 * @param repositoryMapping the repository mapping of the repository
 * @param sourceRoot the source root of the repository; only meaningful for external repositories,
 *     since the main repository may have several source roots with {@code --package_path}
 */
public record RepositoryMetadata(
    RepositoryName repository, RepositoryMapping repositoryMapping, Root sourceRoot)
    implements NestedSetsShouldBeInternedByEquality {

  private static final Interner<RepositoryMetadata> INTERNER = BlazeInterners.newWeakInterner();

  /** Returns the interned {@link RepositoryMetadata} for the repository of the given package. */
  public static RepositoryMetadata forPackage(Package.Metadata packageMetadata) {
    return INTERNER.intern(
        new RepositoryMetadata(
            packageMetadata.packageIdentifier().getRepository(),
            packageMetadata.repositoryMapping(),
            packageMetadata.sourceRoot()));
  }
}
