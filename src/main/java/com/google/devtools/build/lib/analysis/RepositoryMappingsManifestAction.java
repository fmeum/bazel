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
package com.google.devtools.build.lib.analysis;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.Fingerprint;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

@Immutable
public final class RepositoryMappingsManifestAction extends AbstractFileWriteAction {

  private static final String GUID = "bba4527e-b8b7-4480-a8fb-2b10ee44d29f";

  private static final class RepositoryMappingsManifestEntry implements
      Comparable<RepositoryMappingsManifestEntry> {

    private final RepositoryName userCanonicalName;
    private final String ownerApparentName;
    private final RepositoryName ownerCanonicalName;

    public RepositoryMappingsManifestEntry(
        RepositoryName userCanonicalName,
        String ownerApparentName,
        RepositoryName ownerCanonicalName) {
      this.userCanonicalName = Preconditions.checkNotNull(userCanonicalName);
      this.ownerApparentName = Preconditions.checkNotNull(ownerApparentName);
      this.ownerCanonicalName = Preconditions.checkNotNull(ownerCanonicalName);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RepositoryMappingsManifestEntry that = (RepositoryMappingsManifestEntry) o;
      return Objects.equal(userCanonicalName, that.userCanonicalName)
          && Objects.equal(ownerApparentName, that.ownerApparentName)
          && Objects.equal(ownerCanonicalName, that.ownerCanonicalName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(userCanonicalName, ownerApparentName, ownerCanonicalName);
    }

    @Override
    public int compareTo(RepositoryMappingsManifestEntry that) {
      return ComparisonChain.start()
          .compare(this.userCanonicalName.getName(), that.userCanonicalName.getName())
          .compare(this.ownerApparentName, that.ownerApparentName)
          .compare(this.ownerCanonicalName.getName(), that.ownerCanonicalName.getName())
          .result();
    }
  }

  private final Runfiles runfiles;
  private final ImmutableMap<RepositoryName, RepositoryMapping> repositoryMappings;
  private final String workspaceName;


  public RepositoryMappingsManifestAction(
      ActionOwner owner,
      Artifact primaryOutput,
      Runfiles runfiles,
      Map<RepositoryName, RepositoryMapping> repositoryMappings,
      String workspaceName) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), primaryOutput, false);
    this.runfiles = runfiles;
    this.repositoryMappings = ImmutableMap.copyOf(repositoryMappings);
    this.workspaceName = workspaceName;
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    return this::writeFile;
  }

  @Override
  public boolean isRemotable() {
    return true;
  }

  private void writeFile(OutputStream out) throws IOException {
    Writer manifestFile = new BufferedWriter(new OutputStreamWriter(out, ISO_8859_1));

    Set<RepositoryName> runfilesOwningRepositories = getRunfilesOwningRepositories();

    List<RepositoryMappingsManifestEntry> manifestEntries = new ArrayList<>();
    for (Map.Entry<RepositoryName, RepositoryMapping> mapping : repositoryMappings.entrySet()) {
      for (RepositoryName owner : runfilesOwningRepositories) {
        for (String apparentRepositoryName : mapping.getValue().getApparent(owner)) {
          if (apparentRepositoryName.isEmpty()) {
            // Skip over empty apparent names as they aren't usable by runfiles libraries: A path
            // starting with an empty string followed by a path separator is just an absolute path.
            continue;
          }
          manifestEntries.add(
              new RepositoryMappingsManifestEntry(mapping.getKey(), apparentRepositoryName, owner));
        }
      }
    }
    Collections.sort(manifestEntries);

    for (RepositoryMappingsManifestEntry manifestEntry : manifestEntries) {
      writeEntry(manifestFile, manifestEntry);
    }
    manifestFile.flush();
  }

  private void writeEntry(Writer manifestFile, RepositoryMappingsManifestEntry entry)
      throws IOException {
    manifestFile.append(entry.userCanonicalName.getName());
    manifestFile.append(',');
    manifestFile.append(entry.ownerApparentName);
    manifestFile.append(',');
    if (entry.ownerCanonicalName.isMain()) {
      manifestFile.append(workspaceName);
    } else {
      manifestFile.append(entry.ownerCanonicalName.getName());
    }
    manifestFile.append('\n');
  }

  private Set<RepositoryName> getRunfilesOwningRepositories() {
    Set<RepositoryName> runfilesOwners = new HashSet<>();
    for (Artifact runfile : runfiles.getArtifacts().toList()) {
      if (runfile.getOwner() != null) {
        runfilesOwners.add(runfile.getOwner().getRepository());
      }
    }
    return runfilesOwners;
  }

  @Override
  public String getMnemonic() {
    return "RepositoryMappingsManifest";
  }

  @Override
  protected String getRawProgressMessage() {
    return "Creating repository mappings manifest for " + getOwner().getLabel();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable Artifact.ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addString(GUID);
    runfiles.fingerprint(fp);

    fp.addInt(repositoryMappings.size());
    for (Map.Entry<RepositoryName, RepositoryMapping> entry : repositoryMappings.entrySet()) {
      fp.addString(entry.getKey().getName());
      entry.getValue().fingerprint(fp);
    }
  }

  @Override
  public String describeKey() {
    return String.format("GUID: %s\nrunfiles: %s\n", GUID, runfiles.describeFingerprint());
  }
}
