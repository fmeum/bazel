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

import static com.google.devtools.build.lib.vfs.FileSystem.translateNioToIoException;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadSafe;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.StringEncoding;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/** Helper functions that implement often-used complex operations on file systems. */
@ConditionallyThreadSafe
public class FileSystemUtils {

  private FileSystemUtils() {}

  /**
   * Throws exceptions if {@code baseName} is not a valid base name. A valid
   * base name:
   * <ul>
   * <li>Is not null
   * <li>Is not an empty string
   * <li>Is not "." or ".."
   * <li>Does not contain a slash
   * </ul>
   */
  @ThreadSafe
  public static void checkBaseName(String baseName) {
    if (baseName.length() == 0) {
      throw new IllegalArgumentException("Child must not be empty string ('')");
    }
    if (baseName.equals(".") || baseName.equals("..")) {
      throw new IllegalArgumentException("baseName must not be '" + baseName + "'");
    }
    if (baseName.indexOf('/') != -1) {
      throw new IllegalArgumentException("baseName must not contain a slash: '" + baseName + "'");
    }
  }

  /**
   * Returns the common ancestor between two paths, or null if none (including if they are on
   * different filesystems).
   */
  @Nullable
  public static Path commonAncestor(Path a, Path b) {
    if (!a.getFileSystem().equals(b.getFileSystem())) {
      return null;
    }
    PathFragment ancestor = commonAncestor(a.asFragment(), b.asFragment());
    if (ancestor == null) {
      return null;
    }
    if (ancestor.equals(a.asFragment())) {
      return a;
    }
    if (ancestor.equals(b.asFragment())) {
      return b;
    }
    return a.getFileSystem().getPath(ancestor);
  }

  /**
   * Returns the longest common ancestor of the two path fragments, or either "/" or "" (depending
   * on whether {@code a} is absolute or relative) if there is none.
   */
  @Nullable
  public static PathFragment commonAncestor(PathFragment a, PathFragment b) {
    if (a.getDriveStrLength() != b.getDriveStrLength()) {
      return null;
    }
    int driveLen = a.getDriveStrLength();
    int k = a.getCommonPrefixLength(b);
    if (k < driveLen) {
      return null;
    }
    String sA = a.getPathString();
    String sB = b.getPathString();
    if (k == sA.length() && (k == sB.length() || k == driveLen || sB.charAt(k) == '/')) {
      return a;
    }
    if (k == sB.length() && (k == driveLen || sA.charAt(k) == '/')) {
      return b;
    }
    int lastSlash = sA.lastIndexOf('/', k - 1);
    if (lastSlash < driveLen) {
      return driveLen == 0
          ? PathFragment.EMPTY_FRAGMENT
          : PathFragment.createAlreadyNormalized(sA.substring(0, driveLen));
    }
    return PathFragment.createAlreadyNormalized(sA.substring(0, lastSlash));
  }

