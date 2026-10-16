// Copyright 2016 The Bazel Authors. All rights reserved.
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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.actions.cache.MetadataDigestUtils;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.NamedForkJoinPool;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Value for TreeArtifacts, which contains a digest and the {@link FileArtifactValue}s of its child
 * {@link TreeFileArtifact}s.
 *
 * <p>The children of declared directories are stored in a {@link TreeArtifactDirectory}, from which
 * their {@link TreeFileArtifact}s are created on demand. This avoids retaining an artifact and its
 * full exec path for every child, which would otherwise dominate the memory usage of large tree
 * artifacts.
 */
@AutoCodec(
    deserializedInterface = DeserializedSkyValue.class,
    // Do not auto-register; see TreeArtifactValueCodec
    autoRegister = false)
public class TreeArtifactValue implements HasDigest, SkyValue {
  private static final ForkJoinPool VISITOR_POOL =
      NamedForkJoinPool.newNamedPool(
          "tree-artifact-visitor", Runtime.getRuntime().availableProcessors());

  /**
   * Comparator based on exec path which works on {@link ActionInput} as opposed to {@link
   * com.google.devtools.build.lib.actions.Artifact}. This way, we can use an {@link ActionInput} to
   * search the children.
   */
  @SerializationConstant @VisibleForSerialization
  static final Comparator<ActionInput> EXEC_PATH_COMPARATOR =
      Comparator.comparing(ActionInput::getExecPath);

  static final ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> EMPTY_MAP =
      childDataBuilder().buildOrThrow();

  private static ImmutableSortedMap.Builder<TreeFileArtifact, FileArtifactValue>
      childDataBuilder() {
    return new ImmutableSortedMap.Builder<>(EXEC_PATH_COMPARATOR);
  }

  /** Returns an empty {@link TreeArtifactValue}. */
  public static TreeArtifactValue empty() {
    return EMPTY;
  }

  /**
   * Returns a new {@link Builder} for the given parent tree artifact.
   *
   * <p>The returned builder only supports adding children under this parent. To build multiple tree
   * artifacts at once, use {@link MultiBuilder}.
   */
  public static Builder newBuilder(SpecialArtifact parent) {
    return new Builder(parent);
  }

  /** Builder for constructing multiple instances of {@link TreeArtifactValue} at once. */
  public static final class MultiBuilder {

    private final Map<SpecialArtifact, Builder> map = new HashMap<>();

    private MultiBuilder() {}

    /**
     * Adds an empty tree artifact into this builder.
     *
     * @return {@code this} for convenience
     */
    @CanIgnoreReturnValue
    public MultiBuilder addTree(SpecialArtifact tree) {
      map.computeIfAbsent(tree, Builder::new);
      return this;
    }

    /**
     * Puts a child tree file into this builder under its {@linkplain TreeFileArtifact#getParent
     * parent}, inserting the latter into the builder if not already present.
     *
     * @return {@code this} for convenience
     */
    @CanIgnoreReturnValue
    public MultiBuilder putChild(TreeFileArtifact child, FileArtifactValue metadata) {
      map.computeIfAbsent(child.getParent(), Builder::new).putChild(child, metadata);
      return this;
    }

    /**
     * Sets the archived representation and its metadata for the {@linkplain
     * ArchivedTreeArtifact#getParent parent} of the provided tree artifact.
     *
     * <p>Setting an archived representation is only allowed once per {@linkplain SpecialArtifact
     * tree artifact}.
     */
    @CanIgnoreReturnValue
    public MultiBuilder setArchivedRepresentation(
        ArchivedTreeArtifact archivedArtifact, FileArtifactValue metadata) {
      map.computeIfAbsent(archivedArtifact.getParent(), Builder::new)
          .setArchivedRepresentation(ArchivedRepresentation.create(archivedArtifact, metadata));
      return this;
    }

    /**
     * For each unique parent seen by this builder, passes the aggregated metadata to the specified
     * {@link BiConsumer}.
     */
    public void forEach(BiConsumer<SpecialArtifact, TreeArtifactValue> consumer) {
      map.forEach((parent, builder) -> consumer.accept(parent, builder.build()));
    }
  }

