// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.PrintStream;
import javax.annotation.Nullable;

/**
 * An immutable record of the state of Skyfocus. This is recorded as a member in {@link
 * SkyframeExecutor}.
 *
 * @param enabled If true, Skyfocus may run at the end of the build, depending on the state of the
 *     graph and active directories conditions.
 * @param forcedRerun If true, Skyfocus will always run at the end of the build, regardless of the
 *     state of active directories or the graph.
 * @param focusedTargetLabels The set of targets focused in this server instance
 * @param activeDirectories Files/dirs representing the active directories. Can be empty, specified
 *     by the command line flag, or automatically derived. Although the active directories is
 *     represented as {@link FileStateKey}, the presence of a directory path's {@code FileStateKey}
 *     is sufficient to represent the corresponding directory listing state node.
 * @param frontierSet {@link SkyKey}s for nodes that are in the DIRECT deps of the UTC of the active
 *     directories. The values of these nodes are sufficient to build the active directories.
 * @param verificationSet The set of files/dirs that are not in the active directories, but is in
 *     the transitive closure of focusedTargetLabels.
 * @param options The latest instance of {@link SkyfocusOptions}.
 * @param buildConfiguration The latest top level build configuration.
 */
public record SkyfocusState(
    boolean enabled,
    boolean forcedRerun,
    ImmutableSet<Label> focusedTargetLabels,
    ActiveDirectoriesType activeDirectoriesType,
    ImmutableSet<FileStateKey> activeDirectories,
    ImmutableSet<SkyKey> frontierSet,
    ImmutableSet<SkyKey> verificationSet,
    @Nullable SkyfocusOptions options,
    @Nullable BuildConfigurationValue buildConfiguration) {

  public void dumpActiveDirectories(PrintStream out) {
    activeDirectories.forEach(key -> out.println(key.getCanonicalName()));
  }

  public void dumpFrontierSet(PrintStream out) {
    frontierSet.forEach(key -> out.println(key.getCanonicalName()));
  }

  /**
   * Builder for the {@code SkyfocusState} record.
   *
   * <p>This must reflect all parameters in the record constructor.
   */
  @AutoBuilder
  public interface Builder {
    Builder enabled(boolean enable);

    Builder forcedRerun(boolean forcedRerun);

    Builder focusedTargetLabels(ImmutableSet<Label> focusedTargetLabels);

    Builder activeDirectoriesType(ActiveDirectoriesType activeDirectoriesType);

    Builder activeDirectories(ImmutableSet<FileStateKey> activeDirectories);

    Builder frontierSet(ImmutableSet<SkyKey> frontierSet);

    Builder verificationSet(ImmutableSet<SkyKey> verificationSet);

    Builder options(@Nullable SkyfocusOptions options);

    Builder buildConfiguration(@Nullable BuildConfigurationValue buildConfiguration);

    SkyfocusState build();
  }

  public Builder toBuilder() {
    return new AutoBuilder_SkyfocusState_Builder(this);
  }

  /** Describes how the active directories was constructed. */
  public enum ActiveDirectoriesType {
    /** Automatically derived by the source state and the command line (e.g. focused targets) */
    DERIVED,

    /** The value of --experimental_active_directories. Will override derived sets if used. */
    USER_DEFINED
  }

  /** The canonical state to completely disable Skyfocus in the build. */
  public static final SkyfocusState DISABLED =
      new SkyfocusState(
          false,
          false,
          /* focusedTargetLabels= */ ImmutableSet.of(),
          ActiveDirectoriesType.DERIVED,
          /* activeDirectories= */ ImmutableSet.of(),
          /* frontierSet= */ ImmutableSet.of(),
          /* verificationSet= */ ImmutableSet.of(),
          null,
          null);

  public ImmutableSet<String> activeDirectoriesStrings() {
    return activeDirectories.stream()
        .map(fsk -> fsk.argument().getRootRelativePath().toString())
        .collect(toImmutableSet());
  }

}
