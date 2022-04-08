package com.google.devtools.build.lib.actions;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DigestUtils;
import java.util.ArrayList;
import java.util.HashMap;

public class PerActionPathRemapper {

  private static final class RemapKey {
    public final String cleanPath;
    public final byte[] fingerprint;

    private RemapKey(String cleanPath, byte[] fingerprint) {
      this.cleanPath = cleanPath;
      this.fingerprint = fingerprint;
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) {
        return true;
      }
      if (that == null || getClass() != that.getClass()) {
        return false;
      }
      RemapKey remapKey = (RemapKey) that;
      return Objects.equal(cleanPath, remapKey.cleanPath)
          && Objects.equal(fingerprint, remapKey.fingerprint);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(cleanPath, fingerprint);
    }
  }

  private static ImmutableMap<RemapKey, Byte> computeRemapping(Iterable<ActionInput> inputs) {
    Fingerprint fingerprint = new Fingerprint();
    HashMap<String, ArrayList<byte[]>> pathToHash = new HashMap<>();
    for (ActionInput input : inputs) {
      if (!(input instanceof DerivedArtifact)) {
        continue;
      }
      DerivedArtifact derivedArtifact = (DerivedArtifact) input;
      DigestUtils.getDigestWithManualFallback()
    }
    return null;
  }
}
