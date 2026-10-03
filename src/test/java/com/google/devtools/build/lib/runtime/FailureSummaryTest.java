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
package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.ActionExecutedEvent.ErrorTiming;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.exec.SpawnExecException;
import com.google.devtools.build.lib.repository.RepositoryFailedEvent;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FailureSummary}. */
@RunWith(JUnit4.class)
public final class FailureSummaryTest {

  private static final FailureDetail SPAWN_FAILURE =
      FailureDetail.newBuilder()
          .setMessage("spawn failed")
          .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
          .build();

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
  private final FailureSummary summary = new FailureSummary(/* maxOutputBytes= */ 1024 * 1024);
  private int nextId;

  @Test
  public void findCause_prefersErrorLineOverSurroundingLines() {
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of(
                    "In file included from foo.h:1:",
                    "foo.cc:3:12: error: expected ';'",
                    "foo.cc:9:1: error: unknown type name 'x'",
                    "2 errors generated."),
                ImmutableList.of()))
        .isEqualTo("foo.cc:3:12: error: expected ';'");
  }

  @Test
  public void findCause_recognizesCommonDiagnosticFormats() {
    ImmutableList<String> diagnostics =
        ImmutableList.of(
            "pkg/foo.cc:3:12: error: expected ';'",
            "pkg/foo.cc:3:12: fatal error: 'foo.h' file not found",
            "Foo.java:12: error: cannot find symbol",
            "error[E0308]: mismatched types",
            "error: could not compile `foo`",
            "foo.cpp(12): error C2065: 'x': undeclared identifier",
            "src/a.ts(1,2): error TS2322: Type 'string' is not assignable to type 'number'.",
            "clang: error: linker command failed with exit code 1",
            "ld.lld: error: undefined symbol: foo",
            "ValueError: invalid literal for int() with base 10: 'x'",
            "ERROR: Something went wrong",
            "e: file:///pkg/Foo.kt:3:5 Unresolved reference: foo",
            "Exception in thread \"main\" java.lang.IllegalStateException: boom");
    for (String diagnostic : diagnostics) {
      assertThat(
              FailureSummary.findCause(
                  ImmutableList.of("preamble", diagnostic, "trailer"), ImmutableList.of()))
          .isEqualTo(diagnostic);
    }
  }

  @Test
  public void findCause_skipsWarningsNotesAndSummaries() {
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of(
                    "foo.cc:3:5: warning: unused variable 'x' [-Wunused-variable]",
                    "foo.cc:1:1: note: expanded from macro 'X'",
                    "cc1plus: all warnings being treated as errors",
                    "2 errors generated."),
                ImmutableList.of()))
        .isEqualTo("2 errors generated.");
  }

  @Test
  public void findCause_fallsBackToLocationLineWithoutErrorLabel() {
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of(
                    "# example.com/foo",
                    "./main.go:5:2: undefined: x",
                    "./main.go:7:2: undefined: y"),
                ImmutableList.of()))
        .isEqualTo("./main.go:5:2: undefined: x");
  }

  @Test
  public void findCause_fallsBackToLastNonEmptyLine() {
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of(
                    "warning: something benign", "tool: fatal: input config.yaml not found", ""),
                ImmutableList.of()))
        .isEqualTo("tool: fatal: input config.yaml not found");
  }

  @Test
  public void findCause_searchesStderrBeforeStdout() {
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of("just noise"), ImmutableList.of("error: from stdout")))
        .isEqualTo("error: from stdout");
    assertThat(
            FailureSummary.findCause(
                ImmutableList.of("error: from stderr"), ImmutableList.of("error: from stdout")))
        .isEqualTo("error: from stderr");
    assertThat(FailureSummary.findCause(ImmutableList.of(), ImmutableList.of("only stdout")))
        .isEqualTo("only stdout");
  }

  @Test
  public void findCause_truncatesLongLines() {
    String line = "error: " + "x".repeat(FailureSummary.MAX_CAUSE_LENGTH);
    assertThat(FailureSummary.findCause(ImmutableList.of(line), ImmutableList.of()))
        .isEqualTo(line.substring(0, FailureSummary.MAX_CAUSE_LENGTH) + "...");
  }

  @Test
  public void findCause_noOutput_returnsNull() {
    assertThat(FailureSummary.findCause(ImmutableList.of(), ImmutableList.of())).isNull();
    assertThat(FailureSummary.findCause(ImmutableList.of(""), ImmutableList.of(""))).isNull();
  }

  @Test
  public void causeFromMessage_keepsStructuredLines() {
    assertThat(
            FailureSummary.causeFromMessage(
                """
                Error downloading x.tar.gz from all 2 URLs:
                  https://a.example/x.tar.gz: GET returned 404 Not Found

                  https://b.example/x.tar.gz: Unknown host: b.example
                """))
        .containsExactly(
            "Error downloading x.tar.gz from all 2 URLs:",
            "https://a.example/x.tar.gz: GET returned 404 Not Found",
            "https://b.example/x.tar.gz: Unknown host: b.example")
        .inOrder();
    assertThat(FailureSummary.causeFromMessage("")).isEmpty();
    assertThat(FailureSummary.causeFromMessage(null)).isEmpty();
  }

  @Test
  public void causeFromMessage_capsLines() {
    String message =
        IntStream.range(0, FailureSummary.MAX_CAUSE_LINES + 5)
            .mapToObj(i -> "line " + i)
            .collect(joining("\n"));

    ImmutableList<String> lines = FailureSummary.causeFromMessage(message);

    assertThat(lines).hasSize(FailureSummary.MAX_CAUSE_LINES + 1);
    assertThat(lines.get(0)).isEqualTo("line 0");
    assertThat(lines.get(FailureSummary.MAX_CAUSE_LINES - 1))
        .isEqualTo("line " + (FailureSummary.MAX_CAUSE_LINES - 1));
    assertThat(lines.get(FailureSummary.MAX_CAUSE_LINES)).isEqualTo("...");
  }

  @Test
  public void render_nothingRecorded_returnsNull() {
    assertThat(summary.render()).isNull();
  }

  @Test
  public void actionFailed_rendersLabelDescriptionExitCodeAndCause() throws Exception {
    summary.actionFailed(
        failedSpawn("Compiling foo.cc", /* stderr= */ "foo.cc:3:12: error: expected ';'\n"));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
                  foo.cc:3:12: error: expected ';'\
            """);
  }

  @Test
  public void actionFailed_spawnFailureWrappedAsCause_stillReportsExitCode() throws Exception {
    NullAction action = action("Compiling foo.cc");
    ActionExecutionException exception =
        ActionExecutionException.fromExecException(
            new SpawnExecException(
                "bash failed", spawnResult(""), /* forciblyRunRemotely= */ false),
            action);
    summary.actionFailed(
        failedAction(action, exception, /* stderr= */ "error: boom\n", /* stdout= */ null));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
                  error: boom\
            """);
  }

  @Test
  public void actionFailed_withoutOutput_usesSpawnFailureMessage() throws Exception {
    NullAction action = action("Compiling foo.cc");
    SpawnResult spawnResult =
        spawnResult(/* failureMessage= */ "Remote execution failed: DEADLINE_EXCEEDED\ndetails");
    summary.actionFailed(
        failedAction(
            action, spawnFailure(action, spawnResult), /* stderr= */ null, /* stdout= */ null));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
                  Remote execution failed: DEADLINE_EXCEEDED\
            """);
  }

  @Test
  public void actionFailed_withoutOutputOrFailureMessage_hasNoCauseLine() throws Exception {
    summary.actionFailed(failedSpawn("Compiling foo.cc", /* stderr= */ null));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)\
            """);
  }

  @Test
  public void actionFailed_nonSpawnFailure_usesExceptionMessage() throws Exception {
    NullAction action = action("Compiling foo.cc");
    ActionExecutionException exception =
        new ActionExecutionException(
            "Compiling foo.cc failed: not all outputs were created or valid",
            action,
            /* catastrophe= */ false,
            DetailedExitCode.of(SPAWN_FAILURE));
    summary.actionFailed(failedAction(action, exception, /* stderr= */ null, /* stdout= */ null));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc
                  not all outputs were created or valid\
            """);
  }

  @Test
  public void actionFailed_sameActionTwice_countedOnce() throws Exception {
    NullAction action = action("Compiling foo.cc");
    ActionExecutedEvent event =
        failedAction(
            action,
            spawnFailure(action, spawnResult("")),
            /* stderr= */ "error: boom\n",
            /* stdout= */ null);
    summary.actionFailed(event);
    summary.actionFailed(event);

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
                  error: boom\
            """);
  }

  @Test
  public void actionFailed_largeOutput_inspectsOnlyHeadAndTail() throws Exception {
    FailureSummary smallSummary = new FailureSummary(/* maxOutputBytes= */ 64);
    // Neither of the two long lines fits into a 32 byte head or tail, so they are dropped as
    // partial lines and only the last one remains.
    String stderr = "x".repeat(100) + "\n" + "y".repeat(100) + "\n" + "error: at the end\n";
    smallSummary.actionFailed(failedSpawn("Compiling foo.cc", stderr));

    assertThat(smallSummary.render())
        .isEqualTo(
            """
            1 action failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
                  error: at the end\
            """);
  }

  @Test
  public void repositoryFailed_rendersRepositoryAndStructuredCause() {
    summary.repositoryFailed(
        new RepositoryFailedEvent(
            RepositoryName.createUnvalidated("+http_archive+foo"),
            """
            Error downloading x.tar.gz from all 2 URLs:
              https://a.example/x.tar.gz: GET returned 404 Not Found
              https://b.example/x.tar.gz: Unknown host: b.example\
            """));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 repository fetch failed:
              @@+http_archive+foo: Fetching repository
                  Error downloading x.tar.gz from all 2 URLs:
                    https://a.example/x.tar.gz: GET returned 404 Not Found
                    https://b.example/x.tar.gz: Unknown host: b.example\
            """);
  }

  @Test
  public void render_countsActionsAndRepositories() throws Exception {
    summary.actionFailed(failedSpawn("Compiling foo.cc", /* stderr= */ null));
    summary.repositoryFailed(
        new RepositoryFailedEvent(RepositoryName.createUnvalidated("foo"), "no foo"));
    summary.repositoryFailed(
        new RepositoryFailedEvent(RepositoryName.createUnvalidated("bar"), "no bar"));

    assertThat(summary.render())
        .isEqualTo(
            """
            1 action and 2 repository fetches failed:
              //null/action:owner: Compiling foo.cc (exit code 1)
              @@foo: Fetching repository
                  no foo
              @@bar: Fetching repository
                  no bar\
            """);
  }

  @Test
  public void render_capsEntriesAndCountsTheRest() {
    for (int i = 0; i < FailureSummary.MAX_ENTRIES + 2; i++) {
      summary.repositoryFailed(
          new RepositoryFailedEvent(RepositoryName.createUnvalidated("repo" + i), "no repo" + i));
    }

    String rendered = summary.render();
    assertThat(rendered)
        .startsWith((FailureSummary.MAX_ENTRIES + 2) + " repository fetches failed:");
    assertThat(rendered)
        .contains("@@repo" + (FailureSummary.MAX_ENTRIES - 1) + ": Fetching repository");
    assertThat(rendered).doesNotContain("@@repo" + FailureSummary.MAX_ENTRIES + ":");
    assertThat(rendered).endsWith("\n  ... and 2 more");
  }

  private ActionExecutedEvent failedSpawn(String progressMessage, @Nullable String stderr)
      throws IOException {
    NullAction action = action(progressMessage);
    return failedAction(action, spawnFailure(action, spawnResult("")), stderr, /* stdout= */ null);
  }

  private ActionExecutedEvent failedAction(
      NullAction action,
      ActionExecutionException exception,
      @Nullable String stderr,
      @Nullable String stdout)
      throws IOException {
    Artifact output = action.getPrimaryOutput();
    return new ActionExecutedEvent(
        output.getExecPath(),
        action,
        exception,
        output.getPath(),
        output,
        /* primaryOutputMetadata= */ null,
        writeOutput("stdout", stdout),
        writeOutput("stderr", stderr),
        ErrorTiming.AFTER_EXECUTION,
        /* startTime= */ null,
        /* endTime= */ null);
  }

  @Nullable
  private Path writeOutput(String name, @Nullable String content) throws IOException {
    if (content == null) {
      return null;
    }
    Path path = fs.getPath("/exec/out/_tmp/actions/" + name + "-" + nextId++);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, UTF_8, content);
    return path;
  }

  private NullAction action(String progressMessage) {
    Artifact output = ActionsTestUtil.createArtifact(outputRoot, "out" + nextId++ + ".o");
    return new NullAction(output) {
      @Override
      protected String getRawProgressMessage() {
        return progressMessage;
      }
    };
  }

  private static SpawnResult spawnResult(String failureMessage) {
    return new SpawnResult.Builder()
        .setRunnerName("test")
        .setStatus(Status.NON_ZERO_EXIT)
        .setExitCode(1)
        .setFailureDetail(SPAWN_FAILURE)
        .setFailureMessage(failureMessage)
        .build();
  }

  private static ActionExecutionException spawnFailure(NullAction action, SpawnResult result) {
    return new SpawnExecException("bash failed", result, /* forciblyRunRemotely= */ false)
        .toActionExecutionException(action);
  }
}
