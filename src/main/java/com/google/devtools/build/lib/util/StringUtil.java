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
package com.google.devtools.build.lib.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Iterator;

/** Various utility methods operating on strings. */
public class StringUtil {
  /**
   * Creates a comma-separated list of words as in English.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>["a"] → "a"
   *   <li>["a", "b"] → "a or b"
   *   <li>["a", "b", "c"] → "a, b, or c"
   * </ul>
   */
  public static String joinEnglishList(Iterable<?> choices) {
    return joinEnglishList(choices, "or", "", /* oxfordComma= */ true);
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator.
   *
   * <p>Example with lastSeparator="and": ["a", "b", "c"] → "a, b, and c".
   */
  public static String joinEnglishList(Iterable<?> choices, String lastSeparator) {
    return joinEnglishList(choices, lastSeparator, "", /* oxfordComma= */ true);
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator and quotes.
   *
   * <p>Example with lastSeparator="then", quote="'", oxfordComma=false: ["a", "b", "c"] → "'a', 'b'
   * then 'c'".
   */
  public static String joinEnglishList(
      Iterable<?> choices, String lastSeparator, String quote, boolean oxfordComma) {
    StringBuilder buf = new StringBuilder();
    int numChoicesSeen = 0;
    for (Iterator<?> ii = choices.iterator(); ii.hasNext(); ) {
      Object choice = ii.next();
      if (buf.length() > 0) {
        if (ii.hasNext() || (oxfordComma && numChoicesSeen >= 2)) {
          buf.append(",");
        }
        if (!ii.hasNext()) {
          buf.append(" ").append(lastSeparator);
        }
        buf.append(" ");
      }
      buf.append(quote).append(choice).append(quote);
      numChoicesSeen++;
    }
    return buf.length() == 0 ? "nothing" : buf.toString();
  }

  /**
   * Creates a comma-separated list of singe-quoted words as in English.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>["a"] → "'a'""
   *   <li>["a", "b"] → "'a' or 'b'"
   *   <li>["a", "b", "c"] → "'a', 'b', or 'c'"
   * </ul>
   */
  public static String joinEnglishListSingleQuoted(Iterable<?> choices) {
    return joinEnglishList(choices, "or", "'", /* oxfordComma= */ true);
  }

  /**
   * Lists items up to a given limit, then prints how many were omitted.
   */
  public static StringBuilder listItemsWithLimit(StringBuilder appendTo, int limit,
      Collection<?> items) {
    Preconditions.checkState(limit > 0);
    Joiner.on(", ").appendTo(appendTo, Iterables.limit(items, limit));
    if (items.size() > limit) {
      appendTo.append(" ...(omitting ")
          .append(items.size() - limit)
          .append(" more item(s))");
    }
    return appendTo;
  }

  /**
   * Returns the ordinal representation of the number.
   */
  public static String ordinal(int number) {
    switch (number) {
      case 1:
        return "1st";
      case 2:
        return "2nd";
      case 3:
        return "3rd";
      default:
        return number + "th";
    }
  }

  /**
   * Even though Bazel attempts to force the default charset to be ISO-8859-1, which makes String
   * identical to the "bag of raw bytes" that a UNIX path is, the JVM may still use a different
   * encoding for file paths and command-line arguments, e.g. on macOS (always UTF-8). When
   * interoperating with Java APIs, we thus need to reencode paths to the JVM's native encoding.
   */
  private static final Charset SUN_JNI_ENCODING =
      Charset.forName(System.getProperty("sun.jnu.encoding"));

  // This only exists for RemoteWorker, which directly uses the RE APIs UTF-8-encoded string with
  // the JavaIoFileSystem and thus shouldn't be subject to any reencoding.
  private static final boolean BAZEL_UNICODE_STRINGS =
      Boolean.getBoolean("bazel.internal.UseUtf8ForStrings");

  /**
   * Reencodes a string using Bazel's internal raw byte encoding into the equivalent representation
   * for Java stdlib functions, if necessary.
   */
  public static String reencodeInternalToJava(String s) {
    return canSkipJavaReencode(s) ? s : new String(s.getBytes(ISO_8859_1), UTF_8);
  }

  /**
   * Reencodes a string obtained from Java stdlib functions into Bazel's internal raw byte encoding,
   * if necessary.
   */
  public static String reencodeJavaToInternal(String s) {
    return canSkipJavaReencode(s) ? s : new String(s.getBytes(UTF_8), ISO_8859_1);
  }

  private static boolean canSkipJavaReencode(String s) {
    // The comparisons below are expected to be constant-folded by the JIT.
    if (BAZEL_UNICODE_STRINGS) {
      return true;
    }
    if (SUN_JNI_ENCODING == US_ASCII) {
      return true;
    }
    if (SUN_JNI_ENCODING == ISO_8859_1 && OS.getCurrent() != OS.WINDOWS) {
      return true;
    }
    if (SUN_JNI_ENCODING == UTF_8 || SUN_JNI_ENCODING == ISO_8859_1) {
      return StringUnsafe.getInstance().isAscii(s);
    }
    return false;
  }

  public static String toInternal(String s, Charset sourceEncoding) {
    if (BAZEL_UNICODE_STRINGS) {
      return s;
    }
    if (sourceEncoding == US_ASCII) {
      return s;
    }
    if (sourceEncoding == UTF_8 && StringUnsafe.getInstance().isAscii(s)) {
      return s;
    }
    if (OS.getCurrent() == OS.LINUX && SUN_JNI_ENCODING == ISO_8859_1) {
      return new String(s.getBytes(sourceEncoding), ISO_8859_1);
    } else {
      return new String(s.getBytes(SUN_JNI_ENCODING), ISO_8859_1);
    }
  }


  public static String fromInternal(String s, Charset targetEncoding) {
    if (BAZEL_UNICODE_STRINGS) {
      return s;
    }
    if (targetEncoding == US_ASCII) {
      return s;
    }
    if (targetEncoding == UTF_8 && StringUnsafe.getInstance().isAscii(s)) {
      return s;
    }
    if (OS.getCurrent() == OS.LINUX && SUN_JNI_ENCODING == ISO_8859_1) {
      return new String(s.getBytes(ISO_8859_1), targetEncoding);
    } else {
      return new String(s.getBytes(ISO_8859_1), SUN_JNI_ENCODING);
    }
  }

  /**
   * Decode a String that might actually be UTF-8, in which case each input character will be
   * treated as a byte.
   *
   * <p>Several Bazel subsystems, including Starlark, store bytes in `String` values where each
   * `char` stores one `byte` in its lower 8 bits. This function converts its input to a `[]byte`,
   * then decodes that byte array as UTF-8.
   *
   * <p>Using U+2049 (EXCLAMATION QUESTION MARK) as an example:
   *
   * <p>"\u2049".getBytes(UTF_8) == [0xE2, 0x81, 0x89]
   *
   * <p>decodeBytestringUtf8("\u00E2\u0081\u0089") == "\u2049"
   *
   * <p>The return value is suitable for passing to Protobuf string fields or printing to the
   * terminal.
   */
  public static String reencodeInternalToUtf8(String maybeUtf8) {
    if (StringUnsafe.getInstance().isAscii(maybeUtf8)) {
      return maybeUtf8;
    }

    // Try our best to get a valid Unicode string, assuming that the input
    // is either UTF-8 (from Starlark or a UNIX file path) or already valid
    // Unicode (from a Windows file path).
    if (maybeUtf8.chars().anyMatch(c -> c > 0xFF)) {
      return maybeUtf8;
    }

    final byte[] utf8 = maybeUtf8.getBytes(ISO_8859_1);
    final String decoded = new String(utf8, UTF_8);

    // If the input was Unicode that happens to contain only codepoints in
    // the ISO-8859-1 range, then it will probably have a partial decoding
    // failure.
    if (decoded.chars().anyMatch(c -> c == 0xFFFD)) {
      return maybeUtf8;
    }

    return decoded;
  }

  /**
   * Encodes a String to UTF-8, then converts those UTF-8 bytes to a String by zero-extending each
   * `byte` into a `char`.
   *
   * <p>Using U+2049 (EXCLAMATION QUESTION MARK) as an example:
   *
   * <p>"\u2049".getBytes(UTF_8) == [0xE2, 0x81, 0x89]
   *
   * <p>encodeBytestringUtf8("\u2049") == "\u00E2\u0081\u0089"
   *
   * <p>See {@link #reencodeInternalToUtf8} for motivation.
   */
  public static String reencodeUtf8ToInternal(String unicode) {
    if (StringUnsafe.getInstance().isAscii(unicode)) {
      return unicode;
    }
    final byte[] utf8 = unicode.getBytes(UTF_8);
    return new String(utf8, ISO_8859_1);
  }

  private StringUtil() {}
}
