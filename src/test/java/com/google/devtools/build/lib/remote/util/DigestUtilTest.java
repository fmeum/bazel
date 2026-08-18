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
package com.google.devtools.build.lib.remote.util;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DigestUtil}. */
@RunWith(JUnit4.class)
public class DigestUtilTest {

  private static final BaseEncoding LOWER_CASE_HEX = BaseEncoding.base16().lowerCase();

  @Test
  public void buildDigest_encodesEveryByteValueAsLowerCaseHex() {
    for (int b = 0; b <= 0xFF; b++) {
      byte[] hash = {(byte) b};
      assertThat(DigestUtil.buildDigest(hash, 1).getHash()).isEqualTo(LOWER_CASE_HEX.encode(hash));
    }
  }

  @Test
  public void buildDigest_matchesLowerCaseHexEncodingAndRoundTrips() {
    var random = new Random(0);
    for (int length : new int[] {1, 16, 20, 32, 64}) {
      for (int trial = 0; trial < 100; trial++) {
        byte[] hash = new byte[length];
        random.nextBytes(hash);

        var digest = DigestUtil.buildDigest(hash, 123);

        assertThat(digest.getHash()).isEqualTo(LOWER_CASE_HEX.encode(hash));
        assertThat(digest.getSizeBytes()).isEqualTo(123);
        assertThat(DigestUtil.toBinaryDigest(digest)).isEqualTo(hash);
      }
    }
  }

  @Test
  public void hashCodeToString_matchesLowerCaseHexEncoding() {
    var hashCode = Hashing.sha256().hashString("hello", UTF_8);

    assertThat(DigestUtil.hashCodeToString(hashCode))
        .isEqualTo(LOWER_CASE_HEX.encode(hashCode.asBytes()));
  }
}
