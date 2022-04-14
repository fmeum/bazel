package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.PathStripper.CommandAdjuster;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;

public interface PathRemapper extends CommandAdjuster {

  GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  default PathFragment strip(PathFragment execPath) {
    logger.atWarning().log("strip called on PathFragment %s", execPath);
    return execPath;
  }

  @Override
  default List<String> stripCustomStarlarkArgs(List<String> args) {
    logger.atWarning().log("stripCustomStarlarkArgs called on PathFragment %s", args);
    return args;
  }

  void materialize(Path execRoot);

  class PerActionPathRemapper implements PathRemapper {

    private final ImmutableMap<PathFragment, String> execPathMapping;

    private PerActionPathRemapper(ImmutableMap<PathFragment, String> execPathMapping) {
      this.execPathMapping = execPathMapping;
    }

    @Override
    public String strip(DerivedArtifact artifact, boolean forActionKey) {
      String remappedPath = execPathMapping.get(artifact.getExecPath());
      if (remappedPath != null) {
        return remappedPath;
      }
      // Output artifact.
      if (forActionKey) {
        return artifact.getRootRelativePathString();
      } else {
        return artifact.getExecPathString();
      }
    }

    @Override
    public PathFragment strip(PathFragment execPath) {
      String remappedPath = execPathMapping.get(execPath);
      if (remappedPath != null) {
        return PathFragment.createAlreadyNormalized(remappedPath);
      }
      return PathRemapper.super.strip(execPath);
    }

    @Override
    public void materialize(Path execRoot) {
      // TODO: Atomically create symlinks.
    }
  }

  class NoopPathRemapper implements PathRemapper {
    public static final PathRemapper INSTANCE = new NoopPathRemapper();

    @Override
    public String strip(DerivedArtifact artifact, boolean forActionKey) {
      return artifact.getExecPathString();
    }

    @Override
    public void materialize(Path execRoot) {}
  }

  static PathRemapper create(
      ArtifactExpander artifactExpander,
      @Nullable MetadataHandler metadataHandler,
      NestedSet<Artifact> inputs) {
    if (metadataHandler == null) {
      return NoopPathRemapper.INSTANCE;
    }
    byte[] baseHash = new byte[1];
    Fingerprint fp = new Fingerprint();
    HashMap<String, ArrayList<Pair<DerivedArtifact, byte[]>>> shortPathCollisions = new HashMap<>();
    List<ActionInput> expandedInputs = ActionInputHelper.expandArtifacts(inputs, artifactExpander);
    for (ActionInput input : expandedInputs) {
      if (!(input instanceof DerivedArtifact)) {
        System.err.printf("Skipping source artifact %s%n", input);
        logger.atInfo().log("Skipping source artifact %s", input);
        continue;
      }
      DerivedArtifact derivedArtifact = (DerivedArtifact) input;
      String path = derivedArtifact.getRootRelativePathString();
      System.err.printf("Hashing %s as %s%n", input.getExecPathString(), path);
      fp.addString(path);
      FileArtifactValue md;
      try {
        md = metadataHandler.getMetadata(input);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Error getting metadata for %s", input);
        return NoopPathRemapper.INSTANCE;
      }
      if (md == null) {
        logger.atWarning().log("Got null metadata for %s", input);
        return NoopPathRemapper.INSTANCE;
      }
      byte[] digest = md.getDigest();
      if (digest == null) {
        logger.atWarning().log("Got null digest for %s", input);
        return NoopPathRemapper.INSTANCE;
      }
      fp.addBytes(digest);
      baseHash = DigestUtils.xor(baseHash, fp.digestAndReset());
      if (!shortPathCollisions.containsKey(path)) {
        shortPathCollisions.put(path, new ArrayList<>());
      }
      shortPathCollisions.get(path).add(new Pair<>(derivedArtifact, digest));
    }
    if (shortPathCollisions.isEmpty()) {
      return NoopPathRemapper.INSTANCE;
    }

    String rootPrefix = BaseEncoding.base16().encode(baseHash);
    ImmutableMap<PathFragment, String> execPathMapping = shortPathCollisions
        .values()
        .stream()
        .flatMap(collidingArtifacts ->
            Streams.mapWithIndex(collidingArtifacts.stream()
                    .sorted(Comparator.comparing(Pair::getSecond,
                        UnsignedBytes.lexicographicalComparator()))
                    .map(Pair::getFirst),
                (artifact, lexicographicIndex) -> new Pair<>(artifact.getExecPath(),
                    remappedExecPathString(rootPrefix, artifact, lexicographicIndex))))
        .collect(ImmutableMap.toImmutableMap(p -> p.first, p -> p.second));
    return new PerActionPathRemapper(execPathMapping);
  }

  private static String remappedExecPathString(String rootPrefix, DerivedArtifact artifact,
      long lexicographicIndex) {
    PathFragment execPath = artifact.getExecPath();
    String remappedPath = execPath.subFragment(0, 1)
        .getRelative(rootPrefix + "-" + lexicographicIndex)
        .getRelative(execPath.subFragment(2))
        .getPathString();
    logger.atInfo().log("Remapping %s to %s", artifact.getExecPathString(), remappedPath);
    return remappedPath;
  }
}
