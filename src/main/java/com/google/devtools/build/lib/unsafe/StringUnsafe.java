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

import com.google.common.base.Ascii;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Provides direct access to the string implementation used by JDK9.
 *
 * <p>As of JDK9, a string is two fields: <code>byte coder</code>, and <code>byte[] value</code>.
 * The <code>coder</code> field has value 0 if the encoding is LATIN-1, and 2 if the encoding is
 * UTF-16 (the classic JDK8 encoding).
 *
 * <p>The <code>value</code> field contains the actual bytes.
 */
public final class StringUnsafe {
  // Fields corresponding to the coder
  public static final byte LATIN1 = 0;
  public static final byte UTF16 = 1;

  private static final MethodHandle CONSTRUCTOR;
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODE_HANDLE;

  private static final VarHandle LONG_VIEW =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
  private static final VarHandle INT_VIEW =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
  private static final long LONG_HIGH_BITS = 0x8080808080808080L;
  private static final int INT_HIGH_BITS = 0x80808080;

  static {
    try {
      Constructor<String> constructor =
          String.class.getDeclaredConstructor(byte[].class, byte.class);
      constructor.setAccessible(true);
      CONSTRUCTOR = MethodHandles.lookup().unreflectConstructor(constructor);

      var stringLookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
      VALUE_HANDLE = stringLookup.unreflectVarHandle(String.class.getDeclaredField("value"));
      CODE_HANDLE = stringLookup.unreflectVarHandle(String.class.getDeclaredField("coder"));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns the coder used for this string. See {@link #LATIN1} and {@link #UTF16}. */
  public static byte getCoder(String obj) {
    return (byte) CODE_HANDLE.get(obj);
  }

  /**
   * Returns the internal byte array, encoded according to {@link #getCoder}.
   *
   * <p>Use of this is unsafe. The representation may change from one JDK version to the next.
   * Ensure you do not mutate this byte array in any way.
   */
  public static byte[] getByteArray(String obj) {
    return (byte[]) VALUE_HANDLE.get(obj);
  }

  /**
   * Return the internal byte array of a String using Bazel's internal encoding (see {@link
   * com.google.devtools.build.lib.util.StringEncoding}).
   *
   * <p>Callers must not mutate the returned byte array.
   */
  public static byte[] getInternalStringBytes(String obj) {
    // This is both a performance optimization and a correctness check: internal strings must
    // always be coded in Latin-1, otherwise they have been constructed out of a non-ASCII string
    // that hasn't been converted to internal encoding.
    if (getCoder(obj) != LATIN1) {
      // Truncation is ASCII only and thus doesn't change the encoding.
      String truncatedString = Ascii.truncate(obj, 1000, "...");
      throw new IllegalArgumentException(
          String.format(
              "Expected internal string with Latin-1 coder, got: %s (%s)",
              truncatedString, Arrays.toString(getByteArray(truncatedString))));
    }
    return getByteArray(obj);
  }

  /** Returns whether the string is ASCII-only. */
  public static boolean isAscii(String obj) {
    if (getCoder(obj) != LATIN1) {
      // Latin-1 is a superset of ASCII, so we must have non-ASCII characters.
      return false;
    }
    // Checks the high bits of multiple bytes at once. See IsAsciiBenchmark for a comparison with
    // the JDK's intrinsic.
    byte[] bytes = getByteArray(obj);
    int length = bytes.length;
    if (length >= 8) {
      int i = 0;
      for (; i <= length - 32; i += 32) {
        long word =
            (long) LONG_VIEW.get(bytes, i)
                | (long) LONG_VIEW.get(bytes, i + 8)
                | (long) LONG_VIEW.get(bytes, i + 16)
                | (long) LONG_VIEW.get(bytes, i + 24);
        if ((word & LONG_HIGH_BITS) != 0) {
          return false;
        }
      }
      long word = 0;
      for (; i <= length - 8; i += 8) {
        word |= (long) LONG_VIEW.get(bytes, i);
      }
      // Covers the remaining 0 to 7 bytes by overlapping with bytes already checked.
      word |= (long) LONG_VIEW.get(bytes, length - 8);
      return (word & LONG_HIGH_BITS) == 0;
    }
    if (length >= 4) {
      int word = (int) INT_VIEW.get(bytes, 0) | (int) INT_VIEW.get(bytes, length - 4);
      return (word & INT_HIGH_BITS) == 0;
    }
    if (length == 0) {
      return true;
    }
    // Covers lengths 1 to 3.
    return (bytes[0] | bytes[length / 2] | bytes[length - 1]) >= 0;
  }

  /**
   * Constructs a new string from a byte array and coder.
   *
   * <p>The new string shares the byte array instance, which must not be modified after calling this
   * method.
   */
  public static String newInstance(byte[] bytes, byte coder) {
    try {
      return (String) CONSTRUCTOR.invokeExact(bytes, coder);
    } catch (Throwable e) {
      // The constructor never throws, so this is not expected.
      throw new IllegalStateException(
          "Could not instantiate string: " + Arrays.toString(bytes) + ", " + coder, e);
    }
  }

  private StringUnsafe() {}
}
