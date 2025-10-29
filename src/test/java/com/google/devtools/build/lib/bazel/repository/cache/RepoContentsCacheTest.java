// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.cache;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepoContentsCache}. */
@RunWith(JUnit4.class)
public class RepoContentsCacheTest {

  private Scratch scratch;
  private RepoContentsCache cache;

  @Before
  public void setUp() throws Exception {
    scratch = new Scratch();
    cache = new RepoContentsCache();
  }

  @Test
  public void testReleaseSharedLock_withoutAcquiring_doesNotThrow() throws IOException {
    // Set up a cache path so isEnabled() returns true
    Path cachePath = scratch.resolve("/cache");
    cachePath.createDirectoryAndParents();
    cache.setPath(cachePath);

    // Release without acquiring should not throw an exception
    cache.releaseSharedLock();
  }

  @Test
  public void testReleaseSharedLock_whenDisabled_doesNotThrow() throws IOException {
    // When cache is disabled (path is null), release should not throw
    cache.setPath(null);
    cache.releaseSharedLock();
  }

  @Test
  public void testAcquireAndReleaseSharedLock_normalFlow() throws Exception {
    Path cachePath = scratch.resolve("/cache");
    cachePath.createDirectoryAndParents();
    cache.setPath(cachePath);

    // Normal flow: acquire then release
    cache.acquireSharedLock();
    cache.releaseSharedLock();
  }

  @Test
  public void testReleaseSharedLock_multipleTimesWithoutAcquiring_doesNotThrow() throws IOException {
    Path cachePath = scratch.resolve("/cache");
    cachePath.createDirectoryAndParents();
    cache.setPath(cachePath);

    // Multiple releases without acquiring should not throw
    cache.releaseSharedLock();
    cache.releaseSharedLock();
  }

  @Test
  public void testIsEnabled_withPath() throws IOException {
    Path cachePath = scratch.resolve("/cache");
    cachePath.createDirectoryAndParents();
    cache.setPath(cachePath);

    assertThat(cache.isEnabled()).isTrue();
  }

  @Test
  public void testIsEnabled_withoutPath() {
    cache.setPath(null);

    assertThat(cache.isEnabled()).isFalse();
  }
}
