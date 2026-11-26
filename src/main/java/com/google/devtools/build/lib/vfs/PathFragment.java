// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.errorprone.annotations.Immutable;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * A path segment representing a path fragment using the host machine's path style. That is; If you
 * are running on a Unix machine, the path style will be unix, on Windows it is the windows path
 * style.
 *
 * <p>Path fragments are either absolute or relative.
 *
 * <p>Strings are normalized with '.' and '..' removed and resolved (if possible), any multiple
 * slashes ('/') removed, and any trailing slash also removed. Windows drive letters are uppercased.
 * The current implementation does not touch the incoming path string unless the string actually
 * needs to be normalized.
 *
 * <p>There is some limited support for Windows-style paths. Most importantly, drive identifiers in
 * front of a path (c:/abc) are supported and such paths are correctly recognized as absolute, as
 * are paths with backslash separators (C:\\foo\\bar). However, advanced Windows-style features like
 * \\\\network\\paths and \\\\?\\unc\\paths are not supported. We are currently using forward
 * slashes ('/') even on Windows.
 *
 * <p>All paths are case-sensitive.
 *
 * <p>The normalized path is stored as a byte array in Bazel's internal encoding, which is shared
 * with the {@link String}s passed to and returned by this class. The {@link String#hashCode} of the
 * path is cached in a field that fits into the alignment padding of the object.
 */
@Immutable
public abstract sealed class PathFragment
    implements Comparable<PathFragment>, FileType.HasFileType, PathStrippable {
  private static final OsPathPolicy OS = OsPathPolicy.getFilePathOs();

  private static final boolean UNIX_STYLE_SUFFIX_CHECK = OS instanceof UnixOsPathPolicy;

  @SerializationConstant
  public static final PathFragment EMPTY_FRAGMENT = new RelativePathFragment(new byte[0]);

  public static final char SEPARATOR_CHAR = '/';

  /**
   * Compares two path fragments lexicographically as sequences of case-sensitive path segments. The
   * relative ordering of relative and absolute paths is unspecified.
   *
   * <p>The ordering imposed by this comparator differs from that of {@link
   * #compareTo(PathFragment)} as it sorts {@code foo/bar-baz/quz} after {@code foo/bar/quz} - it
   * has the property that the children of a path are sorted directly after their parent.
   */
  // TODO(bazel-team): Consider making this the default comparator for PathFragment.
  public static final Comparator<PathFragment> HIERARCHICAL_COMPARATOR =
      (p1, p2) -> {
        int k = p1.getCommonPrefixLength(p2);
        int n1 = p1.length();
        int n2 = p2.length();
        if (k == n1 || k == n2) {
          return Integer.compare(n1, n2);
        }
        char c1 = p1.charAt(k);
        char c2 = p2.charAt(k);
        if (c1 == '/') {
          // Sort a/b/c before a/b-c.
          return -1;
        }
        if (c2 == '/') {
          // Sort a/b-c after a/b/c.
          return 1;
        }
        return Character.compare(c1, c2);
      };

  private static final char ADDITIONAL_SEPARATOR_CHAR = OS.additionalSeparator();

  @SuppressWarnings("Immutable") // Never mutated.
  private final byte[] bytes;

  /** The {@link String#hashCode} of the path, or 0 if not computed yet. */
  @LazyInit private int hash;

  // DON'T add more fields here unless you know what you are doing. Adding another field will
  // increase the shallow heap of a PathFragment instance beyond the current value of 16 bytes.
  // Blaze's heap typically has many instances.

  /** Creates a new normalized path fragment. */
  public static PathFragment create(String path) {
    return createInternal(path, OS);
  }

  public static PathFragment createForOs(String path, com.google.devtools.build.lib.util.OS os) {
    return createInternal(path, OsPathPolicy.getFilePathOs(os));
  }

  private static PathFragment createInternal(String path, OsPathPolicy osPathPolicy) {
    if (path.isEmpty()) {
      return EMPTY_FRAGMENT;
    }
    int normalizationLevel = osPathPolicy.needsToNormalize(path);
    String normalizedPath =
        normalizationLevel != OsPathPolicy.NORMALIZED
            ? osPathPolicy.normalize(path, normalizationLevel)
            : path;
    int driveStrLength = osPathPolicy.getDriveStrLength(normalizedPath);
    return makePathFragment(normalizedPath, driveStrLength);
  }

  private static PathFragment makePathFragment(String normalizedPath, int driveStrLength) {
    return makePathFragment(internalBytes(normalizedPath), driveStrLength);
  }

  private static PathFragment makePathFragment(byte[] bytes, int driveStrLength) {
    return switch (driveStrLength) {
      case 0 -> bytes.length == 0 ? EMPTY_FRAGMENT : new RelativePathFragment(bytes);
      case 1 -> new UnixStyleAbsolutePathFragment(bytes);
      case 3 -> new WindowsStyleAbsolutePathFragment(bytes);
      default ->
          throw new IllegalStateException(
              String.format(
                  "normalizedPath: %s, driveStrLength: %s",
                  substring(bytes, 0, bytes.length), driveStrLength));
    };
  }

  /**
   * Returns the bytes of {@code s} in Bazel's internal encoding without copying if possible.
   *
   * <p>Strings that aren't coded in Latin-1 can't be internal strings and are converted, which
   * changes what {@link #getPathString()} returns for them.
   */
  private static byte[] internalBytes(String s) {
    if (StringUnsafe.getCoder(s) != StringUnsafe.LATIN1) {
      s = StringEncoding.unicodeToInternal(s);
    }
    return StringUnsafe.getByteArray(s);
  }

  private byte[] concat(boolean separator, byte[] suffix, int suffixStart, int suffixEnd) {
    int n = bytes.length;
    int m = suffixEnd - suffixStart;
    byte[] result = new byte[n + (separator ? 1 : 0) + m];
    System.arraycopy(bytes, 0, result, 0, n);
    if (separator) {
      result[n] = SEPARATOR_CHAR;
    }
    System.arraycopy(suffix, suffixStart, result, result.length - m, m);
    return result;
  }

  private byte[] concat(boolean separator, String suffix) {
    byte[] suffixBytes = internalBytes(suffix);
    return concat(separator, suffixBytes, 0, suffixBytes.length);
  }

  private byte[] range(int start, int end) {
    return start == 0 && end == bytes.length ? bytes : Arrays.copyOfRange(bytes, start, end);
  }

  private int length() {
    return bytes.length;
  }

  private byte byteAt(int i) {
    return bytes[i];
  }

  private char charAt(int i) {
    return (char) (bytes[i] & 0xff);
  }

  private int indexOfSeparator(int from) {
    int n = bytes.length;
    for (int i = from; i < n; i++) {
      if (bytes[i] == SEPARATOR_CHAR) {
        return i;
      }
    }
    return -1;
  }

  private int lastIndexOfSeparator() {
    for (int i = bytes.length - 1; i >= 0; i--) {
      if (bytes[i] == SEPARATOR_CHAR) {
        return i;
      }
    }
    return -1;
  }

  private static String substring(byte[] bytes, int start, int end) {
    if (start == end) {
      return "";
    }
    return StringUnsafe.newInstance(
        start == 0 && end == bytes.length ? bytes : Arrays.copyOfRange(bytes, start, end),
        StringUnsafe.LATIN1);
  }

  private String substring(int start, int end) {
    return substring(bytes, start, end);
  }

  private String substring(int start) {
    return substring(bytes, start, bytes.length);
  }

  /** Returns whether the bytes of this path start with the bytes of {@code prefix}. */
  private boolean hasBytePrefix(PathFragment prefix) {
    int m = prefix.bytes.length;
    return bytes.length >= m && Arrays.mismatch(bytes, 0, m, prefix.bytes, 0, m) == -1;
  }

  /**
   * Creates a new path fragment, where the caller promises that the path is normalized.
   *
   * <p>WARNING! Make sure the path fragment is in fact already normalized. The rest of the code
   * assumes this is the case.
   */
  public static PathFragment createAlreadyNormalized(String normalizedPath) {
    return normalizedPath.isEmpty()
        ? EMPTY_FRAGMENT
        : makePathFragment(normalizedPath, OS.getDriveStrLength(normalizedPath));
  }

  private PathFragment(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * Returns the normalized path.
   *
   * <p>This operation allocates a new string.
   */
  public String getPathString() {
    return substring(0);
  }

  /**
   * Returns the length of the case-sensitive common prefix of the two path strings.
   *
   * <p>The result is an index into {@link #getPathString()}, which uses Bazel's internal encoding.
   * It may fall within a path segment or a multi-byte UTF-8 sequence. For example, the common
   * prefix of {@code foo/bar} and {@code foo/baz} has length 6.
   *
   * <p>This operation does not allocate.
   */
  @SuppressWarnings("ReferenceEquality")
  public int getCommonPrefixLength(PathFragment other) {
    int n = bytes.length;
    if (bytes == other.bytes) {
      return n;
    }
    if (n == 0 || other.bytes.length == 0) {
      return 0;
    }
    // Arrays.mismatch uses a JVM intrinsic to compare multiple bytes at a time.
    int mismatch = Arrays.mismatch(bytes, other.bytes);
    return mismatch == -1 ? n : mismatch;
  }

  public final boolean isEmpty() {
    return length() == 0;
  }

  /**
   * Returns 0 for relative paths (e.g. "a/b"), 1 for Unix-style absolute paths (e.g. "/a/b"), and 3
   * for Windows-style absolute paths (e.g. "a:/b").
   */
  public abstract int getDriveStrLength();

  private static final class RelativePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a RelativePathFragment instance beyond the current value of 16
    // bytes. Our heap typically has many instances.

    private RelativePathFragment(byte[] bytes) {
      super(bytes);
    }

    @Override
    public int getDriveStrLength() {
      return 0;
    }
  }

  private static final class UnixStyleAbsolutePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a UnixStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances.

    private UnixStyleAbsolutePathFragment(byte[] bytes) {
      super(bytes);
    }

    @Override
    public int getDriveStrLength() {
      return 1;
    }
  }

  private static final class WindowsStyleAbsolutePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a WindowsStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances (when Bazel is run on Windows).

    private WindowsStyleAbsolutePathFragment(byte[] bytes) {
      super(bytes);
    }

    @Override
    public int getDriveStrLength() {
      return 3;
    }
  }

  /**
   * If called on a {@link PathFragment} instance for a mount name (eg. '/' or 'C:/'), the empty
   * string is returned.
   *
   * <p>This operation allocates a new string.
   */
  public String getBaseName() {
    int lastSeparator = lastIndexOfSeparator();
    return lastSeparator < getDriveStrLength()
        ? substring(getDriveStrLength())
        : substring(lastSeparator + 1);
  }

  /**
   * Returns a {@link PathFragment} instance formed by resolving {@code other} relative to this
   * path. For example, if this path is "a" and other is "b", returns "a/b".
   *
   * <p>If the passed path is absolute it is returned untouched. This can be useful to resolve
   * symlinks.
   */
  public PathFragment getRelative(PathFragment other) {
    Preconditions.checkNotNull(other);
    if (isEmpty() || other.isAbsolute()) {
      return other;
    }
    if (other.isEmpty()) {
      return this;
    }
    // The path fragment is already normal, use cheaper normalization check.
    int normalizationLevel =
        UNIX_STYLE_SUFFIX_CHECK
            ? (other.containsUplevelReferencesPrefix()
                ? OsPathPolicy.NEEDS_NORMALIZE
                : OsPathPolicy.NORMALIZED)
            : OS.needsToNormalizeSuffix(other.getPathString());
    if (normalizationLevel != OsPathPolicy.NORMALIZED) {
      return getRelative(other.getPathString(), other.getDriveStrLength(), normalizationLevel);
    }
    return makePathFragment(
        concat(length() != getDriveStrLength(), other.bytes, 0, other.bytes.length),
        getDriveStrLength());
  }

  /** Mirrors {@link UnixOsPathPolicy#needsToNormalizeSuffix}. */
  private boolean containsUplevelReferencesPrefix() {
    return length() >= 2 && byteAt(0) == '.' && byteAt(1) == '.';
  }

  /**
   * Returns a {@link PathFragment} instance formed by resolving {@code other} relative to this
   * path. For example, if this path is "a" and other is "b", returns "a/b".
   *
   * <p>See {@link #getRelative(PathFragment)} for details.
   */
  public PathFragment getRelative(String other) {
    Preconditions.checkNotNull(other);
    if (isEmpty()) {
      return create(other);
    }
    if (other.isEmpty()) {
      return this;
    }
    return getRelative(other, OS.getDriveStrLength(other), OS.needsToNormalize(other));
  }

  private PathFragment getRelative(String other, int otherDriveStrLength, int normalizationLevel) {
    // This is an absolute path, simply return it
    if (otherDriveStrLength > 0) {
      String normalizedPath =
          normalizationLevel != OsPathPolicy.NORMALIZED
              ? OS.normalize(other, normalizationLevel)
              : other;
      return makePathFragment(normalizedPath, otherDriveStrLength);
    }
    boolean separator = length() != getDriveStrLength();
    if (normalizationLevel == OsPathPolicy.NORMALIZED) {
      return makePathFragment(concat(separator, other), getDriveStrLength());
    }
    String newPath = separator ? getPathString() + '/' + other : getPathString() + other;
    return makePathFragment(OS.normalize(newPath, normalizationLevel), getDriveStrLength());
  }

  public static boolean isNormalizedRelativePath(String path) {
    int driveStrLength = OS.getDriveStrLength(path);
    int normalizationLevel = OS.needsToNormalize(path);
    return driveStrLength == 0 && normalizationLevel == OsPathPolicy.NORMALIZED;
  }

  public static boolean containsSeparator(String path) {
    return path.lastIndexOf(SEPARATOR_CHAR) != -1;
  }

  public PathFragment getChild(String baseName) {
    checkBaseName(baseName);
    return makePathFragment(
        concat(length() != getDriveStrLength(), baseName), getDriveStrLength());
  }

  /**
   * Returns the parent directory of this {@link PathFragment}.
   *
   * <p>If this is called on an single directory for a relative path, this returns an empty relative
   * path. If it's called on a root (like '/') or the empty string, it returns null.
   */
  @Nullable
  public PathFragment getParentDirectory() {
    int lastSeparator = lastIndexOfSeparator();

    // For absolute paths we need to specially handle when we hit root
    // Relative paths can't hit this path as driveStrLength == 0
    if (getDriveStrLength() > 0) {
      if (lastSeparator < getDriveStrLength()) {
        if (length() > getDriveStrLength()) {
          return makePathFragment(range(0, getDriveStrLength()), getDriveStrLength());
        } else {
          return null;
        }
      }
    } else {
      if (lastSeparator == -1) {
        if (!isEmpty()) {
          return EMPTY_FRAGMENT;
        } else {
          return null;
        }
      }
    }
    return makePathFragment(range(0, lastSeparator), getDriveStrLength());
  }

  /**
   * Returns the {@link PathFragment} relative to the base {@link PathFragment}.
   *
   * <p>For example, <code>
   * {@link PathFragment}.create("foo/bar/wiz").relativeTo({@link PathFragment}.create("foo"))
   * </code> returns <code>"bar/wiz"</code>.
   *
   * <p>If the {@link PathFragment} is not a child of the passed {@link PathFragment} an {@link
   * IllegalArgumentException} is thrown. In particular, this will happen whenever the two {@link
   * PathFragment} instances aren't both absolute or both relative.
   */
  public PathFragment relativeTo(PathFragment base) {
    Preconditions.checkNotNull(base);
    if (isAbsolute() != base.isAbsolute()) {
      throw new IllegalArgumentException(
          "Cannot relativize an absolute and a non-absolute path pair");
    }
    if (!hasBytePrefix(base)) {
      throw new IllegalArgumentException(
          String.format("Path '%s' is not under '%s', cannot relativize", this, base));
    }
    int bn = base.length();
    if (bn == 0) {
      return this;
    }
    int n = length();
    if (n == bn) {
      return EMPTY_FRAGMENT;
    }
    final int lastSlashIndex;
    if (base.byteAt(bn - 1) == '/') {
      lastSlashIndex = bn - 1;
    } else {
      lastSlashIndex = bn;
    }
    if (byteAt(lastSlashIndex) != '/') {
      throw new IllegalArgumentException(
          String.format("Path '%s' is not under '%s', cannot relativize", this, base));
    }
    return makePathFragment(range(lastSlashIndex + 1, n), 0);
  }

  public PathFragment relativeTo(String base) {
    return relativeTo(PathFragment.create(base));
  }

  /**
   * Returns true iff {@code other} is an ancestor of this path.
   *
   * <p>If this == other, true is returned.
   *
   * <p>An absolute path can never be an ancestor of a relative path, and vice versa.
   */
  public boolean startsWith(PathFragment other) {
    Preconditions.checkNotNull(other);
    int n = length();
    int m = other.length();
    if (m > n) {
      return false;
    }
    if (getDriveStrLength() != other.getDriveStrLength()) {
      return false;
    }
    if (!hasBytePrefix(other)) {
      return false;
    }
    return n == m || m == getDriveStrLength() || byteAt(m) == SEPARATOR_CHAR;
  }

  /**
   * Returns true iff {@code other} is an ancestor of this path, ignoring case.
   *
   * <p>If this == other, true is returned.
   *
   * <p>An absolute path can never be an ancestor of a relative path, and vice versa.
   */
  public boolean startsWithIgnoringCase(PathFragment other) {
    Preconditions.checkNotNull(other);
    if (this == other) {
      return true;
    }
    // Drive strings are ASCII only and hence can be checked without conversion to Unicode.
    int driveStrLength = getDriveStrLength();
    if (driveStrLength != other.getDriveStrLength()) {
      return false;
    }
    String normalizedPath = getPathString();
    String otherNormalizedPath = other.getPathString();
    if (otherNormalizedPath.length() == driveStrLength) {
      return normalizedPath.regionMatches(true, 0, otherNormalizedPath, 0, driveStrLength);
    }
    // Convert to regular Unicode Java strings so that Unicode case mappings are applied correctly.
    String otherNormalizedPathUnicode = internalToUnicode(otherNormalizedPath);
    if (otherNormalizedPathUnicode.length() > normalizedPath.length()) {
      return false;
    }
    String normalizedPathUnicode = internalToUnicode(normalizedPath);
    if (otherNormalizedPathUnicode.length() > normalizedPathUnicode.length()) {
      return false;
    }
    if (!normalizedPathUnicode.regionMatches(
        true, 0, otherNormalizedPathUnicode, 0, otherNormalizedPathUnicode.length())) {
      return false;
    }
    return normalizedPathUnicode.length() == otherNormalizedPathUnicode.length()
        || normalizedPathUnicode.charAt(otherNormalizedPathUnicode.length()) == SEPARATOR_CHAR;
  }

  /**
   * Returns true iff {@code other}, considered as a list of path segments, is relative and a suffix
   * of {@code this}, or both are absolute and equal.
   *
   * <p>This is a reflexive, transitive, anti-symmetric relation (i.e. a partial order)
   */
  public boolean endsWith(PathFragment other) {
    Preconditions.checkNotNull(other);
    int n = length();
    int m = other.length();
    if (m > n) {
      return false;
    }
    if (other.isAbsolute()) {
      return this.equals(other);
    }
    if (Arrays.mismatch(bytes, n - m, n, other.bytes, 0, m) != -1) {
      return false;
    }
    return n == m || m == 0 || byteAt(n - m - 1) == SEPARATOR_CHAR;
  }

  public boolean isAbsolute() {
    return getDriveStrLength() > 0;
  }

  public static boolean isAbsolute(String path) {
    return OS.getDriveStrLength(path) > 0;
  }

  @Override
  public String toString() {
    return getPathString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof PathFragment other
        && (bytes == other.bytes || Arrays.equals(bytes, other.bytes));
  }

  @Override
  public int hashCode() {
    int h = hash;
    if (h == 0 && bytes.length > 0) {
      // Uses the vectorized String#hashCode intrinsic. The String doesn't escape.
      h = StringUnsafe.newInstance(bytes, StringUnsafe.LATIN1).hashCode();
      hash = h;
    }
    return h;
  }

  /**
   * Compares this path fragment to another path fragment as normalized strings.
   *
   * <p>{@code dir/foo, dir/foo-bar/data.txt, dir/foo/data.txt} is sorted according to this method,
   * which is not consistent with viewing a path as a sequence of segments. See {@link
   * #HIERARCHICAL_COMPARATOR} for an alternative comparator.
   */
  @Override
  public int compareTo(PathFragment o) {
    // Matches String#compareTo for Latin-1 strings.
    return bytes == o.bytes ? 0 : Arrays.compareUnsigned(bytes, o.bytes);
  }

  ////////////////////////////////////////////////////////////////////////

  /**
   * Returns the number of segments in this path, excluding the drive string for absolute paths.
   *
   * <p>This operation is O(N) on the length of the string.
   */
  public int segmentCount() {
    int n = length();
    int segmentCount = 0;
    int i;
    for (i = getDriveStrLength(); i < n; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        ++segmentCount;
      }
    }
    // Add last segment if one exists.
    if (i > getDriveStrLength()) {
      ++segmentCount;
    }
    return segmentCount;
  }

  /**
   * Determines whether this path consists of a single segment, excluding the drive string for
   * absolute paths.
   *
   * <p>Prefer this method over {@link #segmentCount} when it is not necessary to know the exact
   * number of segments, as this short-circuits as soon as {@link #SEPARATOR_CHAR} is found.
   */
  public boolean isSingleSegment() {
    return length() > getDriveStrLength() && !isMultiSegment();
  }

  /**
   * Determines whether this path consists of multiple segments, excluding the drive string for
   * absolute paths.
   *
   * <p>Prefer this method over {@link #segmentCount} when it is not necessary to know the exact
   * number of segments, as this short-circuits as soon as {@link #SEPARATOR_CHAR} is found.
   */
  public boolean isMultiSegment() {
    return indexOfSeparator(getDriveStrLength()) >= 0;
  }

  /**
   * Returns the specified segment of this path; index must be non-negative and less than {@code
   * segmentCount()}.
   *
   * <p>This operation is O(N) on the length of the string.
   */
  public String getSegment(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("Illegal segment index: " + index);
    }
    int n = length();
    int segmentCount = 0;
    int i;
    for (i = getDriveStrLength(); i < n && segmentCount < index; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        ++segmentCount;
      }
    }
    int starti = i;
    for (; i < n; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        break;
      }
    }
    // Add last segment if one exists.
    if (i > getDriveStrLength()) {
      ++segmentCount;
    }
    int endi = i;
    if (index >= segmentCount) {
      throw new IllegalArgumentException("Illegal segment index: " + index);
    }
    return substring(starti, endi);
  }

  /**
   * Returns a new path fragment that is a sub fragment of this one. The sub fragment begins at the
   * specified <code>beginIndex</code> segment and ends at the segment at index <code>endIndex - 1
   * </code>. Thus the number of segments in the new PathFragment is <code>endIndex - beginIndex
   * </code>.
   *
   * <p>If the path is absolute and <code>beginIndex</code> is zero, the returned path is absolute.
   * Otherwise, if the path is relative or <code>beginIndex> is greater than zero, the returned path
   * is relative.
   *
   * <p>This operation is O(N) on the length of the string.
   *
   * @param beginIndex the beginning index, inclusive.
   * @param endIndex the ending index, exclusive.
   * @return the specified sub fragment, never null.
   * @throws IndexOutOfBoundsException if the <code>beginIndex</code> is negative, or <code>
   *     endIndex</code> is larger than the length of this <code>String</code> object, or <code>
   *     beginIndex</code> is larger than <code>endIndex</code>.
   */
  public PathFragment subFragment(int beginIndex, int endIndex) {
    if (beginIndex < 0 || beginIndex > endIndex) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex));
    }
    return subFragmentImpl(beginIndex, endIndex);
  }

  public PathFragment subFragment(int beginIndex) {
    if (beginIndex < 0) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d", toString(), beginIndex));
    }
    return subFragmentImpl(beginIndex, -1);
  }

  private PathFragment subFragmentImpl(int beginIndex, int endIndex) {
    int n = length();
    int segmentIndex = 0;
    int i;
    for (i = getDriveStrLength(); i < n && segmentIndex < beginIndex; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        ++segmentIndex;
      }
    }
    int starti = i;
    if (segmentIndex < endIndex) {
      for (; i < n; ++i) {
        if (byteAt(i) == SEPARATOR_CHAR) {
          ++segmentIndex;
          if (segmentIndex == endIndex) {
            break;
          }
        }
      }
    } else if (endIndex == -1) {
      i = n;
    }
    int endi = i;
    // Add last segment if one exists for verification
    if (i == n && i > getDriveStrLength()) {
      ++segmentIndex;
    }
    if (beginIndex > segmentIndex || endIndex > segmentIndex) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex));
    }
    // If beginIndex is 0, we include the drive string.
    int driveStrLength = 0;
    if (beginIndex == 0) {
      starti = 0;
      driveStrLength = this.getDriveStrLength();
      endi = Math.max(endi, driveStrLength);
    }
    if (starti == 0 && endi == n) {
      return this;
    }
    return makePathFragment(range(starti, endi), driveStrLength);
  }

  /** Strip <code>numComponents</code> leading components from file names on extraction. */
  public PathFragment stripComponents(int numComponents) {
    if (numComponents == 0) {
      return this;
    }
    if (numComponents < 0) {
      throw new IllegalArgumentException(
          String.format("Invalid number of components (%d)", numComponents));
    }
    int n = length();
    int i = getDriveStrLength();
    for (; i < n && numComponents > 0; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        --numComponents;
      }
    }
    if (i >= n) {
      return EMPTY_FRAGMENT;
    }
    return makePathFragment(range(i, n), 0);
  }

  /**
   * Returns an {@link Iterable} that lazily yields the segments of this path.
   *
   * <p>When iterating over the segments of a path fragment, prefer this method to {@link
   * #splitToListOfSegments} as it performs a single, lazy traversal over the path string without
   * the overhead of creating a list.
   */
  public Iterable<String> segments() {
    return () -> PathSegmentIterator.create(getPathString(), getDriveStrLength());
  }

  /**
   * Splits this path fragment into a list of segments.
   *
   * <p>This operation is O(N) on the length of the string. If it is not necessary to store the
   * segments in list form, consider using {@link #segments}.
   */
  public ImmutableList<String> splitToListOfSegments() {
    ImmutableList.Builder<String> segments = ImmutableList.builderWithExpectedSize(segmentCount());
    int nexti = getDriveStrLength();
    int n = length();
    for (int i = getDriveStrLength(); i < n; ++i) {
      if (byteAt(i) == SEPARATOR_CHAR) {
        segments.add(substring(nexti, i));
        nexti = i + 1;
      }
    }
    // Add last segment if one exists.
    if (nexti < n) {
      segments.add(substring(nexti));
    }
    return segments.build();
  }

  /** Returns the path string, or '.' if the path is empty. */
  public String getSafePathString() {
    return !isEmpty() ? getPathString() : ".";
  }

  /**
   * Returns the path string using '/' as the name-separator character, but do so in a way
   * unambiguously recognizable as path. In other words, return "." for relative and empty paths,
   * and prefix relative paths with one segment by "./".
   *
   * <p>In this way, a shell will always interpret such a string as path (absolute or relative to
   * the working directory) and not as command to be searched for in the search path.
   *
   * <p>Prefer {@link #getCallablePathStringForOs} if the execution OS is available.
   */
  public String getCallablePathString() {
    if (isAbsolute()) {
      return getPathString();
    } else if (isEmpty()) {
      return ".";
    } else if (indexOfSeparator(0) == -1) {
      return "." + SEPARATOR_CHAR + getPathString();
    } else {
      return getPathString();
    }
  }

  /**
   * Returns the path string using the native name-separator for the given OS, but does so in a way
   * unambiguously recognizable as path. In other words, return "." for relative and empty paths,
   * and prefix relative paths with an additional "." segment.
   *
   * <p>In this way, a shell will always interpret such a string as path (absolute or relative to
   * the working directory) and not as command to be searched for in the search path.
   */
  public String getCallablePathStringForOs(com.google.devtools.build.lib.util.OS executionOs) {
    return OsPathPolicy.of(executionOs).postProcessPathStringForExecution(getCallablePathString());
  }

  /**
   * Returns the file extension of this path, excluding the period, or "" if there is no extension.
   */
  public String getFileExtension() {
    int n = length();
    for (int i = n - 1; i > getDriveStrLength(); --i) {
      byte c = byteAt(i);
      if (c == '.') {
        return substring(i + 1, n);
      } else if (c == SEPARATOR_CHAR) {
        break;
      }
    }
    return "";
  }

  /**
   * Returns a {@link PathFragment} formed by appending {@code newName} to this {@link
   * PathFragment}'s parent directory. If this {@link PathFragment} has zero segments, returns
   * {@code null}. If {@code newName} is absolute, the value of {@code this} will be ignored and a
   * {@link PathFragment} corresponding to {@code newName} will be returned. This is consistent with
   * the behavior of {@link #getRelative(String)}.
   */
  @Nullable
  public PathFragment replaceName(String newName) {
    PathFragment parent = getParentDirectory();
    return parent != null ? parent.getRelative(newName) : null;
  }

  /**
   * Returns the drive for an absolute path fragment.
   *
   * <p>On unix, this will return "/". On Windows it will return the drive letter, like "C:/".
   */
  public String getDriveStr() {
    Preconditions.checkArgument(isAbsolute());
    return substring(0, getDriveStrLength());
  }

  /**
   * Returns a relative PathFragment created from this absolute PathFragment using the same segments
   * and drive letter.
   */
  public PathFragment toRelative() {
    Preconditions.checkArgument(isAbsolute());
    return makePathFragment(range(getDriveStrLength(), length()), 0);
  }

  /**
   * Returns true if this path contains uplevel references "..".
   *
   * <p>Since path fragments are normalized, this implies that the uplevel reference is at the start
   * of the path fragment.
   */
  public boolean containsUplevelReferences() {
    // Path is normalized, so any ".." would have to be the first segment.
    return containsUplevelReferencesPrefix() && (length() == 2 || byteAt(2) == SEPARATOR_CHAR);
  }

  /**
   * Returns true if the passed path contains uplevel references "..".
   *
   * <p>This is useful to check a string for '..' segments before constructing a PathFragment, since
   * these are always normalized and will throw uplevel references away.
   */
  public static boolean containsUplevelReferences(String path) {
    return !isNormalizedImpl(path, /* lookForSameLevelReferences= */ false);
  }

  /**
   * Returns true if the passed path does not contain uplevel references ("..") or single-dot
   * references (".").
   *
   * <p>This is useful to check a string for normalization before constructing a PathFragment, since
   * these are always normalized and will throw uplevel references away.
   */
  public static boolean isNormalized(String path) {
    return isNormalizedImpl(path, /* lookForSameLevelReferences= */ true);
  }

  private enum NormalizedImplState {
    Base, /* No particular state, eg. an 'a' or 'L' character */
    Separator, /* We just saw a separator */
    Dot, /* We just saw a dot after a separator */
    DotDot, /* We just saw two dots after a separator */
  }

  private static boolean isNormalizedImpl(String path, boolean lookForSameLevelReferences) {
    // Starting state is equivalent to having just seen a separator
    NormalizedImplState state = NormalizedImplState.Separator;
    int n = path.length();
    for (int i = 0; i < n; ++i) {
      char c = path.charAt(i);
      boolean isSeparator = OS.isSeparator(c);
      switch (state) {
        case Base -> {
          if (isSeparator) {
            state = NormalizedImplState.Separator;
          } else {
            state = NormalizedImplState.Base;
          }
        }
        case Separator -> {
          if (isSeparator) {
            state = NormalizedImplState.Separator;
          } else if (c == '.') {
            state = NormalizedImplState.Dot;
          } else {
            state = NormalizedImplState.Base;
          }
        }
        case Dot -> {
          if (isSeparator) {
            if (lookForSameLevelReferences) {
              // "." segment found
              return false;
            }
            state = NormalizedImplState.Separator;
          } else if (c == '.') {
            state = NormalizedImplState.DotDot;
          } else {
            state = NormalizedImplState.Base;
          }
        }
        case DotDot -> {
          if (isSeparator) {
            // ".." segment found
            return false;
          } else {
            state = NormalizedImplState.Base;
          }
        }
      }
    }
    // The character just after the string is equivalent to a separator
    switch (state) {
      case Dot:
        if (lookForSameLevelReferences) {
          // "." segment found
          return false;
        }
        break;
      case DotDot:
        return false;
      default:
    }
    return true;
  }

  /**
   * Throws {@link IllegalArgumentException} if {@code paths} contains any paths that are equal to
   * {@code startingWithPath} or that are not beneath {@code startingWithPath}.
   */
  public static void checkAllPathsAreUnder(
      Iterable<PathFragment> paths, PathFragment startingWithPath) {
    for (PathFragment path : paths) {
      Preconditions.checkArgument(
          !path.equals(startingWithPath) && path.startsWith(startingWithPath),
          "%s is not beneath %s",
          path,
          startingWithPath);
    }
  }

  @Override
  public String filePathForFileTypeMatcher() {
    return getPathString();
  }

  @Override
  public String expand(UnaryOperator<PathFragment> stripPaths) {
    return stripPaths.apply(this).getPathString();
  }

  private static void checkBaseName(String baseName) {
    if (baseName.isEmpty()) {
      throw new IllegalArgumentException("Child must not be empty string ('')");
    }
    if (baseName.equals(".") || baseName.equals("..")) {
      throw new IllegalArgumentException("baseName must not be '" + baseName + "'");
    }
    try {
      checkSeparators(baseName);
    } catch (InvalidBaseNameException e) {
      throw new IllegalArgumentException("baseName " + e.getMessage() + ": '" + baseName + "'", e);
    }
  }

  public static void checkSeparators(String baseName) throws InvalidBaseNameException {
    if (baseName.indexOf(SEPARATOR_CHAR) != -1) {
      throw new InvalidBaseNameException("must not contain " + SEPARATOR_CHAR);
    }
    if (ADDITIONAL_SEPARATOR_CHAR != 0) {
      if (baseName.indexOf(ADDITIONAL_SEPARATOR_CHAR) != -1) {
        throw new InvalidBaseNameException("must not contain " + ADDITIONAL_SEPARATOR_CHAR);
      }
    }
  }

  /** Indicates that a path fragment's base name had invalid characters. */
  public static final class InvalidBaseNameException extends Exception {
    private InvalidBaseNameException(String message) {
      super(message);
    }
  }

  public static Codec pathFragmentCodec() {
    return Codec.INSTANCE;
  }

  private static class Codec extends LeafObjectCodec<PathFragment> {
    private static final Codec INSTANCE = new Codec();

    @Override
    public Class<PathFragment> getEncodedClass() {
      return PathFragment.class;
    }

    @Override
    public void serialize(
        LeafSerializationContext context, PathFragment obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serializeLeaf(obj.getPathString(), stringCodec(), codedOut);
    }

    @Override
    public PathFragment deserialize(LeafDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      return createAlreadyNormalized(context.deserializeLeaf(codedIn, stringCodec()));
    }
  }
}
