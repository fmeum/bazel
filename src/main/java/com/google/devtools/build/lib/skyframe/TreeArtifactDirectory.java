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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A directory in the hierarchy of children of a {@link TreeArtifactValue}.
 *
 * <p>The names of the entries are packed into a single byte array and the entries themselves, which
 * are either the {@link FileArtifactValue} of a file or the {@link TreeArtifactDirectory} of a
 * subdirectory, are stored in a parallel array. Compared to a map from {@link
 * com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact} to {@link FileArtifactValue},
 * this avoids retaining an artifact and a string holding the full exec path for every file, which
 * dominate the memory usage of large tree artifacts.
 *
 * <p>Entries are sorted by name, with a '/' appended to the names of subdirectories. As a result, a
 * depth-first traversal visits files in the exec path order of the corresponding tree file
 * artifacts. For example, the entries {@code a.txt}, {@code a/} and {@code a0} are sorted in this
 * order since {@code '.' < '/' < '0'}.
 *
 * <p>Names are stored in Bazel's internal string representation, in which every char represents a
 * single byte. Paths containing other chars are not {@linkplain #canRepresent representable}.
 */
@AutoCodec
final class TreeArtifactDirectory {
  /**
   * The names of the entries, encoded as the end offset of each name followed by the concatenation
   * of all names. The offsets are relative to the start of the concatenated names and stored as
   * big-endian unsigned integers of {@link #offsetWidth} bytes.
   */
  private final byte[] names;

  /**
   * The entries, each of which is a {@link FileArtifactValue} or a {@link TreeArtifactDirectory}.
   */
  private final Object[] entries;

  @VisibleForSerialization
  TreeArtifactDirectory(byte[] names, Object[] entries) {
    this.names = names;
    this.entries = entries;
  }

  /** Returns whether the given parent-relative path can be stored in a directory. */
  static boolean canRepresent(String parentRelativePath) {
    if (parentRelativePath.isEmpty()) {
      return false;
    }
    for (int i = 0; i < parentRelativePath.length(); i++) {
      if (parentRelativePath.charAt(i) > 0xFF) {
        return false;
      }
    }
    return true;
  }

  /**
   * Creates a directory containing the given files.
   *
   * @param paths the normalized parent-relative paths of the files, sorted in string order, each of
   *     which must be {@linkplain #canRepresent representable}
   * @param metadata the metadata of the files, in the same order as {@code paths}
   */
  static TreeArtifactDirectory create(String[] paths, FileArtifactValue[] metadata) {
    checkArgument(paths.length > 0, "A directory must have at least one entry");
    checkArgument(paths.length == metadata.length, "Mismatched number of paths and metadata");
    return create(paths, metadata, 0, paths.length, 0);
  }

  /**
   * Creates a directory from the paths in the range {@code [from, to)}, all of which start with the
   * same {@code prefixLength} characters (which end with a '/' if non-empty).
   */
  private static TreeArtifactDirectory create(
      String[] paths, FileArtifactValue[] metadata, int from, int to, int prefixLength) {
    List<String> names = new ArrayList<>();
    List<Object> entries = new ArrayList<>();
    int i = from;
    while (i < to) {
      String path = paths[i];
      int slash = path.indexOf('/', prefixLength);
      if (slash == -1) {
        names.add(path.substring(prefixLength));
        entries.add(metadata[i]);
        i++;
        continue;
      }
      // All paths in the subdirectory start with the same prefix up to and including the slash.
      // Since the paths are sorted, they form a contiguous range.
      int end = i + 1;
      while (end < to && paths[end].regionMatches(0, path, 0, slash + 1)) {
        end++;
      }
      names.add(path.substring(prefixLength, slash));
      entries.add(create(paths, metadata, i, end, slash + 1));
      i = end;
    }
    return new TreeArtifactDirectory(packNames(names), entries.toArray());
  }

  private static byte[] packNames(List<String> names) {
    int nameBytes = 0;
    for (String name : names) {
      nameBytes += name.length();
    }
    int width = nameBytes <= 0xFFFF ? 2 : 4;
    int namesStart = width * names.size();
    byte[] packed = new byte[namesStart + nameBytes];
    int pos = namesStart;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      for (int j = 0; j < name.length(); j++) {
        packed[pos++] = (byte) name.charAt(j);
      }
      int offset = pos - namesStart;
      int offsetPos = i * width;
      if (width == 4) {
        packed[offsetPos++] = (byte) (offset >>> 24);
        packed[offsetPos++] = (byte) (offset >>> 16);
      }
      packed[offsetPos++] = (byte) (offset >>> 8);
      packed[offsetPos] = (byte) offset;
    }
    return packed;
  }

  /**
   * Returns the width of the offsets in {@link #names}, which is 2 if the names take up at most
   * 0xFFFF bytes and 4 otherwise. This can be recovered from the length of {@link #names}: with
   * 4-byte offsets, the length minus two bytes per entry always exceeds 0xFFFF.
   */
  private int offsetWidth() {
    return names.length - 2 * entries.length <= 0xFFFF ? 2 : 4;
  }

  /** Returns the index in {@link #names} at which the name of the given entry ends. */
  private int nameEnd(int index, int width) {
    int pos = index * width;
    int offset =
        width == 2
            ? ((names[pos] & 0xFF) << 8) | (names[pos + 1] & 0xFF)
            : ((names[pos] & 0xFF) << 24)
                | ((names[pos + 1] & 0xFF) << 16)
                | ((names[pos + 2] & 0xFF) << 8)
                | (names[pos + 3] & 0xFF);
    return width * entries.length + offset;
  }

  /** Returns the index in {@link #names} at which the name of the given entry starts. */
  private int nameStart(int index, int width) {
    return index == 0 ? width * entries.length : nameEnd(index - 1, width);
  }

  private void appendName(StringBuilder builder, int index, int width) {
    for (int i = nameStart(index, width), end = nameEnd(index, width); i < end; i++) {
      builder.append((char) (names[i] & 0xFF));
    }
  }

  /**
   * Returns the metadata of the file with the given path relative to this directory, or {@code
   * null} if there is no such file.
   *
   * @param path a string that contains the relative path starting at index {@code start}
   */
  @Nullable
  FileArtifactValue getFile(String path, int start) {
    TreeArtifactDirectory directory = this;
    while (true) {
      int slash = path.indexOf('/', start);
      boolean isDirectory = slash != -1;
      int end = isDirectory ? slash : path.length();
      if (end == start) {
        return null;
      }
      int index = directory.find(path, start, end, isDirectory);
      if (index < 0) {
        return null;
      }
      if (!isDirectory) {
        return (FileArtifactValue) directory.entries[index];
      }
      directory = (TreeArtifactDirectory) directory.entries[index];
      start = slash + 1;
    }
  }

  /**
   * Returns the index of the entry with the name {@code path.substring(start, end)} that is a
   * directory if and only if {@code isDirectory} is true, or a negative value if there is no such
   * entry.
   */
  private int find(String path, int start, int end, boolean isDirectory) {
    int width = offsetWidth();
    int low = 0;
    int high = entries.length - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      int cmp = compareEntry(mid, width, path, start, end, isDirectory);
      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid;
      }
    }
    return -1;
  }

  /**
   * Compares the sort key of the given entry (its name, followed by a '/' if it is a directory) to
   * {@code path.substring(start, end)}, followed by a '/' if {@code isDirectory} is true.
   */
  private int compareEntry(
      int index, int width, String path, int start, int end, boolean isDirectory) {
    int nameStart = nameStart(index, width);
    int nameLength = nameEnd(index, width) - nameStart;
    int keyLength = nameLength + (entries[index] instanceof TreeArtifactDirectory ? 1 : 0);
    int queryLength = end - start;
    int queryKeyLength = queryLength + (isDirectory ? 1 : 0);
    int commonLength = Math.min(keyLength, queryKeyLength);
    for (int i = 0; i < commonLength; i++) {
      int keyChar = i < nameLength ? names[nameStart + i] & 0xFF : '/';
      int queryChar = i < queryLength ? path.charAt(start + i) : '/';
      if (keyChar != queryChar) {
        return keyChar - queryChar;
      }
    }
    return keyLength - queryKeyLength;
  }

  /**
   * Calls {@code consumer} with the path relative to this directory and the metadata of every file,
   * in exec path order.
   */
  void forEachFile(BiConsumer<String, FileArtifactValue> consumer) {
    forEachFile(new StringBuilder(), consumer);
  }

  private void forEachFile(StringBuilder path, BiConsumer<String, FileArtifactValue> consumer) {
    int width = offsetWidth();
    int prefixLength = path.length();
    for (int i = 0; i < entries.length; i++) {
      appendName(path, i, width);
      if (entries[i] instanceof TreeArtifactDirectory directory) {
        path.append('/');
        directory.forEachFile(path, consumer);
      } else {
        consumer.accept(path.toString(), (FileArtifactValue) entries[i]);
      }
      path.setLength(prefixLength);
    }
  }

  /** Returns whether the metadata of at least one file satisfies the given predicate. */
  boolean anyFileMatches(Predicate<? super FileArtifactValue> predicate) {
    for (Object entry : entries) {
      if (entry instanceof TreeArtifactDirectory directory
          ? directory.anyFileMatches(predicate)
          : predicate.test((FileArtifactValue) entry)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the number of files in this directory and its subdirectories. */
  int getFileCount() {
    int count = 0;
    for (Object entry : entries) {
      count += entry instanceof TreeArtifactDirectory directory ? directory.getFileCount() : 1;
    }
    return count;
  }

  /** Returns the path relative to this directory of the first file in exec path order. */
  String getFirstFilePath() {
    StringBuilder path = new StringBuilder();
    TreeArtifactDirectory directory = this;
    while (true) {
      directory.appendName(path, 0, directory.offsetWidth());
      if (!(directory.entries[0] instanceof TreeArtifactDirectory subdirectory)) {
        return path.toString();
      }
      path.append('/');
      directory = subdirectory;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof TreeArtifactDirectory that
        && Arrays.equals(names, that.names)
        && Arrays.equals(entries, that.entries);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(names) + Arrays.hashCode(entries);
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("TreeArtifactDirectory{");
    forEachFile(
        (path, metadata) -> {
          if (builder.charAt(builder.length() - 1) != '{') {
            builder.append(", ");
          }
          builder.append(path).append('=').append(metadata);
        });
    return builder.append('}').toString();
  }
}
