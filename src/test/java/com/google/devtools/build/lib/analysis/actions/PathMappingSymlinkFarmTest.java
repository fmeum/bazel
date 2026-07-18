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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PathMappingSymlinkFarm}. */
@RunWith(JUnit4.class)
public final class PathMappingSymlinkFarmTest {

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fs.getPath("/execroot/_main");
  private final Path outputDir = execRoot.getRelative("bazel-out");

  private PathMappingSymlinkFarm farm;

  @Before
  public void setUp() throws Exception {
    outputDir.createDirectoryAndParents();
    farm = new PathMappingSymlinkFarm(execRoot, /* enabled= */ true);
  }

  private void writeFile(String execPath, String content) throws IOException {
    Path path = execRoot.getRelative(execPath);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, UTF_8, content);
  }

  private void writeAndReportOutput(String execPath, String content) throws IOException {
    writeFile(execPath, content);
    reportOutput(execPath);
  }

  private void reportOutput(String execPath) throws IOException {
    Path path = execRoot.getRelative(execPath);
    farm.addOutput(PathFragment.create(execPath), path.getDigest(), path.getFileSize());
  }

  private void reportOutputWithoutDigest(String execPath) {
    farm.addOutput(PathFragment.create(execPath), /* digest= */ null, /* size= */ 0);
  }

  private String resolve(String mappedExecPath) throws IOException {
    return new String(
        FileSystemUtils.readContent(execRoot.getRelative(mappedExecPath)), UTF_8);
  }

  private void assertUnresolvable(String mappedExecPath) {
    assertThrows(FileNotFoundException.class, () -> resolve(mappedExecPath));
  }

  private void assertIsSymlinkTo(String execPath, @Nullable String target) throws IOException {
    Path path = execRoot.getRelative(execPath);
    assertThat(path.isSymbolicLink()).isTrue();
    if (target != null) {
      assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create(target));
    }
  }

  @Test
  public void singleConfiguration_plantsOneSymlink() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/a.o", "a");
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/b.o", "b");
    writeAndReportOutput("bazel-out/k8-fastbuild/testlogs/pkg/test.log", "log");

    assertIsSymlinkTo("bazel-out/cfg", "k8-fastbuild");
    assertThat(resolve("bazel-out/cfg/bin/pkg/a.o")).isEqualTo("a");
    assertThat(resolve("bazel-out/cfg/bin/pkg/b.o")).isEqualTo("b");
    assertThat(resolve("bazel-out/cfg/testlogs/pkg/test.log")).isEqualTo("log");
  }

  @Test
  public void identicalContentsAcrossConfigurations_keepsSingleSymlink() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/gen.h", "gen");
    writeAndReportOutput("bazel-out/k8-opt-exec-1234/bin/pkg/gen.h", "gen");

    assertIsSymlinkTo("bazel-out/cfg", "k8-fastbuild");
    assertThat(resolve("bazel-out/cfg/bin/pkg/gen.h")).isEqualTo("gen");
  }

  @Test
  public void divergingFile_forksAndTombstones() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/x.o", "fastbuild");
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/keep.o", "keep");
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/other.txt", "other");
    writeAndReportOutput("bazel-out/k8-opt/bin/pkg/x.o", "opt");

    assertThat(outputDir.getRelative("cfg").isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outputDir.getRelative("cfg/bin").isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outputDir.getRelative("cfg/bin/pkg").isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertIsSymlinkTo(
        "bazel-out/cfg/bin/pkg/x.o", PathMappingSymlinkFarm.CONFLICT_SENTINEL.getPathString());
    assertUnresolvable("bazel-out/cfg/bin/pkg/x.o");
    assertThat(resolve("bazel-out/cfg/bin/pkg/keep.o")).isEqualTo("keep");
    assertIsSymlinkTo("bazel-out/cfg/bin/other.txt", "../../k8-fastbuild/bin/other.txt");
    assertThat(resolve("bazel-out/cfg/bin/other.txt")).isEqualTo("other");
  }

  @Test
  public void newSubtreeFromOtherConfiguration_plantsSubtreeSymlink() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/a.o", "a");
    writeAndReportOutput("bazel-out/k8-opt/bin2/pkg/z.o", "z");

    assertIsSymlinkTo("bazel-out/cfg/bin", "../k8-fastbuild/bin");
    assertIsSymlinkTo("bazel-out/cfg/bin2", "../k8-opt/bin2");
    assertThat(resolve("bazel-out/cfg/bin/pkg/a.o")).isEqualTo("a");
    assertThat(resolve("bazel-out/cfg/bin2/pkg/z.o")).isEqualTo("z");

    // Further outputs under the new subtree are covered by the existing symlink.
    writeAndReportOutput("bazel-out/k8-opt/bin2/pkg/w.o", "w");
    assertIsSymlinkTo("bazel-out/cfg/bin2", "../k8-opt/bin2");
    assertThat(resolve("bazel-out/cfg/bin2/pkg/w.o")).isEqualTo("w");
  }

  @Test
  public void unverifiableOutput_neverResolvesToOtherConfiguration() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/x.o", "contents");
    writeFile("bazel-out/k8-opt/bin/pkg/x.o", "contents");
    reportOutputWithoutDigest("bazel-out/k8-opt/bin/pkg/x.o");

    assertUnresolvable("bazel-out/cfg/bin/pkg/x.o");
  }

  @Test
  public void unreportedFilesInForkedDirectoryRemainResolvable() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/x.o", "fastbuild");
    writeFile("bazel-out/k8-fastbuild/bin/stale.txt", "stale");
    writeAndReportOutput("bazel-out/k8-opt/bin/pkg/x.o", "opt");

    assertThat(resolve("bazel-out/cfg/bin/stale.txt")).isEqualTo("stale");
  }

  @Test
  public void reloadFromDisk_preservesState() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/x.o", "fastbuild");
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/keep.o", "keep");
    writeAndReportOutput("bazel-out/k8-opt/bin/pkg/x.o", "opt");

    farm = new PathMappingSymlinkFarm(execRoot, /* enabled= */ true);
    // A conflict is remembered across reloads even if only a single configuration produces the
    // path in the current build.
    writeAndReportOutput("bazel-out/k8-dbg/bin/pkg/x.o", "dbg");
    assertUnresolvable("bazel-out/cfg/bin/pkg/x.o");

    // Consistent outputs leave the reloaded farm unchanged.
    reportOutput("bazel-out/k8-fastbuild/bin/pkg/keep.o");
    assertThat(resolve("bazel-out/cfg/bin/pkg/keep.o")).isEqualTo("keep");
    assertIsSymlinkTo("bazel-out/cfg/bin/pkg/keep.o", "../../../k8-fastbuild/bin/pkg/keep.o");
  }

  @Test
  public void reloadFromDisk_deletesForeignEntries() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/x.o", "fastbuild");
    writeAndReportOutput("bazel-out/k8-opt/bin/pkg/x.o", "opt");
    writeFile("bazel-out/cfg/bin/foreign.txt", "foreign");
    outputDir.getRelative("cfg/bin/absolute").createSymbolicLink(PathFragment.create("/etc"));

    farm = new PathMappingSymlinkFarm(execRoot, /* enabled= */ true);
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/y.o", "y");

    assertThat(outputDir.getRelative("cfg/bin/foreign.txt").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(outputDir.getRelative("cfg/bin/absolute").exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(resolve("bazel-out/cfg/bin/pkg/y.o")).isEqualTo("y");
  }

  @Test
  public void disabledFarm_deletesExistingFarm() throws Exception {
    writeAndReportOutput("bazel-out/k8-fastbuild/bin/pkg/a.o", "a");
    assertThat(outputDir.getRelative("cfg").exists(Symlinks.NOFOLLOW)).isTrue();

    farm = new PathMappingSymlinkFarm(execRoot, /* enabled= */ false);
    reportOutput("bazel-out/k8-fastbuild/bin/pkg/a.o");

    assertThat(outputDir.getRelative("cfg").exists(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void outputsNotUnderConfigurationDirectory_ignored() throws Exception {
    writeFile("bazel-out/stable-status.txt", "status");
    reportOutput("bazel-out/stable-status.txt");

    assertThat(outputDir.getRelative("cfg").exists(Symlinks.NOFOLLOW)).isFalse();
  }
}
