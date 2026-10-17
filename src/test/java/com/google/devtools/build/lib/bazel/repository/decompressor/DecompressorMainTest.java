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

package com.google.devtools.build.lib.bazel.repository.decompressor;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DecompressorMain}. */
@RunWith(JUnit4.class)
public final class DecompressorMainTest {

  private Path tmp;
  private Path archive;

  @Before
  public void createArchive() throws IOException {
    tmp = Files.createTempDirectory(Paths.get(System.getenv("TEST_TMPDIR")), "decompressor");
    archive = tmp.resolve("archive.tar.gz");
    try (var out =
        new TarArchiveOutputStream(
            new GzipCompressorOutputStream(Files.newOutputStream(archive)))) {
      byte[] content = "content".getBytes(UTF_8);
      var file = new TarArchiveEntry("pkg/dir/file.txt");
      file.setSize(content.length);
      out.putArchiveEntry(file);
      out.write(content);
      out.closeArchiveEntry();
      var relativeSymlink = new TarArchiveEntry("pkg/rel_link", TarArchiveEntry.LF_SYMLINK);
      relativeSymlink.setLinkName("dir/file.txt");
      out.putArchiveEntry(relativeSymlink);
      out.closeArchiveEntry();
      var absoluteSymlink = new TarArchiveEntry("pkg/dir/abs_link", TarArchiveEntry.LF_SYMLINK);
      absoluteSymlink.setLinkName("/pkg/dir/file.txt");
      out.putArchiveEntry(absoluteSymlink);
      out.closeArchiveEntry();
    }
  }

  @Test
  public void extract_movesSymlinksOutOfOutputDirectory() throws Exception {
    DecompressorMain.extract(
        new String[] {
          archive.toString(),
          "tar.gz",
          tmp.resolve("out").toString(),
          tmp.resolve("symlinks").toString(),
          "pkg",
          "0"
        });

    assertThat(Files.readString(tmp.resolve("out/dir/file.txt"))).isEqualTo("content");
    assertThat(Files.exists(tmp.resolve("out/rel_link"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(Files.exists(tmp.resolve("out/dir/abs_link"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(new String(Files.readAllBytes(tmp.resolve("symlinks")), ISO_8859_1))
        .isEqualTo("A\0dir/abs_link\0dir/file.txt\0R\0rel_link\0dir/file.txt\0");
  }

  @Test
  public void extract_renamesFilesAndStripsComponents() throws Exception {
    DecompressorMain.extract(
        new String[] {
          archive.toString(),
          "tar.gz",
          tmp.resolve("out").toString(),
          tmp.resolve("symlinks").toString(),
          "",
          "1",
          "pkg/dir/file.txt",
          "pkg/renamed.txt"
        });

    assertThat(Files.readString(tmp.resolve("out/renamed.txt"))).isEqualTo("content");
    assertThat(Files.exists(tmp.resolve("out/dir/file.txt"))).isFalse();
  }

  @Test
  public void extract_missingPrefix() {
    assertThrows(
        RepositoryFunctionException.class,
        () ->
            DecompressorMain.extract(
                new String[] {
                  archive.toString(),
                  "tar.gz",
                  tmp.resolve("out").toString(),
                  tmp.resolve("symlinks").toString(),
                  "does-not-exist",
                  "0"
                }));
  }
}
