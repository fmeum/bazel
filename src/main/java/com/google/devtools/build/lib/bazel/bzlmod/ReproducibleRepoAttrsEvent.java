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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import java.util.Optional;

/**
 * Posted after a repo has been fetched from its original definition to update the reproducible
 * attributes recorded for it in the lockfile.
 *
 * @param repoName The canonical name of the repo.
 * @param reproducibleRepoAttrs The attributes reported by the repo rule to make the repo
 *     reproducible, or empty if it didn't report any (in which case any previously recorded
 *     attributes are stale and should be removed).
 */
public record ReproducibleRepoAttrsEvent(
    RepositoryName repoName, Optional<ReproducibleRepoAttrs> reproducibleRepoAttrs)
    implements Postable {}
