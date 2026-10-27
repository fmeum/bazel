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
package com.google.devtools.build.lib.repository;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;

/**
 * Event posted when a repository was fetched successfully and its repository rule called
 * expensive methods such as {@code repository_ctx.download} or {@code repository_ctx.execute}.
 *
 * <p>This event is not posted if the repository contents were reused from a previous fetch or a
 * cache.
 *
 * @param repo the fetched repository
 * @param expensiveCalls the names of the expensive {@code repository_ctx} methods called
 */
public record ExpensiveRepositoryFetchEvent(
    RepositoryName repo, ImmutableSet<String> expensiveCalls) implements Postable {}
