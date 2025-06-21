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
package com.google.devtools.build.lib.vfs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Event;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An immutable set of modified source files. The scope of these files is context-dependent; in some
 * uses this may mean information about all files in the client, while in other uses this may mean
 * information about some specific subset of files. {@link #EVERYTHING_MODIFIED} can be used to
 * indicate that all files of interest have been modified.
 */
public class ModifiedFileSet {

  /**
   * Allows issuing instructions to clean up the client. This could be in order to revert the client
   * back to its baseline (hypothetical use case) or a real use case at the time of writing this
   * comment which is to clean up Skycache violations of files modified outside of the project's
   * frontier compared to the baseline.
   */
  @Nullable
  public String getInstructionsMessage(Set<String> modified) {
    return null;
  }

  @Nullable
  public String getInstructionsPrelude(Set<String> modified) {
    return null;
  }

  /**
   * Returns events related to this modified file set, for example, files whose state violates some
   * condition or require a warning.
   */
  public List<Event> getMessages() {
    return ImmutableList.of();
  }

  // When everything is modified that naturally includes all directories.
  public static final ModifiedFileSet EVERYTHING_MODIFIED = new ModifiedFileSet(null);

  /**
   * Special case of {@link #EVERYTHING_MODIFIED}, which indicates that the entire tree has been
   * deleted.
   */
  public static final ModifiedFileSet EVERYTHING_DELETED =
      new ModifiedFileSet(null) {
        @Override
        public boolean treatEverythingAsDeleted() {
          return true;
        }
      };

  public static final ModifiedFileSet NOTHING_MODIFIED = new ModifiedFileSet(ImmutableSet.of());

  @Nullable private final ImmutableSet<PathFragment> modified;

  /**
   * Whether all files of interest should be treated as potentially modified.
   */
  public boolean treatEverythingAsModified() {
    return modified == null;
  }

  /**
   * Returns whether the diff indicates the whole tree has been deleted.
   *
   * <p>This precludes any optimizations like skipping invalidation when we do not check modified
   * outputs.
   */
  public boolean treatEverythingAsDeleted() {
    return false;
  }

  /**
   * The set of files of interest that were modified.
   *
   * @throws IllegalStateException if {@link #treatEverythingAsModified} returns true.
   */
  public ImmutableSet<PathFragment> modifiedSourceFiles() {
    if (treatEverythingAsModified()) {
      throw new IllegalStateException();
    }
    return modified;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ModifiedFileSet other)) {
      return false;
    }
    return treatEverythingAsModified() == other.treatEverythingAsModified()
        && treatEverythingAsDeleted() == other.treatEverythingAsDeleted()
        && Objects.equals(modified, other.modified);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hashCode(modified) + Boolean.hashCode(treatEverythingAsDeleted());
  }

  @Override
  public String toString() {
    if (this.equals(EVERYTHING_DELETED)) {
      return "EVERYTHING_DELETED";
    } else if (this.equals(EVERYTHING_MODIFIED)) {
      return "EVERYTHING_MODIFIED";
    } else if (this.equals(NOTHING_MODIFIED)) {
      return "NOTHING_MODIFIED";
    } else {
      return modified.toString();
    }
  }

  protected ModifiedFileSet(ImmutableSet<PathFragment> modified) {
    this.modified = modified;
  }

  /** The builder for {@link ModifiedFileSet}. */
  public static class Builder {
    private final ImmutableSet.Builder<PathFragment> setBuilder = ImmutableSet.builder();

    public ModifiedFileSet build() {
      ImmutableSet<PathFragment> modified = setBuilder.build();
      return modified.isEmpty() ? NOTHING_MODIFIED : new ModifiedFileSet(modified);
    }

    @CanIgnoreReturnValue
    public Builder modify(PathFragment pathFragment) {
      setBuilder.add(pathFragment);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder modifyAll(Iterable<PathFragment> pathFragments) {
      setBuilder.addAll(pathFragments);
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
