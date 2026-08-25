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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * (Slow) tests of FileSystem under concurrency.
 *
 * These tests are nondeterministic but provide good coverage nonetheless.
 */
@RunWith(JUnit4.class)
public class FileSystemConcurrencyTest {
  Path workingDir;

  @Before
  public final void initializeFileSystem() throws Exception  {
    FileSystem testFS = FileSystems.getNativeFileSystem();

    // Resolve symbolic links in the temp dir:
    workingDir = testFS.getPath(new File(TestUtils.tmpDir()).getCanonicalPath());
  }

  @Test
  public void testConcurrentSymlinkModifications() throws Exception {
    Path xFile = workingDir.getRelative("file");
    FileSystemUtils.createEmptyFile(xFile);

    Path xLinkToFile = workingDir.getRelative("link");

    AtomicBoolean run = new AtomicBoolean(true);
    TestThread createThread =
        new TestThread(
            () -> {
              while (run.get()) {
                if (!xLinkToFile.exists()) {
                  xLinkToFile.createSymbolicLink(xFile);
                }
              }
            });
    TestThread deleteThread =
        new TestThread(
            () -> {
              while (run.get()) {
                if (xLinkToFile.exists(Symlinks.NOFOLLOW)) {
                  xLinkToFile.delete();
                }
              }
            });
    createThread.start();
    deleteThread.start();
    Thread.sleep(1000);
    run.set(false);
    createThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    deleteThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
  }

  /**
   * Digests files of assorted sizes from many threads at once.
   *
   * <p>{@link FileSystem#getDigest} reads file contents through a recycled scratch buffer, so a
   * buffer handed to two threads at the same time would silently produce wrong digests.
   */
  @Test
  public void testConcurrentDigests() throws Exception {
    int fileCount = 64;
    Path[] files = new Path[fileCount];
    byte[][] expectedDigests = new byte[fileCount][];
    for (int i = 0; i < fileCount; i++) {
      // Cover files both well below and well above any plausible scratch buffer size.
      byte[] contents = new byte[i * 4096];
      Arrays.fill(contents, (byte) i);
      files[i] = workingDir.getRelative("digested" + i);
      FileSystemUtils.writeContent(files[i], contents);
      expectedDigests[i] = files[i].getDigest();
    }

    int threadCount = 16;
    TestThread[] threads = new TestThread[threadCount];
    for (int t = 0; t < threadCount; t++) {
      threads[t] =
          new TestThread(
              () -> {
                for (int round = 0; round < 20; round++) {
                  for (int i = 0; i < fileCount; i++) {
                    assertThat(files[i].getDigest()).isEqualTo(expectedDigests[i]);
                  }
                }
              });
    }
    for (TestThread thread : threads) {
      thread.start();
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    }
  }
}
