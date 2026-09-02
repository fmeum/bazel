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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.SpawnActionExecutionException;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.exec.SpawnExecException;
import com.google.devtools.build.lib.repository.RepositoryFailedEvent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Collects the actions and repository fetches that failed during a build and renders them as a
 * short summary: one line naming each failure, followed by the line of tool output that most likely
 * states its cause.
 *
 * <p>The UI prints the summary right before the final build status so that the causes are the last
 * thing on screen rather than buried in the scroll-back buffer between command lines and warnings.
 */
final class FailureSummary {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** The number of failures listed in detail; further failures are only counted. */
  @VisibleForTesting static final int MAX_ENTRIES = 10;

  /** Cause lines longer than this are cut off. */
  @VisibleForTesting static final int MAX_CAUSE_LENGTH = 500;

  /** The number of lines shown of a cause that spans several lines. */
  @VisibleForTesting static final int MAX_CAUSE_LINES = 10;

  /**
   * A line stating an error, in the formats used by most compilers and tools:
   *
   * <pre>
   * pkg/foo.cc:3:12: error: expected ';'                  gcc, clang, javac, swiftc, protoc, lld
   * error[E0308]: mismatched types                        rustc
   * foo.cpp(12): error C2065: 'x': undeclared             MSVC, tsc
   * clang: error: linker command failed                   compiler drivers
   * ValueError: invalid literal for int()                 Python
   * e: file:///pkg/Foo.kt:3:5 Unresolved reference        kotlinc
   * Exception in thread "main" java.lang.IllegalStateException
   * </pre>
   */
  private static final Pattern ERROR_LINE =
      Pattern.compile(
          "(?:\\S.*?:\\s*)?(?:fatal\\s+)?\\w*error(?:\\[\\w+\\]|\\s+[A-Z]+\\d+)?:\\s"
              + "|e:\\s"
              + "|Exception in thread ",
          Pattern.CASE_INSENSITIVE);

  /**
   * A line starting with a source location but without an error label, as printed by tools such as
   * the Go compiler ("./main.go:5:2: undefined: x"). Warnings and notes are excluded.
   */
  private static final Pattern LOCATION_LINE =
      Pattern.compile(
          "\\S+:\\d+(?::\\d+)?:\\s+(?!(?:warning|note|remark|info)\\b)\\S",
          Pattern.CASE_INSENSITIVE);

  private static final Splitter LINE_SPLITTER = Splitter.on('\n').trimResults();

  /** One failure: what failed, and the lines that most likely say why (possibly none). */
  private record Entry(String headline, ImmutableList<String> cause) {}

  private final int maxOutputBytes;

  private final List<Entry> entries = new ArrayList<>();
  private final Set<String> failedActionOutputs = new HashSet<>();
  private int failedActions;
  private int failedRepositories;

  /**
   * @param maxOutputBytes how much of a failed action's stdout and stderr to inspect for the cause
   */
  FailureSummary(int maxOutputBytes) {
    this.maxOutputBytes = maxOutputBytes;
  }

  /** Records a failed action. Events without an exception are ignored. */
  synchronized void actionFailed(ActionExecutedEvent event) {
    ActionExecutionException exception = event.getException();
    if (exception == null) {
      return;
    }
    Action action = event.getAction();
    // Shared actions and actions retried after rewinding can be reported more than once.
    if (!failedActionOutputs.add(action.getPrimaryOutput().getExecPathString())) {
      return;
    }
    failedActions++;
    if (entries.size() >= MAX_ENTRIES) {
      return;
    }

    StringBuilder headline = new StringBuilder();
    ActionOwner owner = action.getOwner();
    Label label = owner != null ? owner.getLabel() : null;
    if (label != null) {
      headline.append(label).append(": ");
    }
    headline.append(action.describe());
    SpawnResult spawnResult = findSpawnResult(exception);
    if (spawnResult != null) {
      headline.append(" (").append(describeStatus(spawnResult)).append(')');
    }

    String cause = null;
    try {
      cause = findCause(readLines(event.getStderrPath()), readLines(event.getStdoutPath()));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to read the output of failed action %s", action.describe());
    }
    if (cause == null) {
      // Without output, fall back to what the spawn runner or the action itself reported. For a
      // spawn, the exception message only repeats the command line, which is not a cause.
      cause =
          spawnResult != null
              ? firstLine(spawnResult.getFailureMessage())
              : firstLine(stripPrefix(exception.getMessage(), action.describe() + " failed: "));
    }
    entries.add(
        new Entry(
            headline.toString(), cause != null ? ImmutableList.of(cause) : ImmutableList.of()));
  }

  /** Records a failed repository fetch. */
  synchronized void repositoryFailed(RepositoryFailedEvent event) {
    failedRepositories++;
    if (entries.size() >= MAX_ENTRIES) {
      return;
    }
    entries.add(
        new Entry(
            event.getRepo().getNameWithAt() + ": Fetching repository",
            causeFromMessage(event.getMessage())));
  }

