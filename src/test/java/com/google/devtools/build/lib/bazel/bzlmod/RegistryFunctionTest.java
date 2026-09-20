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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RegistryFunction}. */
@RunWith(JUnit4.class)
public class RegistryFunctionTest {

  @Test
  public void filterFileHashesForRegistry_keepsOnlyOwnFiles() throws Exception {
    Optional<Checksum> checksum =
        Optional.of(
            Checksum.fromSubresourceIntegrity(
                "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="));
    ImmutableMap<String, Optional<Checksum>> hashes =
        ImmutableMap.of(
            "https://example.com/modules/foo/1.0/MODULE.bazel", checksum,
            "https://example.com/modules/foo/1.0/source.json", Optional.empty(),
            "https://example.com/bazel_registry.json", checksum,
            // A registry whose URL has the registry's URL as a prefix.
            "https://example.com-other/modules/foo/1.0/MODULE.bazel", checksum,
            "https://example.org/modules/foo/1.0/MODULE.bazel", checksum);

    assertThat(RegistryFunction.filterFileHashesForRegistry(hashes, "https://example.com"))
        .containsExactly(
            "https://example.com/modules/foo/1.0/MODULE.bazel", checksum,
            "https://example.com/modules/foo/1.0/source.json", Optional.empty(),
            "https://example.com/bazel_registry.json", checksum)
        .inOrder();
    // A trailing slash in the registry URL is handled as well.
    assertThat(RegistryFunction.filterFileHashesForRegistry(hashes, "https://example.com/"))
        .containsExactly(
            "https://example.com/modules/foo/1.0/MODULE.bazel", checksum,
            "https://example.com/modules/foo/1.0/source.json", Optional.empty(),
            "https://example.com/bazel_registry.json", checksum)
        .inOrder();
    assertThat(RegistryFunction.filterFileHashesForRegistry(hashes, "https://example.net"))
        .isEmpty();
  }
}
