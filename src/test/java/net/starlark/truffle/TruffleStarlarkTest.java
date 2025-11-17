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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for Truffle-based Starlark implementation. */
@RunWith(JUnit4.class)
public class TruffleStarlarkTest {

  @Test
  public void testPrintHelloWorld() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (Context context = Context.newBuilder()
        .allowAllAccess(true)
        .out(out)
        .err(out)
        .build()) {

      // Register the language manually since service discovery isn't working
      context.initialize("starlark");

      Source source = Source.newBuilder("starlark", "print(\"Hello world!\")", "test.star")
          .buildLiteral();

      context.eval(source);

      String output = out.toString().trim();
      assertEquals("Hello world!", output);
    } catch (Exception e) {
      // For now, just verify the language loads
      // The annotation processor issue needs to be resolved for full functionality
      throw new RuntimeException("Language loading failed - annotation processor needs investigation", e);
    }
  }

  @Test
  public void testPrintSimpleString() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (Context context = Context.newBuilder("starlark")
        .out(out)
        .err(out)
        .build()) {

      Source source = Source.newBuilder("starlark", "print(\"Test\")", "test.star")
          .buildLiteral();

      context.eval(source);

      String output = out.toString().trim();
      assertEquals("Test", output);
    }
  }

  @Test
  public void testLanguageRegistration() {
    try (Context context = Context.newBuilder("starlark").build()) {
      // If we get here without an exception, the language is properly registered
      assertTrue(true);
    }
  }
}
