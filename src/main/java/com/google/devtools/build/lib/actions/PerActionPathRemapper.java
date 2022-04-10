package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DigestUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

public class PerActionPathRemapper {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final String rootPrefix;
  private final ImmutableMap<String, byte[][]> pathToDigests;

  private PerActionPathRemapper(String rootPrefix, ImmutableMap<String, byte[][]> pathToDigests) {
    this.rootPrefix = rootPrefix;
    this.pathToDigests = pathToDigests;
  }

  public static PerActionPathRemapper create(
      ActionExecutionContext actionExecutionContext,
      Iterable<ActionInput> inputs) {
    MetadataHandler metadataHandler = actionExecutionContext.getMetadataHandler();
    byte[] baseHash = new byte[1];
    Fingerprint fp = new Fingerprint();
    HashMap<String, ArrayList<byte[]>> pathToDigests = new HashMap<>();
    for (ActionInput input : inputs) {
      if (!(input instanceof DerivedArtifact)) {
        logger.atInfo().log("Skipping source artifact %s", input);
        continue;
      }
      String path = ((DerivedArtifact) input).getRootRelativePathString();
      fp.addString(path);
      FileArtifactValue md;
      try {
        md = metadataHandler.getMetadata(input);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Error getting metadata for %s", input);
        return null;
      }
      if (md == null) {
        logger.atWarning().log("Got null metadata for %s", input);
        return null;
      }
      byte[] digest = md.getDigest();
      if (digest == null) {
        logger.atWarning().log("Got null digest for %s", input);
        return null;
      }
      fp.addBytes(digest);
      baseHash = DigestUtils.xor(baseHash, fp.digestAndReset());
      if (!pathToDigests.containsKey(path)) {
        pathToDigests.put(path, new ArrayList<>());
      }
      pathToDigests.get(path).add(digest);
    }
    if (pathToDigests.isEmpty()) {
      return null;
    }

    String rootPrefix = BaseEncoding.base16().encode(baseHash);
    ImmutableMap<String, byte[][]> pathToSortedDigests = pathToDigests.entrySet()
        .stream()
        // Omit the lexicographically first (and possibly only) digest as a memory optimization for
        // the very common case where a given path is only built in a single configuration. Since it
        // is the only missing digest for this path, it can be recognized by its absence from the
        // array.
        .filter(e -> e.getValue().size() > 1)
        .collect(ImmutableMap.toImmutableMap(
            Entry::getKey,
            e -> e.getValue()
                .stream()
                .sorted(UnsignedBytes.lexicographicalComparator())
                .skip(1)
                .toArray(byte[][]::new)));
    return new PerActionPathRemapper(rootPrefix, pathToSortedDigests);
  }

  public String getExecPathString(MetadataHandler metadataHandler, ActionInput input) {
    if (!(input instanceof DerivedArtifact)) {
      return input.getExecPathString();
    }
    String path = ((DerivedArtifact) input).getRootRelativePathString();
    String root = rootPrefix;
    if (pathToDigests.containsKey(path)) {
      int index = Arrays.binarySearch(pathToDigests.get(path), )
    }
  }
}
