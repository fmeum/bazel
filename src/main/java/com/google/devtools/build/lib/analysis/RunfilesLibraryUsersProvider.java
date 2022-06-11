// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import com.google.common.base.Objects;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

@Immutable
public final class RunfilesLibraryUsersProvider implements TransitiveInfoProvider {

  public static final class RepositoryNameAndMapping {
    private final RepositoryName repositoryName;
    private final RepositoryMapping repositoryMapping;

    public RepositoryNameAndMapping(RepositoryName repositoryName,
        RepositoryMapping repositoryMapping) {
      this.repositoryName = repositoryName;
      this.repositoryMapping = repositoryMapping;
    }

    public RepositoryName getRepositoryName() {
      return repositoryName;
    }

    public RepositoryMapping getRepositoryMapping() {
      return repositoryMapping;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RepositoryNameAndMapping that = (RepositoryNameAndMapping) o;
      return Objects.equal(repositoryName, that.repositoryName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(repositoryName);
    }
  }

  public static final RunfilesLibraryUsersProvider EMPTY =
      new RunfilesLibraryUsersProvider(NestedSetBuilder.emptySet(Order.COMPILE_ORDER));

  private final NestedSet<RepositoryNameAndMapping> users;

  RunfilesLibraryUsersProvider(NestedSet<RepositoryNameAndMapping> users) {
    this.users = users;
  }

  NestedSet<RepositoryNameAndMapping> getUsers() {
    return users;
  }
}
