package com.google.devtools.build.lib.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class InMemoryPipeBenchmark {

  @Param({"8192"})
  public int bufferSize;

  @Param({"128"})
  public int writeSize;

  @Param({"1000"})
  public int writeCount;

  private byte[] data;

  @Setup(Level.Iteration)
  public void setup() {
    data = new byte[writeSize];
    new SecureRandom().nextBytes(data);
  }

  private byte[] readThroughPipe(InputStream in, OutputStream out)
      throws IOException, InterruptedException {
    var thread =
        Thread.startVirtualThread(
            () -> {
              try (out) {
                for (int i = 0; i < writeCount; i++) {
                  out.write(data);
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    byte[] result;
    try (in) {
      result = in.readAllBytes();
    }
    thread.join();
    return result;
  }

  @Benchmark
  public void inMemoryPipe(Blackhole blackhole) throws InterruptedException {
    for (int i = 0; i < 10; i++) {
      Thread.startVirtualThread(
              () -> {
                var pipe = new InMemoryPipe(bufferSize);
                try {
                  blackhole.consume(readThroughPipe(pipe.in(), pipe.out()));
                } catch (IOException | InterruptedException e) {
                  throw new IllegalStateException(e);
                }
              })
          .join();
    }
  }

  @Benchmark
  public void legacyPipe(Blackhole blackhole) throws InterruptedException {
    for (int i = 0; i < 10; i++) {
      Thread.startVirtualThread(
              () -> {
                try {
                  var in = new PipedInputStream(bufferSize);
                  var out = new PipedOutputStream(in);
                  blackhole.consume(readThroughPipe(in, out));
                } catch (IOException | InterruptedException e) {
                  throw new IllegalStateException(e);
                }
              })
          .join();
    }
  }
}
