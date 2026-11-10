// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unsafe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StringUnsafe}. */
@RunWith(JUnit4.class)
public final class StringUnsafeTest {

  @Test
  public void testGetCoder() {
    assertThat(StringUnsafe.getCoder("")).isEqualTo(StringUnsafe.LATIN1);
    assertThat(StringUnsafe.getCoder("hello")).isEqualTo(StringUnsafe.LATIN1);
    assertThat(StringUnsafe.getCoder("lambda λ")).isEqualTo(StringUnsafe.UTF16);
  }

  @Test
  public void testGetBytes() {
    assertThat(ByteBuffer.wrap(StringUnsafe.getByteArray("hello")))
        .isEqualTo(StandardCharsets.ISO_8859_1.encode("hello"));

    if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
      assertThat(ByteBuffer.wrap(StringUnsafe.getByteArray("lambda λ")))
          .isEqualTo(StandardCharsets.UTF_16BE.encode("lambda λ"));
    } else {
      assertThat(ByteBuffer.wrap(StringUnsafe.getByteArray("lambda λ")))
          .isEqualTo(StandardCharsets.UTF_16LE.encode("lambda λ"));
    }
  }

  @Test
  public void testNewInstance() throws Exception {
    String s = "hello";
    assertThat(StringUnsafe.newInstance(StringUnsafe.getByteArray(s), StringUnsafe.getCoder(s)))
        .isEqualTo("hello");
  }

  @Test
  public void testIsAscii() {
    assertThat(StringUnsafe.isAscii("")).isTrue();
    assertThat(StringUnsafe.isAscii("hello")).isTrue();
    assertThat(StringUnsafe.isAscii("hällo")).isFalse();
    assertThat(StringUnsafe.isAscii("hållo")).isFalse();
    assertThat(StringUnsafe.isAscii("h👋llo")).isFalse();
  }

  @Test
  public void testIsAscii_allLengthsAndPositions() {
    for (int length = 0; length <= 100; length++) {
      char[] chars = new char[length];
      Arrays.fill(chars, '\u007f');
      assertWithMessage("length %s", length).that(StringUnsafe.isAscii(new String(chars))).isTrue();
      for (int i = 0; i < length; i++) {
        chars[i] = '\u0080';
        String s = new String(chars);
        assertThat(StringUnsafe.getCoder(s)).isEqualTo(StringUnsafe.LATIN1);
        assertWithMessage("length %s, position %s", length, i)
            .that(StringUnsafe.isAscii(s))
            .isFalse();
        chars[i] = '\u007f';
      }
    }
  }
}