  /** Returns the rendered summary, or null if no failure was recorded. */
  @Nullable
  synchronized String render() {
    if (failedActions == 0 && failedRepositories == 0) {
      return null;
    }
    List<String> counts = new ArrayList<>(2);
    if (failedActions > 0) {
      counts.add(failedActions + (failedActions == 1 ? " action" : " actions"));
    }
    if (failedRepositories > 0) {
      counts.add(
          failedRepositories
              + (failedRepositories == 1 ? " repository fetch" : " repository fetches"));
    }
    StringBuilder summary = new StringBuilder(String.join(" and ", counts)).append(" failed:");
    for (Entry entry : entries) {
      summary.append("\n  ").append(entry.headline());
      String indent = "\n      ";
      for (String line : entry.cause()) {
        summary.append(indent).append(line);
        // Continuation lines are indented further to keep the structure of the message, e.g.
        // one line per URL that failed to download.
        indent = "\n        ";
      }
    }
    int more = failedActions + failedRepositories - entries.size();
    if (more > 0) {
      summary.append("\n  ... and ").append(more).append(" more");
    }
    return summary.toString();
  }

  /**
   * Returns the result of the spawn whose failure caused the given exception, or null if the action
   * did not fail because of a spawn.
   */
  @Nullable
  private static SpawnResult findSpawnResult(ActionExecutionException exception) {
    if (exception instanceof SpawnActionExecutionException spawnException) {
      return spawnException.getSpawnResult();
    }
    // Actions that build their own message, such as CppCompileAction, keep the spawn failure as
    // the cause (see ActionExecutionException.fromExecException).
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SpawnExecException spawnExecException) {
        return spawnExecException.getSpawnResult();
      }
    }
    return null;
  }

  private static String describeStatus(SpawnResult result) {
    return switch (result.status()) {
      case NON_ZERO_EXIT -> "exit code " + result.exitCode();
      case TIMEOUT -> "timed out";
      case OUT_OF_MEMORY -> "out of memory";
      default -> Ascii.toLowerCase(result.status().name()).replace('_', ' ');
    };
  }

  /**
   * Returns the line of a failed action's output that most likely states the cause of the failure:
   * the first line that looks like an error diagnostic, else the first line that starts with a
   * source location, else the last non-empty line, which is where most tools say why they gave up.
   * Stderr is searched before stdout at every step. Returns null if there is no output.
   */
  @Nullable
  @VisibleForTesting
  static String findCause(List<String> stderr, List<String> stdout) {
    for (Pattern pattern : ImmutableList.of(ERROR_LINE, LOCATION_LINE)) {
      for (List<String> lines : ImmutableList.of(stderr, stdout)) {
        for (String line : lines) {
          if (pattern.matcher(line).lookingAt()) {
            return truncate(line);
          }
        }
      }
    }
    for (List<String> lines : ImmutableList.of(stderr, stdout)) {
      for (String line : Lists.reverse(lines)) {
        if (!line.isBlank()) {
          return truncate(line);
        }
      }
    }
    return null;
  }

  /**
   * Returns the non-empty lines of a repository fetch error message, which may be structured, such
   * as one line per URL that failed to download. At most {@link #MAX_CAUSE_LINES} lines are kept,
   * followed by an ellipsis if there were more.
   */
  @VisibleForTesting
  static ImmutableList<String> causeFromMessage(@Nullable String message) {
    if (message == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    int count = 0;
    for (String line : LINE_SPLITTER.split(message)) {
      if (line.isEmpty()) {
        continue;
      }
      if (count == MAX_CAUSE_LINES) {
        lines.add("...");
        break;
      }
      lines.add(truncate(line));
      count++;
    }
    return lines.build();
  }

  /**
   * Reads the lines of the given output file, trimmed of surrounding whitespace. Of a file larger
   * than the configured limit, only the beginning and the end are read, minus the two lines cut in
   * half.
   */
  private ImmutableList<String> readLines(@Nullable Path path) throws IOException {
    if (path == null) {
      return ImmutableList.of();
    }
    long size = path.getFileSize();
    if (size == 0) {
      return ImmutableList.of();
    }
    if (size <= maxOutputBytes) {
      return ImmutableList.copyOf(
          LINE_SPLITTER.split(new String(FileSystemUtils.readContent(path), UTF_8)));
    }
    int chunk = Math.max(maxOutputBytes, 0) / 2;
    byte[] head = new byte[chunk];
    byte[] tail = new byte[chunk];
    try (InputStream in = path.getInputStream()) {
      ByteStreams.readFully(in, head);
      ByteStreams.skipFully(in, size - 2L * chunk);
      ByteStreams.readFully(in, tail);
    }
    List<String> headLines = LINE_SPLITTER.splitToList(new String(head, UTF_8));
    List<String> tailLines = LINE_SPLITTER.splitToList(new String(tail, UTF_8));
    return ImmutableList.<String>builder()
        .addAll(headLines.subList(0, headLines.size() - 1))
        .addAll(tailLines.subList(1, tailLines.size()))
        .build();
  }

  @Nullable
  private static String firstLine(@Nullable String text) {
    if (text == null) {
      return null;
    }
    return LINE_SPLITTER
        .splitToStream(text)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .map(FailureSummary::truncate)
        .orElse(null);
  }

  @Nullable
  private static String stripPrefix(@Nullable String text, String prefix) {
    if (text == null) {
      return null;
    }
    return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
  }

  private static String truncate(String line) {
    return line.length() <= MAX_CAUSE_LENGTH ? line : line.substring(0, MAX_CAUSE_LENGTH) + "...";
  }
}