  /** Returns a new {@link MultiBuilder}. */
  public static MultiBuilder newMultiBuilder() {
    return new MultiBuilder();
  }

  /**
   * Archived representation of a tree artifact which contains a representation of the filesystem
   * tree starting with the tree artifact directory.
   *
   * <p>Contains both the {@linkplain ArchivedTreeArtifact artifact} for the archived file and the
   * metadata for it.
   */
  @AutoCodec
  public record ArchivedRepresentation(
      ArchivedTreeArtifact archivedTreeFileArtifact, FileArtifactValue archivedFileValue) {
    public ArchivedRepresentation {
      requireNonNull(archivedTreeFileArtifact, "archivedTreeFileArtifact");
      requireNonNull(archivedFileValue, "archivedFileValue");
    }

    public static ArchivedRepresentation create(
        ArchivedTreeArtifact archivedTreeFileArtifact, FileArtifactValue fileArtifactValue) {
      return new ArchivedRepresentation(archivedTreeFileArtifact, fileArtifactValue);
    }
  }

  // Note that this is not marked as a @SerializationConstant because we need the deserialized value
  // to implement DeserializedSkyValue. As a result, the deserialized value must be of a different
  // class. We make this work by using a custom codec (see TreeArtifactValueCodec).
  private static final TreeArtifactValue EMPTY =
      new TreeArtifactValue(
          MetadataDigestUtils.fromMetadata(ImmutableMap.of()),
          EMPTY_MAP,
          /* parent= */ null,
          0L,
          /* archivedRepresentation= */ null,
          /* resolvedPath= */ null,
          /* entirelyRemote= */ false);

  private final byte[] digest;

  /**
   * The children of this tree artifact.
   *
   * <p>If {@link #parent} is non-null, this is a {@link TreeArtifactDirectory} and the {@link
   * TreeFileArtifact}s of the children are {@linkplain TreeFileArtifact#createTreeOutput created}
   * on demand. See {@link Builder#canRecreate} for when this is possible.
   *
   * <p>Otherwise, this is an {@code ImmutableSortedMap<TreeFileArtifact, FileArtifactValue>} sorted
   * by {@link #EXEC_PATH_COMPARATOR}, which is used for example for the outputs of action template
   * expansions.
   */
  private final Object children;

  /**
   * The tree artifact described by this value if {@link #children} is a {@link
   * TreeArtifactDirectory}, null otherwise.
   */
  @Nullable private final SpecialArtifact parent;

  /**
   * The total size of all regular file children in the lower 63 bits and whether the children are
   * {@linkplain #isEntirelyRemote entirely remote} in the sign bit.
   *
   * <p>Sizes are never negative, so the sign bit is free. Packing the flag into it instead of using
   * a separate boolean field reduces the size of this object from 40 to 32 bytes with compact
   * object headers.
   */
  private final long totalChildSizeAndEntirelyRemote;

  /**
   * The optional {@linkplain #getArchivedRepresentation archived representation} and the optional
   * {@linkplain #getResolvedPath resolved path}: null if neither is present, an {@link
   * ArchivedRepresentation} or a {@link PathFragment} if only one of them is present and an {@link
   * ArchivedRepresentationAndResolvedPath} if both are present.
   *
   * <p>Both are rarely present, so storing them in a single field keeps this object at 32 bytes
   * with compact object headers despite the additional {@link #parent} field.
   */
  @Nullable private final Object archivedRepresentationOrResolvedPath;

  private record ArchivedRepresentationAndResolvedPath(
      ArchivedRepresentation archivedRepresentation, PathFragment resolvedPath) {}

  /** A FileArtifactValue used to stand in for a TreeArtifactValue. */
  private static final class TreeArtifactCompositeFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final boolean isRemote;
    @Nullable private final PathFragment resolvedPath;

