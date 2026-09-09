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
package com.google.devtools.build.lib.remote.common;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TeeOutputStream}. */
@RunWith(JUnit4.class)
public final class TeeOutputStreamTest {
  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final ByteArrayOutputStream primary = new ByteArrayOutputStream();
  private Path mirrorPath;

  @Before
  public void setUp() throws IOException {
    mirrorPath = fs.getPath("/dir/mirror");
    mirrorPath.getParentDirectory().createDirectoryAndParents();
  }

  @Test
  public void writesToBothStreams() throws IOException {
    var tee = new TeeOutputStream(primary, new LazyFileOutputStream(mirrorPath));

    tee.write('a');
    tee.write("bcd".getBytes(UTF_8));
    tee.write("xefy".getBytes(UTF_8), 1, 2);
    tee.flush();

    assertThat(tee.closeMirror()).isNull();
    assertThat(primary.toString(UTF_8)).isEqualTo("abcdef");
    assertThat(FileSystemUtils.readContent(mirrorPath, UTF_8)).isEqualTo("abcdef");
  }

  @Test
  public void closeMirror_withoutWrites_createsEmptyFile() throws IOException {
    var tee = new TeeOutputStream(primary, new LazyFileOutputStream(mirrorPath));

    assertThat(tee.closeMirror()).isNull();

    assertThat(mirrorPath.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(mirrorPath, UTF_8)).isEmpty();
  }

  @Test
  public void mirrorFailure_doesNotAffectPrimary() throws IOException {
    // The mirror can't be created because its parent directory doesn't exist.
    Path unwritableMirror = fs.getPath("/does_not_exist/mirror");
    var tee = new TeeOutputStream(primary, new LazyFileOutputStream(unwritableMirror));

    tee.write("abc".getBytes(UTF_8));
    tee.write('d');
    tee.flush();

    assertThat(primary.toString(UTF_8)).isEqualTo("abcd");
    IOException failure = tee.closeMirror();
    assertThat(failure).isNotNull();
    // The same failure is reported again rather than a new one from creating the file.
    assertThat(tee.closeMirror()).isSameInstanceAs(failure);
    assertThat(unwritableMirror.exists()).isFalse();
  }

  @Test
  public void primaryFailure_propagates() throws IOException {
    OutputStream failingPrimary =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("primary failed");
          }
        };
    var tee = new TeeOutputStream(failingPrimary, new LazyFileOutputStream(mirrorPath));

    var e = assertThrows(IOException.class, () -> tee.write('a'));

    assertThat(e).hasMessageThat().isEqualTo("primary failed");
  }

  @Test
  public void close_closesBoth() throws IOException {
    var closed = new boolean[1];
    OutputStream trackingPrimary =
        new OutputStream() {
          @Override
          public void write(int b) {}

          @Override
          public void close() {
            closed[0] = true;
          }
        };
    var tee = new TeeOutputStream(trackingPrimary, new LazyFileOutputStream(mirrorPath));
    tee.write("abc".getBytes(UTF_8));

    tee.close();

    assertThat(closed[0]).isTrue();
    assertThat(FileSystemUtils.readContent(mirrorPath, UTF_8)).isEqualTo("abc");
  }
}