  /**
   * Returns a path fragment from a given from-dir to a given to-path.
   */
  public static PathFragment relativePath(PathFragment fromDir, PathFragment to) {
    if (to.equals(fromDir)) {
      return PathFragment.EMPTY_FRAGMENT;
    }
    if (to.startsWith(fromDir)) {
      return to.relativeTo(fromDir);  // easy case--it's a descendant
    }
    PathFragment ancestor = commonAncestor(fromDir, to);
    if (ancestor == null) {
      return to;  // no common ancestor, use 'to'
    }
    int levels = fromDir.relativeTo(ancestor).segmentCount();
    StringBuilder dotdots = new StringBuilder();
    for (int i = 0; i < levels; i++) {
      dotdots.append("../");
    }
    return PathFragment.create(dotdots.toString()).getRelative(to.relativeTo(ancestor));
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * filename string. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the filename is composed entirely of ".", this method will return the string
   * with one fewer ".", which may have surprising effects.
   */
  @ThreadSafe
  public static String removeExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex == -1) { return filename; }
    int lastSlashIndex = filename.lastIndexOf('/');
    if (lastSlashIndex > lastDotIndex) {
      return filename;
    }
    return filename.substring(0, lastDotIndex);
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * PathFragment. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the base filename is composed entirely of ".", this method will return the
   * filename with one fewer "." in the base filename, which may have surprising effects.
   */
  @ThreadSafe
  public static PathFragment removeExtension(PathFragment path) {
    return path.replaceName(removeExtension(path.getBaseName()));
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * Path. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the base filename is composed entirely of ".", this method will return the
   * filename with one fewer "." in the base filename, which may have surprising effects.
   */
  @ThreadSafe
  public static Path removeExtension(Path path) {
    return path.getFileSystem().getPath(removeExtension(path.asFragment()));
  }

  /**
   * Returns a new {@code PathFragment} formed by replacing the extension of the
   * last path segment of {@code path} with {@code newExtension}. Null is
   * returned iff {@code path} has zero segments.
   */
  public static PathFragment replaceExtension(PathFragment path, String newExtension) {
    return path.replaceName(removeExtension(path.getBaseName()) + newExtension);
  }

  /**
   * Returns a new {@code PathFragment} formed by replacing the extension of the last path segment
   * of {@code path} with {@code newExtension}. Null is returned iff {@code path} has zero segments
   * or it doesn't end with {@code oldExtension}.
   */
  @Nullable
  public static PathFragment replaceExtension(
      PathFragment path, String newExtension, String oldExtension) {
    String base = path.getBaseName();
    if (!base.endsWith(oldExtension)) {
      return null;
    }
    String newBase = base.substring(0, base.length() - oldExtension.length()) + newExtension;
    return path.replaceName(newBase);
  }

  /**
   * Returns a new {@code Path} formed by replacing the extension of the last path segment of {@code
   * path} with {@code newExtension}. Null is returned iff {@code path} has zero segments.
   */
  @Nullable
  public static Path replaceExtension(Path path, String newExtension) {
    PathFragment fragment = replaceExtension(path.asFragment(), newExtension);
    return fragment == null ? null : path.getFileSystem().getPath(fragment);
  }

  /**
   * Returns a new {@code PathFragment} formed by adding the extension to the last path segment of
   * {@code path}. Null is returned if {@code path} has zero segments.
   */
  public static PathFragment appendExtension(PathFragment path, String newExtension) {
    return path.replaceName(path.getBaseName() + newExtension);
  }

  /**
   * Returns a new {@code PathFragment} formed by appending the given string to the last path
   * segment of {@code path} without removing the extension.  Returns null if {@code path}
   * has zero segments.
   */
  public static PathFragment appendWithoutExtension(PathFragment path, String toAppend) {
    return path.replaceName(appendWithoutExtension(path.getBaseName(), toAppend));
  }

  /**
   * Given a string that represents a file with an extension separated by a '.' and a string
   * to append, return a string in which {@code toAppend} has been appended to {@code name}
   * before the last '.' character.  If {@code name} does not include a '.', appends {@code
   * toAppend} at the end.
   *
   * <p>For example,
   * ("libfoo.jar", "-src") ==> "libfoo-src.jar"
   * ("libfoo", "-src") ==> "libfoo-src"
   */
  private static String appendWithoutExtension(String name, String toAppend) {
    int dotIndex = name.lastIndexOf('.');
    if (dotIndex > 0) {
      String baseName = name.substring(0, dotIndex);
      String extension = name.substring(dotIndex);
      return baseName + toAppend + extension;
    } else {
      return name + toAppend;
    }
  }

  /**
   * Return the current working directory as expressed by the System property
   * 'user.dir'.
   */
  public static Path getWorkingDirectory(FileSystem fs) {
    return fs.getPath(getWorkingDirectory());
  }

  /**
   * Returns the current working directory as expressed by the System property
   * 'user.dir'. This version does not require a {@link FileSystem}.
   */
  public static PathFragment getWorkingDirectory() {
    // System properties obtained from host are encoded using sun.jnu.encoding, so reencode them to
    // the internal representation.
    // https://github.com/openjdk/jdk/blob/285385247aaa262866697ed848040f05f4d94988/src/java.base/share/native/libjava/System.c#L121
    return PathFragment.create(
        StringEncoding.platformToInternal(System.getProperty("user.dir", "/")));
  }

  /**
   * "Touches" the file or directory specified by the path, following symbolic
   * links. If it does not exist, it is created as an empty file; otherwise, the
   * time of last access is updated to the current time.
   *
   * @throws IOException if there was an error while touching the file
   */
  @ThreadSafe
  public static void touchFile(Path path) throws IOException {
    if (path.exists()) {
      path.setLastModifiedTime(Path.NOW_SENTINEL_TIME);
    } else {
      createEmptyFile(path);
    }
  }

  /**
   * Creates an empty regular file with the name of the current path, following
   * symbolic links.
   *
   * @throws IOException if the file could not be created for any reason
   *         (including that there was already a file at that location)
   */
  public static void createEmptyFile(Path path) throws IOException {
    path.getOutputStream().close();
  }

  /**
   * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
   * directories of 'link' will also be created.
   *
   * <p>This operation is not atomic.
   *
   * @throws NotASymlinkException if the path already exists and is not a symbolic link
   * @throws IOException if creating the symbolic link or its ancestor directories failed for any
   *     other reason
   */
  @ThreadSafe // but not atomic
  public static void ensureSymbolicLink(Path link, Path target) throws IOException {
    ensureSymbolicLink(link, target.asFragment());
  }

  /**
   * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
   * directories of 'link' will also be created.
   *
   * <p>This operation is not atomic.
   *
   * @throws NotASymlinkException if the path already exists and is not a symbolic link
   * @throws IOException if creating the symbolic link or its ancestor directories failed for any
   *     other reason
   */
  @ThreadSafe // but not atomic
  public static void ensureSymbolicLink(Path link, String target) throws IOException {
    ensureSymbolicLink(link, PathFragment.create(target));
  }

  /**
   * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
   * directories of 'link' will also be created.
   *
   * <p>This operation is not atomic.
   *
   * @throws NotASymlinkException if the path already exists and is not a symbolic link
   * @throws IOException if creating the symbolic link or its ancestor directories failed for any
   *     other reason
   */
  @ThreadSafe // but not atomic
  public static void ensureSymbolicLink(Path link, PathFragment target) throws IOException {
    // TODO(bazel-team): (2009) consider adding the logic for recovering from the case when
    // we have already created a parent directory symlink earlier.
    boolean parentKnownToExist = false;
    try {
      // This will throw if the path already exists and is not a symbolic link.
      if (link.readSymbolicLink().equals(target)) {
        // Nothing to do.
        return;
      }
      // The symlink exists, but points elsewhere.
      link.delete();
      parentKnownToExist = true;
    } catch (FileNotFoundException e) {
      // Path does not exist; fall through.
    }
    if (!parentKnownToExist) {
      link.getParentDirectory().createDirectoryAndParents();
    }
    link.createSymbolicLink(target);
  }

  public static ByteSource asByteSource(final Path path) {
    return new ByteSource() {
      @Override public InputStream openStream() throws IOException {
        return path.getInputStream();
      }
    };
  }

  public static ByteSink asByteSink(final Path path, final boolean append) {
    return new ByteSink() {
      @Override public OutputStream openStream() throws IOException {
        return path.getOutputStream(append);
      }
    };
  }

  public static ByteSink asByteSink(final Path path) {
    return asByteSink(path, false);
  }

  /**
   * Copies a file, potentially overwriting the destination. Preserves the modification time and
   * permissions.
   *
   * <p>See {@link #copyToNewFile} for a copy that gets the attributes of a newly created file
   * instead.
   *
   * <p>If the source is a symbolic link, it will be followed. If the destination is a symbolic
   * link, it will be replaced.
   *
   * <p>Copying directories is not supported.
   *
   * @param from the source path
   * @param to the destination path
   * @throws FileNotFoundException if the source does not exist, or the parent directory of the
   *     destination does not exist
   * @throws IOException if the copy fails for any other reason
   */
  @ThreadSafe // but not atomic
  public static void copyFile(Path from, Path to) throws IOException {
    copyFile(from, to, from.stat());
  }

  private static void copyFile(Path from, Path to, FileStatus stat) throws IOException {
    if (!stat.isFile()) {
      throw new IOException("don't know how to copy " + from);
    }
    var fromNio = from.getFileSystem().getNioPath(from.asFragment());
    var toNio = to.getFileSystem().getNioPath(to.asFragment());
    if (fromNio != null && toNio != null) {
      // Fast path: Files.copy uses various optimizations such as kernel buffers (sendfile on Unix)
      // or copy-on-write (clonefile on macOS, copy_file_range on Linux with a supported file
      // system).
      try {
        Files.copy(fromNio, toNio, REPLACE_EXISTING, COPY_ATTRIBUTES);
      } catch (IOException e) {
        throw translateNioToIoException(from.asFragment(), e);
      }
      return;
    }
    // Target may be a symlink, in which case we should not follow it.
    to.delete();
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      // This may use a faster copy method (such as via an in-kernel buffer) if both streams are
      // backed by files.
      in.transferTo(out);
    }
    to.setLastModifiedTime(stat.getLastModifiedTime());
    int perms = stat.getPermissions();
    if (perms != -1) {
      to.chmod(perms);
    } else {
      to.setReadable(from.isReadable());
      to.setWritable(from.isWritable());
      to.setExecutable(from.isExecutable());
    }
  }

