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
package com.google.devtools.build.lib.unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares {@link StringUnsafe#isAscii} with the JDK's intrinsic for {@code
 * StringCoding.hasNegatives} and with the incubating Vector API.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgsAppend = {
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-modules=jdk.incubator.vector"
    })
public class IsAsciiBenchmark {
  private static final int STRINGS_PER_INVOCATION = 256;

  /** Either a fixed string length or "mixed" for a distribution of path-like lengths. */
  @Param({"mixed", "4", "8", "16", "32", "64", "128", "1024"})
  public String length;

  private String[] strings;

  @Setup
  public void setup() {
    Random random = new Random(42);
    strings = new String[STRINGS_PER_INVOCATION];
    for (int i = 0; i < strings.length; i++) {
      int stringLength;
      if (length.equals("mixed")) {
        double p = random.nextDouble();
        if (p < 0.5) {
          stringLength = 3 + random.nextInt(18);
        } else if (p < 0.85) {
          stringLength = 20 + random.nextInt(60);
        } else {
          stringLength = 80 + random.nextInt(120);
        }
      } else {
        stringLength = Integer.parseInt(length);
      }
      char[] chars = new char[stringLength];
      for (int j = 0; j < stringLength; j++) {
        chars[j] = (char) (' ' + random.nextInt('\u007f' - ' '));
      }
      strings[i] = new String(chars);
    }
    for (String s : strings) {
      String nonAscii = s + "\u0080";
      if (!hasNegativesIsAscii(s)
          || hasNegativesIsAscii(nonAscii)
          || !vectorIsAscii(s)
          || vectorIsAscii(nonAscii)) {
        throw new IllegalStateException("Implementations disagree on: " + s);
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(STRINGS_PER_INVOCATION)
  public int stringUnsafe() {
    int count = 0;
    for (String s : strings) {
      if (StringUnsafe.isAscii(s)) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  @OperationsPerInvocation(STRINGS_PER_INVOCATION)
  public int hasNegatives() {
    int count = 0;
    for (String s : strings) {
      if (hasNegativesIsAscii(s)) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  @OperationsPerInvocation(STRINGS_PER_INVOCATION)
  public int vector() {
    int count = 0;
    for (String s : strings) {
      if (vectorIsAscii(s)) {
        count++;
      }
    }
    return count;
  }

  private static final MethodHandle HAS_NEGATIVES;

  static {
    try {
      Method hasNegatives =
          Class.forName("java.lang.StringCoding")
              .getDeclaredMethod("hasNegatives", byte[].class, int.class, int.class);
      hasNegatives.setAccessible(true);
      HAS_NEGATIVES = MethodHandles.lookup().unreflect(hasNegatives);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean hasNegativesIsAscii(String s) {
    if (StringUnsafe.getCoder(s) != StringUnsafe.LATIN1) {
      return false;
    }
    byte[] bytes = StringUnsafe.getByteArray(s);
    try {
      return !(boolean) HAS_NEGATIVES.invokeExact(bytes, 0, bytes.length);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  private static boolean vectorIsAscii(String s) {
    if (StringUnsafe.getCoder(s) != StringUnsafe.LATIN1) {
      return false;
    }
    byte[] bytes = StringUnsafe.getByteArray(s);
    int length = bytes.length;
    int vectorLength = SPECIES.length();
    if (length < vectorLength) {
      return StringUnsafe.isAscii(s);
    }
    // Start with the last vector, which may overlap with the ones checked in the loop.
    ByteVector acc = ByteVector.fromArray(SPECIES, bytes, length - vectorLength);
    for (int i = 0; i < length - vectorLength; i += vectorLength) {
      acc = acc.or(ByteVector.fromArray(SPECIES, bytes, i));
    }
    return acc.reduceLanes(VectorOperators.OR) >= 0;
  }
}
