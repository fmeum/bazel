// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util;

import static org.junit.Assert.fail;

import com.google.common.base.Throwables;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InMemoryPipeTest {
  private static final long SEED = 987654321L;
  private static final int REPETITIONS = 100;
  private static final int OPS = 1000;
  private static final int CAPACITY = 64;

  private static final RandomGeneratorFactory<? extends RandomGenerator.JumpableGenerator> factory =
      RandomGeneratorFactory.of("Xoroshiro128PlusPlus");

  @Test
  public void testManyPipes() throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    LongAdder counter = new LongAdder();
    for (int i = 0; i < 16; i++) {
      threads.add(
          Thread.startVirtualThread(
              () -> {
                InMemoryPipe pipe = new InMemoryPipe(CAPACITY);
                try {
                  counter.add(
                      readThroughPipe(
                              pipe.out(),
                              out -> {
                                for (int j = 0; j < 1000; j++) {
                                  out.write(j);
                                }
                              },
                              pipe.in(),
                              InputStream::readAllBytes)
                          .length);
                } catch (IOException | InterruptedException e) {
                  throw new IllegalStateException(e);
                }
              }));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    if (counter.sum() == 0) {
      fail("Expected non-zero sum");
    }
  }

  @Test
  public void testManyLegacyPipes() throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    LongAdder counter = new LongAdder();
    for (int i = 0; i < 10000; i++) {
      threads.add(
          Thread.startVirtualThread(
              () -> {
                try {
                  PipedInputStream legacyIn = new PipedInputStream(CAPACITY);
                  PipedOutputStream legacyOut = new PipedOutputStream(legacyIn);
                  counter.add(
                      readThroughPipe(
                              legacyOut,
                              out -> {
                                for (int j = 0; j < 1000; j++) {
                                  out.write(j);
                                }
                              },
                              legacyIn,
                              InputStream::readAllBytes)
                          .length);
                } catch (IOException | InterruptedException e) {
                  throw new IllegalStateException(e);
                }
              }));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    if (counter.sum() == 0) {
      fail("Expected non-zero sum");
    }
  }

  @Test
  public void stressTestWrite() throws IOException, InterruptedException {
    var rng = factory.create(SEED);
    for (int i = 0; i < REPETITIONS; i++) {
      // Use a small capacity to increase the likelihood of contention.
      var pipe = new InMemoryPipe(CAPACITY);
      byte[] actualResult =
          readThroughPipe(
              pipe.out(),
              out -> runWriteOps(out, OPS, rng.copy()),
              pipe.in(),
              InputStream::readAllBytes);

      var legacyIn = new PipedInputStream(CAPACITY);
      var legacyOut = new PipedOutputStream(legacyIn);
      byte[] expectedResult =
          readThroughPipe(
              legacyOut,
              out -> runWriteOps(out, OPS, rng.copy()),
              legacyIn,
              InputStream::readAllBytes);

      if (!Arrays.equals(expectedResult, actualResult)) {
        fail("Expected: " + expectedResult.length + " but got: " + actualResult.length);
      }

      rng.jump();
    }
  }

  private static byte[] readThroughPipe(
      OutputStream out, WriterOp writeOp, InputStream in, ReaderOp readOp)
      throws IOException, InterruptedException {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    var thread =
        Thread.startVirtualThread(
            () -> {
              try (out) {
                writeOp.writeTo(out);
              } catch (IOException e) {
                exception.set(e);
              }
            });
    byte[] result;
    try (in) {
      result = readOp.readFrom(in);
    }
    thread.join();
    if (exception.get() != null) {
      Throwables.throwIfInstanceOf(exception.get(), IOException.class);
    }
    return result;
  }

  interface WriterOp {
    void writeTo(OutputStream out) throws IOException;
  }

  interface ReaderOp {
    byte[] readFrom(InputStream in) throws IOException;
  }

  private static void runWriteOps(OutputStream out, int ops, RandomGenerator rng)
      throws IOException {
    byte[] buffer = new byte[2 * CAPACITY];
    rng.nextBytes(buffer);
    for (int i = 0; i < ops; i++) {
      if (rng.nextBoolean()) {
        int length = rng.nextInt(buffer.length);
        int offset = rng.nextInt(buffer.length - length);
        out.write(buffer, offset, length);
      } else {
        out.write(buffer[rng.nextInt(buffer.length)]);
      }
    }
  }

  private static void runReadOps(InputStream out, int ops, RandomGenerator rng) throws IOException {
    byte[] buffer = new byte[2 * CAPACITY];
    rng.nextBytes(buffer);
    for (int i = 0; i < ops; i++) {
      switch (rng.nextInt(3)) {
        case 0 -> {
          int length = rng.nextInt(buffer.length);
          int offset = rng.nextInt(buffer.length - length);
          out.read(buffer, offset, length);
        }
      }
    }
  }
}