    TreeArtifactCompositeFileArtifactValue(
        byte[] digest, boolean isRemote, @Nullable PathFragment resolvedPath) {
      this.digest = digest;
      this.isRemote = isRemote;
      this.resolvedPath = resolvedPath;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TreeArtifactCompositeFileArtifactValue that)) {
        return false;
      }
      return Arrays.equals(digest, that.digest) && Objects.equals(resolvedPath, that.resolvedPath);
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(Arrays.hashCode(digest), resolvedPath);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    @Nullable
    public FileContentsProxy getContentsProxy() {
      return null;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", BaseEncoding.base16().lowerCase().encode(digest))
          .add("resolvedPath", resolvedPath)
          .toString();
    }

    @Override
    protected boolean couldBeModifiedByMetadata(FileArtifactValue o) {
      return false;
    }

    @Override
    public boolean isRemote() {
      return isRemote;
    }

    @Override
    @Nullable
    public PathFragment getResolvedPath() {
      return resolvedPath;
    }
  }

  @VisibleForSerialization
  TreeArtifactValue(
      byte[] digest,
      Object children,
      @Nullable SpecialArtifact parent,
      long totalChildBytes,
      @Nullable ArchivedRepresentation archivedRepresentation,
      @Nullable PathFragment resolvedPath,
      boolean entirelyRemote) {
    checkArgument(totalChildBytes >= 0, "Negative total child size: %s", totalChildBytes);
    checkArgument(
        (parent != null) == (children instanceof TreeArtifactDirectory),
        "Children must be stored in a TreeArtifactDirectory if and only if the parent is known");
    this.digest = digest;
    this.children = children;
    this.parent = parent;
    this.totalChildSizeAndEntirelyRemote =
        entirelyRemote ? totalChildBytes | Long.MIN_VALUE : totalChildBytes;
    if (archivedRepresentation != null && resolvedPath != null) {
      this.archivedRepresentationOrResolvedPath =
          new ArchivedRepresentationAndResolvedPath(archivedRepresentation, resolvedPath);
    } else if (archivedRepresentation != null) {
      this.archivedRepresentationOrResolvedPath = archivedRepresentation;
    } else {
      this.archivedRepresentationOrResolvedPath = resolvedPath;
    }
  }

  @SuppressWarnings("unchecked")
  private ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> childMap() {
    return (ImmutableSortedMap<TreeFileArtifact, FileArtifactValue>) children;
  }

  private TreeArtifactDirectory directory() {
    return (TreeArtifactDirectory) children;
  }

  private static TreeFileArtifact createChild(SpecialArtifact parent, String parentRelativePath) {
    return TreeFileArtifact.createTreeOutput(
        parent, PathFragment.createAlreadyNormalized(parentRelativePath));
  }

  public FileArtifactValue getMetadata() {
    return new TreeArtifactCompositeFileArtifactValue(digest, isEntirelyRemote(), resolvedPath());
  }

  ImmutableSet<PathFragment> getChildPaths() {
    ImmutableSet.Builder<PathFragment> childPaths = ImmutableSet.builder();
    forEachChild((parentRelativePath, metadata) -> childPaths.add(parentRelativePath));
    return childPaths.build();
  }

  @Override
  public byte[] getDigest() {
    return digest.clone();
  }

  /**
   * Returns the children of this tree artifact.
   *
   * <p>Prefer the other accessors if the {@link TreeFileArtifact}s themselves are not needed, as
   * they may have to be created on every call.
   */
  public ImmutableSortedSet<TreeFileArtifact> getChildren() {
    return getChildValues().keySet();
  }

  public long getTotalChildBytes() {
    return totalChildSizeAndEntirelyRemote & Long.MAX_VALUE;
  }

  /** Returns the archived representation of the tree artifact, if present. */
  public Optional<ArchivedRepresentation> getArchivedRepresentation() {
    return Optional.ofNullable(archivedRepresentation());
  }

  @Nullable
  @VisibleForSerialization
  ArchivedRepresentation archivedRepresentation() {
    return switch (archivedRepresentationOrResolvedPath) {
      case ArchivedRepresentation archivedRepresentation -> archivedRepresentation;
      case ArchivedRepresentationAndResolvedPath both -> both.archivedRepresentation();
      case null, default -> null;
    };
  }

  /**
   * Returns the resolved path, if present.
   *
   * <p>See {@link FileArtifactValue#getResolvedPath} for semantics.
   */
  public Optional<PathFragment> getResolvedPath() {
    return Optional.ofNullable(resolvedPath());
  }

  @Nullable
  @VisibleForSerialization
  PathFragment resolvedPath() {
    return switch (archivedRepresentationOrResolvedPath) {
      case PathFragment resolvedPath -> resolvedPath;
      case ArchivedRepresentationAndResolvedPath both -> both.resolvedPath();
      case null, default -> null;
    };
  }

  @Nullable
  public ArchivedTreeArtifact getArchivedArtifact() {
    ArchivedRepresentation archivedRepresentation = archivedRepresentation();
    return archivedRepresentation != null
        ? archivedRepresentation.archivedTreeFileArtifact()
        : null;
  }

  /**
   * Returns the children of this tree artifact and their metadata.
   *
   * <p>Prefer the other accessors if the {@link TreeFileArtifact}s themselves are not needed, as
   * they may have to be created on every call.
   */
  public ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> getChildValues() {
    if (parent == null) {
      return childMap();
    }
    ImmutableSortedMap.Builder<TreeFileArtifact, FileArtifactValue> childValues =
        childDataBuilder();
    directory()
        .forEachFile(
            (parentRelativePath, metadata) ->
                childValues.put(createChild(parent, parentRelativePath), metadata));
    return childValues.buildOrThrow();
  }

  /** Returns whether this tree artifact has at least one child. */
  public boolean hasChildren() {
    // A TreeArtifactDirectory always has at least one entry.
    return parent != null || !childMap().isEmpty();
  }

  /** Returns the number of children of this tree artifact. */
  public int getChildCount() {
    return parent != null ? directory().getFileCount() : childMap().size();
  }

  /**
   * Returns the first child of this tree artifact in exec path order, or {@code null} if it has no
   * children.
   */
  @Nullable
  public TreeFileArtifact getFirstChild() {
    if (parent != null) {
      return createChild(parent, directory().getFirstFilePath());
    }
    return childMap().isEmpty() ? null : childMap().firstKey();
  }

  /**
   * Returns the metadata of the child with the same exec path as the given one, or {@code null} if
   * there is no such child.
   */
  @Nullable
  public FileArtifactValue getChildValue(TreeFileArtifact child) {
    return parent != null
        ? getChildValueFromDirectory(child.getExecPathString())
        : childMap().get(child);
  }

  /**
   * Returns the metadata of the child with the given exec path or {@code null} if no such child is
   * present.
   */
  @Nullable
  public FileArtifactValue findChildValueByExecPath(PathFragment execPath) {
    if (parent != null) {
      return getChildValueFromDirectory(execPath.getPathString());
    }
    Map.Entry<TreeFileArtifact, FileArtifactValue> entry = findChildEntryByExecPath(execPath);
    return entry != null ? entry.getValue() : null;
  }

  @Nullable
  private FileArtifactValue getChildValueFromDirectory(String execPath) {
    int start = getParentRelativePathStart(execPath);
    return start != -1 ? directory().getFile(execPath, start) : null;
  }

  /**
   * Returns the index at which the parent-relative path starts in the given exec path, or -1 if it
   * doesn't point below the {@link #parent}.
   */
  private int getParentRelativePathStart(String execPath) {
    String parentExecPath = parent.getExecPathString();
    int parentLength = parentExecPath.length();
    if (execPath.length() <= parentLength + 1
        || execPath.charAt(parentLength) != '/'
        || !execPath.startsWith(parentExecPath)) {
      return -1;
    }
    return parentLength + 1;
  }

  /**
   * Calls {@code consumer} with the {@linkplain TreeFileArtifact#getParentRelativePath
   * parent-relative path} and the metadata of every child, in exec path order.
   *
   * <p>Prefer this over {@link #getChildValues} when the {@link TreeFileArtifact}s themselves are
   * not needed.
   */
  public void forEachChild(BiConsumer<PathFragment, FileArtifactValue> consumer) {
    if (parent != null) {
      directory()
          .forEachFile(
              (parentRelativePath, metadata) ->
                  consumer.accept(
                      PathFragment.createAlreadyNormalized(parentRelativePath), metadata));
      return;
    }
    childMap()
        .forEach((child, metadata) -> consumer.accept(child.getParentRelativePath(), metadata));
  }

  /** Returns whether the metadata of at least one child satisfies the given predicate. */
  public boolean anyChildValueMatches(Predicate<? super FileArtifactValue> predicate) {
    if (parent != null) {
      return directory().anyFileMatches(predicate);
    }
    for (FileArtifactValue metadata : childMap().values()) {
      if (predicate.test(metadata)) {
        return true;
      }
    }
    return false;
  }

  /** Returns an entry for child with given exec path or null if no such child is present. */
  @SuppressWarnings("unchecked")
  @Nullable
  public Map.Entry<TreeFileArtifact, FileArtifactValue> findChildEntryByExecPath(
      PathFragment execPath) {
    if (parent != null) {
      String execPathString = execPath.getPathString();
      int start = getParentRelativePathStart(execPathString);
      if (start == -1) {
        return null;
      }
      FileArtifactValue metadata = directory().getFile(execPathString, start);
      return metadata != null
          ? Maps.immutableEntry(createChild(parent, execPathString.substring(start)), metadata)
          : null;
    }
    ActionInput searchToken = ActionInputHelper.fromPath(execPath);
    // Not really a copy -- original map is already an ImmutableSortedMap using the same comparator.
    ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> childData = childMap();
    ImmutableSortedMap<ActionInput, FileArtifactValue> casted =
        ImmutableSortedMap.copyOf(childData, EXEC_PATH_COMPARATOR);
    checkState(casted == (Object) childData, "Casting children resulted with a copy");
    Map.Entry<? extends ActionInput, FileArtifactValue> entry = casted.floorEntry(searchToken);
    return entry != null && entry.getKey().getExecPath().equals(execPath)
        ? (Map.Entry<TreeFileArtifact, FileArtifactValue>) entry
        : null;
  }

  /** Returns true if the {@link TreeFileArtifact}s are only stored remotely. */
  public boolean isEntirelyRemote() {
    return totalChildSizeAndEntirelyRemote < 0;
  }

  /**
   * Returns the value for the corresponding output of a shared action, which is equivalent to
   * recreating all children and the archived representation (but not the resolved path) for the
   * given parent.
   */
  TreeArtifactValue transformForSharedAction(SpecialArtifact newParent) {
    ArchivedRepresentation archivedRepresentation = archivedRepresentation();
    // The directory, digest, size and remoteness don't depend on the parent and can be reused as is
    // as long as the children of the new parent can also be recreated on demand.
    if (parent != null
        && createChild(newParent, directory().getFirstFilePath()).isChildOfDeclaredDirectory()) {
      return new TreeArtifactValue(
          digest,
          children,
          newParent,
          getTotalChildBytes(),
          archivedRepresentation != null
              ? ArchivedRepresentation.create(
                  ArchivedTreeArtifact.createForTree(newParent),
                  archivedRepresentation.archivedFileValue())
              : null,
          /* resolvedPath= */ null,
          isEntirelyRemote());
    }
    Builder newTree = newBuilder(newParent);
    forEachChild(
        (parentRelativePath, metadata) ->
            newTree.putChild(
                TreeFileArtifact.createTreeOutput(newParent, parentRelativePath), metadata));
    if (archivedRepresentation != null) {
      newTree.setArchivedRepresentation(
          ArchivedTreeArtifact.createForTree(newParent),
          archivedRepresentation.archivedFileValue());
    }
    return newTree.build();
  }

  @Override
  public int hashCode() {
    return HashCodes.hashObjects(Arrays.hashCode(digest), archivedRepresentation(), resolvedPath());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof TreeArtifactValue that)) {
      return false;
    }

    return Arrays.equals(digest, that.digest)
        && Objects.equals(parent, that.parent)
        && children.equals(that.children)
        && Objects.equals(
            archivedRepresentationOrResolvedPath, that.archivedRepresentationOrResolvedPath);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("digest", digest)
        .add("childData", getChildValues())
        .add("archivedRepresentation", archivedRepresentation())
        .add("resolvedPath", resolvedPath())
        .toString();
  }

  /**
   * A TreeArtifactValue that represents a missing TreeArtifact. This is occasionally useful because
   * Java's concurrent collections disallow null members.
   */
  public static final TreeArtifactValue MISSING_TREE_ARTIFACT =
      createMarker("MISSING_TREE_ARTIFACT");

  private static TreeArtifactValue createMarker(String toStringRepresentation) {
    return new TreeArtifactValue(
        null,
        EMPTY_MAP,
        /* parent= */ null,
        0L,
        /* archivedRepresentation= */ null,
        /* resolvedPath= */ null,
        /* entirelyRemote= */ false) {
      @Override
      public ImmutableSortedSet<TreeFileArtifact> getChildren() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> getChildValues() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public boolean hasChildren() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public int getChildCount() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public TreeFileArtifact getFirstChild() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public FileArtifactValue getChildValue(TreeFileArtifact child) {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public FileArtifactValue findChildValueByExecPath(PathFragment execPath) {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public void forEachChild(BiConsumer<PathFragment, FileArtifactValue> consumer) {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public boolean anyChildValueMatches(Predicate<? super FileArtifactValue> predicate) {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public FileArtifactValue getMetadata() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      ImmutableSet<PathFragment> getChildPaths() {
        throw new UnsupportedOperationException(toString());
      }

      @Nullable
      @Override
      public byte[] getDigest() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public int hashCode() {
        return System.identityHashCode(this);
      }

      @Override
      public boolean equals(Object other) {
        return this == other;
      }

      @Override
      public String toString() {
        return toStringRepresentation;
      }
    };
  }

  /** Visitor for use in {@link #visitTree}. */
  @FunctionalInterface
  public interface TreeArtifactVisitor {
    /**
     * Called for every directory entry encountered during tree traversal, in a nondeterministic
     * order.
     *
     * <p>Regular files and directories are reported as {@link Dirent.Type#FILE} or {@link
     * Dirent.Type#DIRECTORY}, respectively. Directories are traversed recursively.
     *
     * <p>Symlinks that resolve to an existing file or directory are followed and reported as the
     * regular files or directories they point to, recursively for directories. Symlinks that fail
     * to resolve to an existing path cause an {@link IOException} to be immediately thrown without
     * invoking the visitor. Thus, the visitor is never called with a {@link Dirent.Type#SYMLINK}
     * type.
     *
     * <p>Special files or files whose type could not be determined, regardless of whether they are
     * encountered directly or indirectly through symlinks, cause an {@link IOException} to be
     * immediately thrown without invoking the visitor. Thus, the visitor is never called with a
     * {@link Dirent.Type#UNKNOWN} type.
     *
     * <p>The {@code parentRelativePath} argument is always set to the apparent path relative to the
     * tree directory root, without resolving any intervening symlinks. The {@code traversedSymlink}
     * argument is true if at least one symlink was traversed on the way to the entry being
     * reported.
     *
     * <p>If the visitor throws {@link IOException}, traversal is immediately halted and the
     * exception is propagated.
     *
     * <p>This method can be called from multiple threads in parallel during a single call of {@link
     * TreeArtifactVisitor#visitTree(Path, TreeArtifactVisitor)}.
     */
    @ThreadSafe
    void visit(PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink)
        throws IOException;
  }

  /** An {@link AbstractQueueVisitor} that visits every file in the tree artifact. */
  static class Visitor extends AbstractQueueVisitor {
    private final Path parentDir;
    private final TreeArtifactVisitor visitor;

    Visitor(Path parentDir, TreeArtifactVisitor visitor) {
      super(
          VISITOR_POOL,
          ExecutorOwnership.SHARED,
          ExceptionHandlingMode.FAIL_FAST,
          ErrorClassifier.DEFAULT);
      this.parentDir = checkNotNull(parentDir);
      this.visitor = checkNotNull(visitor);
    }

    void run() throws IOException, InterruptedException {
      execute(
          () ->
              visit(
                  PathFragment.EMPTY_FRAGMENT,
                  Dirent.Type.DIRECTORY,
                  /* traversedSymlink= */ false));
      try {
        awaitQuiescence(true);
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }

    private void visit(
        PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink) {
      try {
        Path path = parentDir.getRelative(parentRelativePath);

        if (type == Dirent.Type.SYMLINK) {
          traversedSymlink = true;

          FileStatus statFollow = path.statIfFound(Symlinks.FOLLOW);

          if (statFollow == null) {
            throw new IOException(
                String.format("child %s is a dangling symbolic link", parentRelativePath));
          }

          if (statFollow.isFile() && !statFollow.isSpecialFile()) {
            type = Dirent.Type.FILE;
          } else if (statFollow.isDirectory()) {
            type = Dirent.Type.DIRECTORY;
          } else {
            type = Dirent.Type.UNKNOWN;
          }
        }

        if (type == Dirent.Type.UNKNOWN) {
          throw new IOException(
              String.format("child %s has an unsupported type", parentRelativePath));
        }

        visitor.visit(parentRelativePath, type, traversedSymlink);

        if (type == Dirent.Type.DIRECTORY) {
          for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
            PathFragment childPath = parentRelativePath.getChild(dirent.getName());
            Dirent.Type childType = dirent.getType();
            boolean finalTraversedSymlink = traversedSymlink;
            execute(() -> visit(childPath, childType, finalTraversedSymlink));
          }
        }
      } catch (IOException e) {
        // We can't throw checked exceptions here since AQV expects Runnables
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * Recursively visits all descendants under a directory.
   *
   * <p>{@link TreeArtifactVisitor#visit} is invoked on {@code visitor} for each directory, file,
   * and symlink under the given {@code parentDir}, including {@code parentDir} itself.
   *
   * <p>This method is intended to provide uniform semantics for constructing a tree artifact,
   * including special logic that validates directory entries. Invalid directory entries include a
   * symlink that traverses outside of the tree artifact and any entry of {@link
   * Dirent.Type#UNKNOWN}, such as a named pipe.
   *
   * <p>The visitor will be called on multiple threads in parallel. Accordingly, it must be
   * thread-safe.
   *
   * @throws IOException if there is any problem reading or validating outputs under the given tree
   *     artifact directory, or if {@link TreeArtifactVisitor#visit} throws {@link IOException}
   */
  public static void visitTree(Path parentDir, TreeArtifactVisitor treeArtifactVisitor)
      throws IOException, InterruptedException {
    Visitor visitor = new Visitor(parentDir, treeArtifactVisitor);
    visitor.run();
  }

  /** Builder for a {@link TreeArtifactValue}. */
  public static final class Builder {
    private final ImmutableSortedMap.Builder<TreeFileArtifact, FileArtifactValue> childData =
        childDataBuilder();
    private ArchivedRepresentation archivedRepresentation;
    private PathFragment resolvedPath;
    private final SpecialArtifact parent;

    Builder(SpecialArtifact parent) {
      checkArgument(parent.isTreeArtifact(), "%s is not a tree artifact", parent);
      this.parent = parent;
    }

    /**
     * Adds a child to this builder.
     *
     * <p>The child's {@linkplain TreeFileArtifact#getParent parent} <em>must</em> match the parent
     * with which this builder was initialized.
     *
     * <p>Children may be added in any order. The children are sorted prior to constructing the
     * final {@link TreeArtifactValue}.
     *
     * @return {@code this} for convenience
     */
    @CanIgnoreReturnValue
    public Builder putChild(TreeFileArtifact child, FileArtifactValue metadata) {
      checkArgument(
          child.isChildOf(parent),
          "While building TreeArtifactValue for %s, got %s with parent %s",
          parent,
          child,
          child.getParent());
      childData.put(child, metadata);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setArchivedRepresentation(
        ArchivedTreeArtifact archivedTreeArtifact, FileArtifactValue metadata) {
      return setArchivedRepresentation(
          ArchivedRepresentation.create(archivedTreeArtifact, metadata));
    }

    @CanIgnoreReturnValue
    public Builder setArchivedRepresentation(ArchivedRepresentation archivedRepresentation) {
      checkNotNull(archivedRepresentation);
      checkState(
          this.archivedRepresentation == null,
          "Tried to add 2 archived representations for: %s",
          parent);
      checkArgument(
          parent.equals(archivedRepresentation.archivedTreeFileArtifact().getParent()),
          "Cannot add archived representation: %s for a mismatching tree artifact: %s",
          archivedRepresentation,
          parent);
      this.archivedRepresentation = archivedRepresentation;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setResolvedPath(PathFragment resolvedPath) {
      checkArgument(resolvedPath.isAbsolute(), resolvedPath);
      checkState(
          this.resolvedPath == null, "Tried to set resolved path multiple times for: %s", parent);
      this.resolvedPath = resolvedPath;
      return this;
    }

    /** Builds the final {@link TreeArtifactValue}. */
    public TreeArtifactValue build() {
      ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> finalChildData =
          childData.buildOrThrow();
      if (finalChildData.isEmpty() && archivedRepresentation == null && resolvedPath == null) {
        return EMPTY;
      }

      Fingerprint fingerprint = new Fingerprint();
      boolean entirelyRemote =
          archivedRepresentation == null || archivedRepresentation.archivedFileValue().isRemote();

      int childCount = finalChildData.size();
      String[] parentRelativePaths = new String[childCount];
      FileArtifactValue[] childValues = new FileArtifactValue[childCount];
      ActionLookupData parentKey =
          parent.hasGeneratingActionKey() ? parent.getGeneratingActionKey() : null;
      boolean canRecreateChildren = childCount > 0 && parentKey != null;
      long totalChildSize = 0;
      int i = 0;
      for (Map.Entry<TreeFileArtifact, FileArtifactValue> childData : finalChildData.entrySet()) {
        TreeFileArtifact child = childData.getKey();
        String parentRelativePath = child.getTreeRelativePathString();
        // Digest will be deterministic because children are sorted. This is equivalent to (but
        // cheaper than) adding child.getParentRelativePath().
        fingerprint.addString(parentRelativePath);
        FileArtifactValue metadata = childData.getValue();
        metadata.addTo(fingerprint);

        // Tolerate a mix of local and remote children (b/152496153#comment80).
        entirelyRemote &= metadata.isRemote();

        if (metadata.getType() == FileStateType.REGULAR_FILE) {
          totalChildSize += metadata.getSize();
        }

        canRecreateChildren &= canRecreate(child, parentRelativePath, parentKey);
        parentRelativePaths[i] = parentRelativePath;
        childValues[i] = metadata;
        i++;
      }

      if (archivedRepresentation != null) {
        archivedRepresentation.archivedFileValue().addTo(fingerprint);
      }

      return new TreeArtifactValue(
          fingerprint.digestAndReset(),
          canRecreateChildren
              ? TreeArtifactDirectory.create(parentRelativePaths, childValues)
              : finalChildData,
          canRecreateChildren ? parent : null,
          totalChildSize,
          archivedRepresentation,
          resolvedPath,
          entirelyRemote);
    }

    /**
     * Returns whether the given child is equal to the result of {@link
     * TreeFileArtifact#createTreeOutput} for the parent of this builder and the child's
     * parent-relative path, so that it doesn't have to be retained.
     *
     * <p>This is the case for children of declared directories, but not for the outputs of action
     * template expansions, which are owned by the expanded actions.
     */
    private boolean canRecreate(
        TreeFileArtifact child, String parentRelativePath, ActionLookupData parentKey) {
      return child.getParent().equals(parent)
          && child.isChildOfDeclaredDirectory()
          && child.hasGeneratingActionKey()
          && child.getGeneratingActionKey().equals(parentKey)
          && TreeArtifactDirectory.canRepresent(parentRelativePath);
    }
  }
}
