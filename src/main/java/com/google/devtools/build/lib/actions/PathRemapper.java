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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;

public interface PathRemapper extends CommandAdjuster {

  GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  default List<String> stripCustomStarlarkArgs(List<String> args) {
    logger.atWarning().log("stripCustomStarlarkArgs called on PathFragment %s", args);
    return args;
  }

  default String strip(ActionInput artifact) {
    if (!(artifact instanceof DerivedArtifact)) {
      return artifact.getExecPathString();
    }
    return strip(artifact.getExecPath()).getPathString();
  }

  void materialize(Path execRoot) throws IOException;

  static PathRemapper noop() {
    return NoopPathRemapper.INSTANCE;
  }

  class PerActionPathRemapper implements PathRemapper {

    private final ImmutableMap<PathFragment, String> execPathMapping;

    private PerActionPathRemapper(ImmutableMap<PathFragment, String> execPathMapping) {
      this.execPathMapping = execPathMapping;
    }

    @Override
    public PathFragment strip(PathFragment execPath) {
      String remappedPath = execPathMapping.get(execPath);
      if (remappedPath != null) {
        return PathFragment.createAlreadyNormalized(remappedPath);
      }
      // Output artifact
      return PathFragment.createAlreadyNormalized(
          PathRemapper.execPathStringWithSyntheticConfig(execPath, "out"));
    }

    @Override
    public void materialize(Path execRoot) throws IOException {
      for (Entry<PathFragment, String> e : execPathMapping.entrySet()) {
        execRoot.getRelative(e.getValue()).createSymbolicLink(e.getKey());
      }
    }
  }

  class NoopPathRemapper implements PathRemapper {
    private static final PathRemapper INSTANCE = new NoopPathRemapper();

    @Override
    public PathFragment strip(PathFragment execPath) {
      return execPath;
    }

    @Override
    public void materialize(Path execRoot) {}
  }

  static PathRemapper create(
      Map<String, String> executionInfo,
      ArtifactExpander artifactExpander,
      @Nullable MetadataHandler metadataHandler,
      NestedSet<Artifact> inputs) {
    if (metadataHandler == null) {
      return NoopPathRemapper.INSTANCE;
    }
    if (!executionInfo.containsKey(ExecutionRequirements.SUPPORTS_PATH_REMAPPING)) {
      return NoopPathRemapper.INSTANCE;
    }
    byte[] baseHash = new byte[DigestUtils.ESTIMATED_SIZE];
    Fingerprint fp = new Fingerprint();
    // TODO: Handle materialization of param files.
    HashMap<String, ArrayList<Pair<DerivedArtifact, byte[]>>> shortPathCollisions = new HashMap<>();
    List<Artifact> expandedInputs = new ArrayList<>();
    // TODO: Handle filesets.
    for (Artifact input : inputs.toList()) {
      if (input.isMiddlemanArtifact()) {
        artifactExpander.expand(input, expandedInputs);
      } else {
        expandedInputs.add(input);
      }
    }
    for (Artifact input : expandedInputs) {
      if (!(input instanceof DerivedArtifact)) {
        logger.atInfo().log("Skipping source artifact %s", input);
        continue;
      }
      DerivedArtifact derivedArtifact = (DerivedArtifact) input;
      String path = derivedArtifact.getRootRelativePathString();
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

    // 25 * log_2(32) = 125 bits ought to be enough to prevent collisions.
    String rootPrefix = BaseEncoding.base32().lowerCase().omitPadding().encode(baseHash).substring(0, 25);
    ImmutableMap<PathFragment, String> execPathMapping = shortPathCollisions
        .values()
        .stream()
        .flatMap(collidingArtifacts ->
            Streams.mapWithIndex(collidingArtifacts.stream()
                    .sorted(Comparator.comparing(Pair::getSecond,
                        UnsignedBytes.lexicographicalComparator()))
                    .map(Pair::getFirst),
                (artifact, lexicographicIndex) -> new Pair<>(artifact.getExecPath(),
                    execPathStringWithSyntheticConfig(artifact.getExecPath(),
                        rootPrefix + "-" + lexicographicIndex))))
        .collect(ImmutableMap.toImmutableMap(p -> p.first, p -> p.second));
    return new PerActionPathRemapper(execPathMapping);
  }

  private static String execPathStringWithSyntheticConfig(PathFragment execPath, String config) {
    // TODO: Support experimental_sibling_repository_layout.
    String remappedPath = execPath.subFragment(0, 1)
        .getRelative(config)
        .getRelative(execPath.subFragment(2))
        .getPathString();
    logger.atInfo().log("Remapping %s to %s", execPath.getPathString(), remappedPath);
    return remappedPath;
  }

  static PathFragment stripForRunfiles(PathFragment execPath) {
    return PathFragment.createAlreadyNormalized(execPathStringWithSyntheticConfig(execPath, "run"));
  }

  static InvertibleFunction<PathFragment, PathFragment> restrictionOf(CommandAdjuster remapper,
      Collection<PathFragment> paths) {
    return InvertibleFunction.restrictionOf(remapper::strip, paths);
  }
}
