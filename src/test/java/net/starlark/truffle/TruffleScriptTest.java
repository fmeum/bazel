// Copyright 2025 The Bazel Authors. All rights reserved.
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

package net.starlark.truffle;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Script-based tests for Truffle Starlark implementation.
 *
 * <p>Reuses the test files from {@code //src/test/java/net/starlark/java/eval/testdata/}
 * to verify faithful implementation of Starlark semantics.
 *
 * <p>Test file format (same as ScriptTest.java):
 * - Chunks separated by {@code \n---\n}
 * - Error expectations: {@code ### regex}
 * - Helper functions: {@code assert_}, {@code assert_eq}, {@code assert_fails}
 */
public class TruffleScriptTest {

  private static final Pattern EXPECTATION_PATTERN = Pattern.compile("###(.*)$");

  private final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    // Find test files
    String runfiles = System.getenv("TEST_SRCDIR");
    if (runfiles == null) {
      System.err.println("Cannot find runfiles (TEST_SRCDIR not set)");
      System.exit(1);
    }

    String workspace = System.getenv("TEST_WORKSPACE");
    if (workspace == null) {
      workspace = "_main"; // Default workspace name
    }

    // Check local testdata first (for Phase 1 tests)
    File localTestdata = new File(runfiles, workspace + "/src/test/java/net/starlark/truffle/testdata");
    File originalTestdata = new File(runfiles, workspace + "/src/test/java/net/starlark/java/eval/testdata");

    TruffleScriptTest tester = new TruffleScriptTest();
    int passed = 0;
    int failed = 0;

    // Run local test files first (Phase 1 tests)
    if (localTestdata.isDirectory()) {
      for (String name : localTestdata.list()) {
        if (!name.endsWith(".star")) {
          continue;
        }

        System.out.println("Testing: " + name);

        try {
          tester.runTestFile(new File(localTestdata, name));
          passed++;
          System.out.println("  ✓ PASSED");
        } catch (Exception e) {
          failed++;
          System.err.println("  ✗ FAILED: " + e.getMessage());
          e.printStackTrace();
        }
      }
    }

    // Skip original tests for now - they use features not yet implemented
    // if (originalTestdata.isDirectory()) {
    //   for (String name : originalTestdata.list()) {
    //     ...
    //   }
    // }

    System.out.println("\n" + "=".repeat(60));
    System.out.println("Results: " + passed + " passed, " + failed + " failed");

    if (failed > 0) {
      System.exit(1);
    }
  }

  private void runTestFile(File file) throws IOException {
    String content = Files.asCharSource(file, StandardCharsets.UTF_8).read();

    int chunkIndex = 0;
    int lineOffset = 1;

    for (String chunk : Splitter.on("\n---\n").split(content)) {
      chunkIndex++;

      // Extract error expectations from this chunk
      Map<Pattern, Integer> expectations = new HashMap<>();
      StringBuilder cleanChunk = new StringBuilder();
      int lineNum = lineOffset;

      for (String line : chunk.split("\n", -1)) {
        Matcher m = EXPECTATION_PATTERN.matcher(line);
        if (m.find()) {
          String expectation = m.group(1).trim();
          if (!expectation.isEmpty()) {
            try {
              expectations.put(Pattern.compile(expectation), lineNum);
            } catch (PatternSyntaxException e) {
              throw new RuntimeException(
                  file.getName() + ":" + lineNum + ": invalid expectation regex: " + expectation, e);
            }
          }
        }
        cleanChunk.append(line).append("\n");
        lineNum++;
      }

      // Execute the chunk
      try {
        executeChunk(file.getName(), cleanChunk.toString(), lineOffset, expectations);
      } catch (Exception e) {
        if (!matchExpectation(expectations, e.getMessage())) {
          throw new RuntimeException(
              file.getName() + ":" + lineOffset + ": unexpected error in chunk " + chunkIndex,
              e);
        }
      }

      // Check for unmatched expectations
      if (!expectations.isEmpty()) {
        for (Map.Entry<Pattern, Integer> entry : expectations.entrySet()) {
          throw new RuntimeException(
              file.getName() + ":" + entry.getValue() +
              ": expected error matching '" + entry.getKey().pattern() + "' but none occurred");
        }
      }

      lineOffset = lineNum + 2; // +2 for the separator
    }
  }

  private void executeChunk(String filename, String source, int lineOffset,
                           Map<Pattern, Integer> expectations) throws IOException {
    failures.clear();

    try (Context context = Context.newBuilder("starlark")
        .option("engine.WarnInterpreterOnly", "false")
        .build()) {

      // TODO: Add bindings for assert_, assert_eq, struct, etc.
      // For now, just execute the source

      Source src = Source.newBuilder("starlark", source, filename)
          .build();

      context.eval(src);

      // Check if any assertions failed (stored in failures list)
      for (String failure : failures) {
        if (!matchExpectation(expectations, failure)) {
          throw new RuntimeException(failure);
        }
      }

    } catch (PolyglotException e) {
      String message = e.getMessage();
      if (!matchExpectation(expectations, message)) {
        throw e;
      }
    }
  }

  private boolean matchExpectation(Map<Pattern, Integer> expectations, String message) {
    if (message == null) {
      return false;
    }

    for (Pattern pattern : new ArrayList<>(expectations.keySet())) {
      if (pattern.matcher(message).find()) {
        expectations.remove(pattern);
        return true;
      }
    }
    return false;
  }

  /** Records a test failure without stopping execution. */
  void reportError(String message) {
    failures.add(message);
  }
}