  /**
   * Copies a file into a newly created file, potentially replacing the destination.
   *
   * <p>Unlike {@link #copyFile}, this doesn't preserve the modification time and permissions of the
   * source. Instead, just like a file written through a stream, the copy gets the current
   * modification time as well as the permissions, owner and group (on Windows, the owner, ACL and
   * file attributes) that a newly created file gets from the umask and its directory (for example,
   * through a default ACL or the setgid bit). In particular, the copy is never executable on Unix.
   *
   * <p>This can't replace {@link #copyFile}, whose callers mostly need the copy to be a stand-in
   * for the source: for example, a tool copied into a sandbox must remain executable, and {@link
   * #moveFile} falls back to a copy to emulate a rename across filesystems. This method is meant
   * for a copy that is a file in its own right, such as a disk cache entry, which must be
   * accessible like any other file in the cache regardless of the file it was copied from.
   *
   * <p>The contents are copied in a way that lets a filesystem with copy-on-write support serve the
   * copy as a clone, leaving it sharing its blocks with the source: through copy_file_range on
   * Linux, clonefile on macOS and CopyFileEx on Windows (which clones on a Dev Drive as of Windows
   * 11 24H2).
   *
   * <p>If the source is a symbolic link, it will be followed. If the destination is a symbolic
   * link, it will be replaced.
   *
   * <p>Copying directories is not supported.
   *
   * @param from the source path
   * @param to the destination path
   * @throws FileNotFoundException if the source does not exist, or the parent directory of the
   *     destination does not exist
   * @throws IOException if the copy fails for any other reason
   */
  @ThreadSafe // but not atomic
  public static void copyToNewFile(Path from, Path to) throws IOException {
    if (!from.stat().isFile()) {
      throw new IOException("don't know how to copy " + from);
    }
    // The destination must be a new file, and if it is a symlink, it must not be followed.
    to.delete();
    boolean copied =
        switch (OS.getCurrent()) {
          case DARWIN -> copyToNewFileOnMacOs(from, to);
          case WINDOWS -> copyToNewFileOnWindows(from, to);
          default -> false;
        };
    if (copied) {
      return;
    }
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      if (in instanceof FileInputStream fileIn && out instanceof FileOutputStream fileOut) {
        // A transfer between file channels uses copy_file_range on Linux.
        transferFully(fileIn.getChannel(), fileOut.getChannel());
      } else {
        in.transferTo(out);
      }
    }
  }

  /**
   * Copies a file into a newly created file on macOS, giving the copy the attributes described in
   * {@link #copyToNewFile}.
   *
   * <p>{@link Files#copy} only clones a file when asked to copy its attributes, so that the clone
   * takes its permissions, owner and group from the source. They are reset to those of the file as
   * created empty beforehand. (The extended attributes, which clonefile copies from the source as
   * well, are left as they are, as is the ACL, which recent macOS versions inherit from the
   * directory rather than copy from the source.)
   *
   * @return whether the copy was created; otherwise, the destination doesn't exist
   */
  private static boolean copyToNewFileOnMacOs(Path from, Path to) throws IOException {
    var toNio = to.getFileSystem().getNioPath(to.asFragment());
    if (toNio == null) {
      return false;
    }
    createEmptyFile(to);
    var view = Files.getFileAttributeView(toNio, PosixFileAttributeView.class);
    var newFileAttributes = view.readAttributes();
    copyFile(from, to);
    var cloneAttributes = view.readAttributes();
    try {
      if (!cloneAttributes.owner().equals(newFileAttributes.owner())) {
        view.setOwner(newFileAttributes.owner());
      }
      if (!cloneAttributes.group().equals(newFileAttributes.group())) {
        view.setGroup(newFileAttributes.group());
      }
    } catch (IOException e) {
      // A new file gets the group of its directory, but an unprivileged user can only give a file a
      // group they are a member of. Fall back to a copy, which gets the group of the directory too.
      to.delete();
      return false;
    }
    view.setPermissions(newFileAttributes.permissions());
    to.setLastModifiedTime(Path.NOW_SENTINEL_TIME);
    return true;
  }

  /**
   * Copies a file into a newly created file on Windows, giving the copy the attributes described in
   * {@link #copyToNewFile}.
   *
   * <p>{@link Files#copy} uses CopyFileEx, which doesn't copy the owner and ACL of the source
   * unless asked to copy its attributes, so that the copy gets them from its directory just like a
   * new file. It does copy its file attributes and mtime, though, which are reset to those of a new
   * file. (The alternate data streams, which CopyFileEx copies from the source as well, are left as
   * they are.)
   *
   * @return whether the copy was created; otherwise, the destination doesn't exist
   */
  private static boolean copyToNewFileOnWindows(Path from, Path to) throws IOException {
    var fromNio = from.getFileSystem().getNioPath(from.asFragment());
    var toNio = to.getFileSystem().getNioPath(to.asFragment());
    if (fromNio == null || toNio == null) {
      return false;
    }
    try {
      Files.copy(fromNio, toNio, REPLACE_EXISTING);
    } catch (IOException e) {
      throw translateNioToIoException(from.asFragment(), e);
    }
    // A new file is neither read-only, nor hidden, nor a system file.
    var view = Files.getFileAttributeView(toNio, DosFileAttributeView.class);
    var copyAttributes = view.readAttributes();
    if (copyAttributes.isReadOnly()) {
      view.setReadOnly(false);
    }
    if (copyAttributes.isHidden()) {
      view.setHidden(false);
    }
    if (copyAttributes.isSystem()) {
      view.setSystem(false);
    }
    to.setLastModifiedTime(Path.NOW_SENTINEL_TIME);
    return true;
  }

  /**
   * Transfers the contents of a file channel to another file channel.
   *
   * <p>Unlike {@link FileInputStream#transferTo}, this doesn't fall back to a copy through a buffer
   * for the part of a file that exceeds the maximum size of a single transfer (about 2 GiB on
   * Linux).
   */
  private static void transferFully(FileChannel source, FileChannel target) throws IOException {
    long position = 0;
    long transferred;
    while ((transferred = source.transferTo(position, Long.MAX_VALUE, target)) > 0) {
      position += transferred;
    }
  }

  /** Describes the behavior of a {@link #moveFile(Path, Path)} operation. */
  public enum MoveResult {
    /** The file was moved at the file system level. */
    FILE_MOVED,

    /** The file had to be copied and then deleted because the move failed. */
    FILE_COPIED,
  }

  /**
   * Moves a file or symbolic link, potentially overwriting the destination. Does not follow
   * symbolic links.
   *
   * <p>This method is not guaranteed to be atomic. Use {@link Path#renameTo(Path)} instead.
   *
   * <p>If the move fails (usually because the source and destination are in different filesystems),
   * falls back to copying the file, preserving its permissions and modification time. Note that the
   * fallback has very different performance characteristics, which is why this method reports what
   * actually happened back to the caller.
   *
   * @param from the source path
   * @param to the destination path
   * @return a description of how the move was performed
   * @throws FileNotFoundException if the source does not exist, or the parent directory of the
   *     destination does not exit
   * @throws IOException if the move fails for any other reason
   */
  @ThreadSafe // but not atomic
  public static MoveResult moveFile(Path from, Path to) throws IOException {
    try {
      from.renameTo(to);
      return MoveResult.FILE_MOVED;
    } catch (IOException ignored) {
      // Fallback to a copy.
      FileStatus stat = from.stat(Symlinks.NOFOLLOW);
      if (stat.isFile()) {
        copyFile(from, to, stat);
      } else if (stat.isSymbolicLink()) {
        PathFragment targetPath = from.readSymbolicLink();
        try {
          to.createSymbolicLink(targetPath);
        } catch (IOException ignored2) {
          // May have failed due the target file existing, but not being a symlink.
          // TODO: Only catch FileAlreadyExistsException once we throw that.
          to.delete();
          to.createSymbolicLink(targetPath);
        }
      } else {
        // TODO(tjgq): The move/copy cases should have a consistent result for a directory.
        throw new IOException("Don't know how to move " + from, ignored);
      }
      try {
        from.delete();
      } catch (IOException e) {
        // If we fail to delete the source, then delete the destination.
        try {
          to.delete();
        } catch (IOException e2) {
          e.addSuppressed(e2);
        }
        throw e;
      }
      return MoveResult.FILE_COPIED;
    }
  }

  /**
   * Atomically renames a source file to a target file, tolerating the case where another thread has
   * concurrently created the target file (e.g. because it is known to have the same content in a
   * CAS-like structure).
   *
   * <p>This handles a Windows-specific edge case: when the target file is being read by another
   * process (e.g., during a concurrent cache lookup), the rename operation fails with a {@link
   * FileAccessException}. If the target file already exists when this happens, it means another
   * thread won the race to create it, so we can safely delete the source file.
   *
   * <p>The parent directories of the target file must already exist.
   *
   * @param source the file to rename
   * @param target the destination path
   */
  @ThreadSafe
  public static void renameToleratingConcurrentCreation(Path source, Path target)
      throws IOException {
    try {
      source.renameTo(target);
    } catch (FileAccessException e) {
      // On Windows, atomically replacing a file that is currently opened (e.g. due to a concurrent
      // get on the cache) results in renameTo throwing this exception, which wraps an
      // AccessDeniedException. This case is benign since if the target path already exists, we know
      // that another thread won the race to place the file in the cache. As the exception is rather
      // generic and could result from other failure types, we rethrow the exception if the cache
      // entry hasn't been created.
      if (OS.getCurrent() != OS.WINDOWS || !target.exists()) {
        throw e;
      }
      source.delete();
    }
  }

  /* Directory tree operations. */

  /** Determines whether a tree traversal should visit a path. */
  @FunctionalInterface
  public interface TraverseTreePredicate {
    /** Returns whether a tree traversal should visit the given path. */
    boolean shouldTraverse(Path path) throws IOException;
  }

  /**
   * Returns a new collection containing all of the paths below a given root path, for which the
   * given predicate is true. Symbolic links are not followed, and may appear in the result.
   *
   * @throws IOException if the root does not denote a directory, or if the predicate throws an
   *     IOException
   */
  @ThreadSafe
  public static Collection<Path> traverseTree(Path root, TraverseTreePredicate predicate)
      throws IOException {
    List<Path> paths = new ArrayList<>();
    traverseTree(paths, root, predicate);
    return paths;
  }

  /**
   * Populates an existing Path List, adding all of the paths below a given root path for which the
   * given predicate is true. Symbolic links are not followed, and may appear in the result.
   *
   * @throws IOException If the root does not denote a directory
   */
  @ThreadSafe
  public static void traverseTree(
      Collection<Path> paths, Path root, TraverseTreePredicate predicate) throws IOException {
    for (Dirent dirent : root.readdir(Symlinks.NOFOLLOW)) {
      Path childPath = root.getChild(dirent.getName());
      if (predicate.shouldTraverse(childPath)) {
        paths.add(childPath);
      }
      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        traverseTree(paths, childPath, predicate);
      }
    }
  }

  /**
   * Copies all dir trees under a given 'from' dir to location 'to', while overwriting all files in
   * the potentially existing 'to'. Symlinks are copied as-is.
   *
   * <p>The source and the destination must be non-overlapping, otherwise an
   * IllegalArgumentException will be thrown. This method cannot be used to copy a dir tree to a sub
   * tree of itself.
   *
   * <p>If no error occurs, the method returns normally. If the given 'from' does not exist, a
   * FileNotFoundException is thrown. An IOException is thrown when other erroneous situations
   * occur. (e.g. read errors)
   */
  @ThreadSafe
  public static void copyTreesBelow(Path from, Path to) throws IOException {
    if (to.startsWith(from)) {
      throw new IllegalArgumentException(to + " is a subdirectory of " + from);
    }

    for (Dirent dirent : from.readdir(Symlinks.NOFOLLOW)) {
      Path fromChild = from.getChild(dirent.getName());
      Path toChild = to.getChild(dirent.getName());
      switch (dirent.getType()) {
        case FILE:
          copyFile(fromChild, toChild);
          break;
        case SYMLINK:
          FileSystemUtils.ensureSymbolicLink(toChild, fromChild.readSymbolicLink());
          break;
        case DIRECTORY:
          toChild.createDirectory();
          copyTreesBelow(fromChild, toChild);
          break;
        default:
          throw new IOException("Don't know how to copy " + fromChild);
      }
    }
  }

  /**
   * Moves all dir trees under a given 'from' dir to location 'to', while overwriting
   * all files in the potentially existing 'to'. Doesn't resolve symbolic links.
   *
   * <p>The source and the destination must be non-overlapping, otherwise an
   * IllegalArgumentException will be thrown. This method cannot be used to copy
   * a dir tree to a sub tree of itself.
   *
   * <p>If no error occurs, the method returns normally. If the given 'from' does
   * not exist, a FileNotFoundException is thrown. An IOException is thrown when
   * other erroneous situations occur. (e.g. read errors)
   */
  @ThreadSafe
  public static void moveTreesBelow(Path from , Path to) throws IOException {
    if (to.startsWith(from)) {
      throw new IllegalArgumentException(to + " is a subdirectory of " + from);
    }

    // Actions can make output directories inaccessible, which would cause the move to fail.
    from.chmod(0755);

    // TODO(tjgq): Don't leave an empty directory behind.
    Collection<Path> entries = from.getDirectoryEntries();
    for (Path entry : entries) {
      if (entry.isDirectory(Symlinks.NOFOLLOW)) {
        Path subDir = to.getChild(entry.getBaseName());
        subDir.createDirectory();
        moveTreesBelow(entry, subDir);
      } else {
        Path newEntry = to.getChild(entry.getBaseName());
        moveFile(entry, newEntry);
      }
    }
  }

  /**
   * Attempts to remove a relative chain of directories under a given base. Returns {@code true} if
   * the removal was successful, and returns {@code false} if the removal fails because a directory
   * was not empty. An {@link IOException} is thrown for any other errors.
   */
  @ThreadSafe
  public static boolean removeDirectoryAndParents(Path base, PathFragment toRemove) {
    if (toRemove.isAbsolute()) {
      return false;
    }
    try {
      while (!toRemove.isEmpty()) {
        Path p = base.getRelative(toRemove);
        if (p.exists()) {
          p.delete();
        }
        toRemove = toRemove.getParentDirectory();
      }
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  /**
   * Decodes the given byte array assumed to be encoded with ISO-8859-1 encoding (isolatin1).
   */
  public static char[] convertFromLatin1(byte[] content) {
    char[] latin1 = new char[content.length];
    for (int i = 0; i < latin1.length; i++) { // yeah, latin1 is this easy! :-)
      latin1[i] = (char) (0xff & content[i]);
    }
    return latin1;
  }

  /**
   * Writes lines to file using ISO-8859-1 encoding (isolatin1).
   */
  @ThreadSafe // but not atomic
  public static void writeIsoLatin1(Path file, String... lines) throws IOException {
    writeLinesAs(file, ISO_8859_1, lines);
  }

  /**
   * Append lines to file using ISO-8859-1 encoding (isolatin1).
   */
  @ThreadSafe // but not atomic
  public static void appendIsoLatin1(Path file, String... lines) throws IOException {
    appendLinesAs(file, ISO_8859_1, lines);
  }

  /**
   * Writes the specified String as ISO-8859-1 (latin1) encoded bytes to the
   * file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContentAsLatin1(Path outputFile, String content) throws IOException {
    writeContent(outputFile, ISO_8859_1, content);
  }

  /**
   * Writes the specified String using the specified encoding to the file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContent(Path outputFile, Charset charset, String content)
      throws IOException {
    asByteSink(outputFile).asCharSink(charset).write(content);
  }

  /**
   * Writes the specified byte array to the output file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContent(Path outputFile, byte[] content) throws IOException {
    asByteSink(outputFile).write(content);
  }

  /** Writes lines to file using the given encoding, ending every line with '\n'. */
  @ThreadSafe // but not atomic
  public static void writeLinesAs(Path file, Charset charset, String... lines) throws IOException {
    writeLinesAs(file, charset, Arrays.asList(lines));
  }

  /** Writes lines to file using the given encoding, ending every line with '\n'. */
  @ThreadSafe // but not atomic
  public static void writeLinesAs(Path file, Charset charset, Iterable<String> lines)
      throws IOException {
    file.getParentDirectory().createDirectoryAndParents();
    asByteSink(file).asCharSink(charset).writeLines(lines, "\n");
  }

  /** Appends lines to file using the given encoding, ending every line with '\n'. */
  @ThreadSafe // but not atomic
  public static void appendLinesAs(Path file, Charset charset, String... lines) throws IOException {
    file.getParentDirectory().createDirectoryAndParents();
    asByteSink(file, true).asCharSink(charset).writeLines(Arrays.asList(lines), "\n");
  }

  /**
   * Updates the contents of the output file if they do not match the given array, thus maintaining
   * the mtime and ctime in case of no updates. Follows symbolic links.
   *
   * <p>If the output file already exists but is unreadable, this tries to overwrite it with the new
   * contents. In other words: unreadable or missing files are considered to be non-matching.
   *
   * @throws IOException if there was an error
   */
  public static void maybeUpdateContent(Path outputFile, byte[] newContent) throws IOException {
    byte[] currentContent;
    try {
      currentContent = readContent(outputFile);
    } catch (IOException e) {
      // Ignore error per the rationale given in the docstring. Keep in mind that what we are doing
      // here is for performance reasons only so we should only break if the real action (that is,
      // the write) fails -- not any of the optimization steps.
      currentContent = null;
    }

    if (currentContent == null) {
      writeContent(outputFile, newContent);
    } else {
      if (!Arrays.equals(newContent, currentContent)) {
        if (!outputFile.isWritable()) {
          outputFile.delete();
        }
        writeContent(outputFile, newContent);
      }
    }
  }

  /**
   * Returns the entirety of the specified input stream and returns it as a char
   * array, decoding characters using ISO-8859-1 (Latin1).
   *
   * @throws IOException if there was an error
   */
  public static char[] readContentAsLatin1(InputStream in) throws IOException {
    return convertFromLatin1(ByteStreams.toByteArray(in));
  }

  /**
   * Returns the entirety of the specified file and returns it as a char array,
   * decoding characters using ISO-8859-1 (Latin1).
   *
   * @throws IOException if there was an error
   */
  public static char[] readContentAsLatin1(Path inputFile) throws IOException {
    return convertFromLatin1(readContent(inputFile));
  }

  /**
   * Returns a list of the lines in an ISO-8859-1 (Latin1) text file. If the file ends in a line
   * break, the list will contain an empty string as the last element.
   *
   * @throws IOException if there was an error
   */
  public static ImmutableList<String> readLinesAsLatin1(Path inputFile) throws IOException {
    return readLines(inputFile, ISO_8859_1);
  }

  /**
   * Returns a list of the lines in a text file in the given {@link Charset}. If the file ends in a
   * line break, the list will contain an empty string as the last element.
   *
   * @throws IOException if there was an error
   */
  public static ImmutableList<String> readLines(Path inputFile, Charset charset)
      throws IOException {
    return asByteSource(inputFile).asCharSource(charset).readLines();
  }

  /**
   * Returns the entirety of the specified file and returns it as a byte array.
   *
   * @throws IOException if there was an error
   */
  public static byte[] readContent(Path inputFile) throws IOException {
    return asByteSource(inputFile).read();
  }

  /**
   * Reads the entire file using the given charset and returns the contents as a string
   */
  public static String readContent(Path inputFile, Charset charset) throws IOException {
    return asByteSource(inputFile).asCharSource(charset).read();
  }

  /**
   * The type of {@link IOException} thrown by {@link #readWithKnownFileSize} when fewer bytes than
   * expected are read.
   */
  public static class ShortReadIOException extends IOException {
    public final Path path;
    public final int fileSize;
    public final int numBytesRead;

    private ShortReadIOException(Path path, int fileSize, int numBytesRead) {
      super("Unexpected short read from file '" + path + "' (expected " + fileSize + ", got "
          + numBytesRead + " bytes)");
      this.path = path;
      this.fileSize = fileSize;
      this.numBytesRead = numBytesRead;
    }
  }

  /**
   * The type of {@link IOException} thrown by {@link #readWithKnownFileSize} when more bytes than
   * expected could be read.
   */
  public static class LongReadIOException extends IOException {
    public final Path path;
    public final int fileSize;

    private LongReadIOException(Path path, int fileSize) {
      super("File '" + path + "' is unexpectedly longer than " + fileSize + " bytes)");
      this.path = path;
      this.fileSize = fileSize;
    }
  }

  /**
   * Reads the given file {@code path}, assumed to have size {@code fileSize}, and does a check on
   * the number of bytes read.
   *
   * <p>Use this method when you already know the size of the file. The check is intended to catch
   * issues where the filesystem incorrectly returns truncated file contents, or where an external
   * modification has concurrently truncated or appended to the file.
   *
   * @throws IOException if there was an error, or if fewer than {@code fileSize} bytes were read.
   */
  public static byte[] readWithKnownFileSize(Path path, long fileSize) throws IOException {
    Preconditions.checkArgument(fileSize >= 0, "fileSize needs to be >=0, but it is %s", fileSize);
    if (fileSize > Integer.MAX_VALUE) {
      throw new IOException("Cannot read file with size larger than 2GB");
    }
    int size = (int) fileSize;
    byte[] bytes = new byte[size];
    try (InputStream in = asByteSource(path).openBufferedStream()) {
      int read = ByteStreams.read(in, bytes, 0, size);
      if (read != size) {
        throw new ShortReadIOException(path, size, read);
      }
      int eof = in.read();
      if (eof != -1) {
        throw new LongReadIOException(path, size);
      }
    }
    return bytes;
  }

  /**
   * Returns the type of the file system path belongs to.
   */
  public static String getFileSystem(Path path) {
    return path.getFileSystem().getFileSystemType(path.asFragment());
  }

  /** Returns whether the given path starts with any of the paths in the given list of prefixes. */
  public static boolean startsWithAny(Path path, Iterable<Path> prefixes) {
    for (Path prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether the given path starts with any of the paths in the given list of prefixes,
   * ignoring case.
   */
  public static boolean startsWithAnyIgnoringCase(Path path, Iterable<Path> prefixes) {
    for (Path prefix : prefixes) {
      if (path.startsWithIgnoringCase(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether the given path starts with any of the paths in the given list of prefixes. */
  public static boolean startsWithAny(PathFragment path, Iterable<PathFragment> prefixes) {
    for (PathFragment prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }


  /**
   * Create a new hard link file at "linkPath" for file at "originalPath". If "originalPath" is a
   * directory, then for each entry, create link under "linkPath" recursively.
   *
   * @param linkPath The path of the new link file to be created
   * @param originalPath The path of the original file
   * @throws IOException if there was an error executing {@link Path#createHardLink}
   */
  public static void createHardLink(Path linkPath, Path originalPath) throws IOException {

    // Directory
    if (originalPath.isDirectory()) {
      for (Path originalSubpath : originalPath.getDirectoryEntries()) {
        Path linkSubpath = linkPath.getRelative(originalSubpath.relativeTo(originalPath));
        createHardLink(linkSubpath, originalSubpath);
      }
      // Other types of file
    } else {
      Path parentDir = linkPath.getParentDirectory();
      if (!parentDir.exists()) {
        parentDir.createDirectoryAndParents();
      }
      originalPath.createHardLink(linkPath);
    }
  }
}
