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

package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.exec.SpawnLogContext;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.SymlinkAction.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Action to create a copy of the input.
 *
 * <p>{@link SymlinkAction} can be more efficient, but does not have the same semantics as a true
 * copy.
 */
public final class CopyAction extends AbstractAction {
  private static final String GUID = "404e0f0e-6795-4cc7-a62b-891e088150bb";

  /**
   * Creates an action that creates a copy of an artifact.
   *
   * @param owner the action owner.
   * @param input the {@link Artifact} the symlink will point to
   * @param output the {@link Artifact} that will be created by executing this Action.
   */
  public CopyAction(ActionOwner owner, Artifact input, Artifact output) {
    super(owner, NestedSetBuilder.create(Order.STABLE_ORDER, input), ImmutableSet.of(output));
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Artifact primaryInput = getPrimaryInput();
    Path inputPath = actionExecutionContext.getInputPath(primaryInput);
    Path outputPath = actionExecutionContext.getInputPath(getPrimaryOutput());
    try {
      FileArtifactValue metadata =
          actionExecutionContext.getInputMetadataProvider().getInputMetadata(primaryInput);
      switch (metadata.getType()) {
        case SYMLINK ->
            outputPath.createSymbolicLink(
                metadata.getUnresolvedSymlinkTarget(), SymlinkTargetType.UNSPECIFIED);
        case DIRECTORY -> FileSystemUtils.copyTreesBelow(inputPath, outputPath);
        default -> {}
      }
    } catch (IOException e) {
      String message =
          String.format(
              "failed to copy '%s' to '%s' due to I/O error: %s",
              inputPath, outputPath, e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.LINK_CREATION_IO_EXCEPTION);
      throw new ActionExecutionException(message, e, this, /* catastrophe= */ false, code);
    }

    SpawnLogContext logContext = actionExecutionContext.getContext(SpawnLogContext.class);
    if (logContext != null) {
      try {
        logContext.logSymlinkAction(this);
      } catch (IOException e) {
        String message =
            String.format(
                "failed to log creation of symlink '%s' to '%s' due to I/O error: %s",
                getPrimaryOutput().getExecPathString(), printInputs(), e.getMessage());
        DetailedExitCode code = createDetailedExitCode(message, Code.LINK_LOG_IO_EXCEPTION);
        throw new ActionExecutionException(message, e, this, false, code);
      }
    }

    if (targetType == TargetType.FILESET) {
      // Forward the Fileset metadata to the output artifact of this symlink: the metadata is
      // created in an upstream (Google-specific) action, but the output of this action will appear
      // on the inputs of actions that have the Fileset as an input. The Fileset metadata must be
      // attached to that artifact so that the execution strategies of actions that take it as an
      // input can recreate the Fileset.
      actionExecutionContext.setRichArtifactData(
          FilesetOutputTree.forward(
              actionExecutionContext.getInputMetadataProvider().getFileset(getPrimaryInput())));
    } else {
      maybeInjectMetadata(this, actionExecutionContext);
    }
    return ActionResult.EMPTY;
  }

  private SymlinkTargetType getSymlinkTargetType(ActionExecutionContext actionExecutionContext)
      throws IOException {
    Artifact primaryInput = getPrimaryInput();
    if (primaryInput == null) {
      return SymlinkTargetType.UNSPECIFIED;
    }
    FileArtifactValue metadata =
        checkNotNull(
            actionExecutionContext.getInputMetadataProvider().getInputMetadata(primaryInput),
            "missing metadata for %s",
            primaryInput);
    return metadata.getType() == FileStateType.DIRECTORY
        ? SymlinkTargetType.DIRECTORY
        : SymlinkTargetType.FILE;
  }

  /**
   * Propagates metadata from the input artifact (symlink target) if possible.
   *
   * <p>This is an optimization that saves filesystem operations - we know the output is just a
   * symlink to the input, so we may be able to skip constructing its metadata from the filesystem.
   *
   * <p>In addition to reducing filesystem operations, this allows us to provide richer information
   * for the symlink metadata. For example, if the input metadata is a {@link
   * com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue}, the output
   * metadata will be as well.
   *
   * <p>In cases where propagating the input metadata is incorrect ({@linkplain Artifact#isDirectory
   * directory artifacts}) or cases where the input metadata cannot be obtained, this method does
   * nothing. The output symlink will be read back from the filesystem after this action finishes
   * executing.
   */
  public static void maybeInjectMetadata(Action symlinkAction, ActionExecutionContext ctx) {
    if (ctx.getActionFileSystem() != null) {
      return; // Action filesystems are responsible for their own metadata injection.
    }
    Artifact primaryInput = symlinkAction.getPrimaryInput();
    if (primaryInput == null || primaryInput.isDirectory()) {
      return;
    }
    FileArtifactValue metadata;
    try {
      metadata = ctx.getInputMetadataProvider().getInputMetadata(primaryInput);
    } catch (IOException e) {
      return;
    }
    if (metadata != null) {
      ctx.getOutputMetadataStore()
          .injectFile(
              symlinkAction.getPrimaryOutput(),
              primaryInput instanceof SourceArtifact
                  ? FileArtifactValue.createFromExistingWithResolvedPath(
                      metadata, primaryInput.getPath().asFragment())
                  : metadata);
    }
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    fp.addString(GUID);
  }

  @Override
  public String describeKey() {
    return String.format("GUID: %s\n\n", GUID);
  }

  @Override
  public String getMnemonic() {
    return "Copy";
  }

  @Override
  protected String getRawProgressMessage() {
    return "Copying %{input} to %{output}";
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setSymlinkAction(FailureDetails.SymlinkAction.newBuilder().setCode(detailedCode))
            .build());
  }

  @Override
  public PlatformInfo getExecutionPlatform() {
    return PlatformInfo.EMPTY_PLATFORM_INFO;
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    // SymlinkAction is platform agnostic.
    return ImmutableMap.of();
  }
}
