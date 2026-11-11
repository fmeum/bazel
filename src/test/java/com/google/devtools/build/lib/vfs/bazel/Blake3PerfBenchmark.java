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

package com.google.devtools.build.lib.vfs.bazel;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.unix.NativePosixFilesServiceImpl;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 1,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xmx2g"})
public class Blake3PerfBenchmark {

  static {
    BazelHashFunctions.ensureRegistered();
  }

  @State(Scope.Thread)
  public static class BytesState {
    @Param({"JNI", "JNI_ONESHOT", "FFM_STREAM", "FFM_ONESHOT", "SHA256"})
    public String impl;

    @Param({"1", "64", "1024", "8192", "65536", "1048576"})
    public int size;

    byte[] data;

    @Setup(Level.Trial)
    public void setup() {
      data = new byte[size];
      new Random(42).nextBytes(data);
      // Cross-check all BLAKE3 variants.
      byte[] expected = Blake3HashFunction.INSTANCE.hashBytes(data).asBytes();
      check(expected, Blake3Experimental.jniOneShot(data, 0, size));
      check(expected, Blake3Experimental.ffmOneShot(data, 0, size));
      var h = new Blake3Experimental.FfmHasher();
      h.update(data, 0, size);
      check(expected, h.digestAndClose());
    }
  }

  @Benchmark
  public byte[] hashBytes(BytesState s) {
    return switch (s.impl) {
      case "JNI" -> Blake3HashFunction.INSTANCE.hashBytes(s.data).asBytes();
      case "JNI_ONESHOT" -> Blake3Experimental.jniOneShot(s.data, 0, s.data.length);
      case "FFM_STREAM" -> {
        var h = new Blake3Experimental.FfmHasher();
        h.update(s.data, 0, s.data.length);
        yield h.digestAndClose();
      }
      case "FFM_ONESHOT" -> Blake3Experimental.ffmOneShot(s.data, 0, s.data.length);
      case "SHA256" -> Hashing.sha256().hashBytes(s.data).asBytes();
      default -> throw new IllegalStateException(s.impl);
    };
  }

  @State(Scope.Thread)
  public static class FileState {
    @Param({
      "JNI_8K",
      "JNI_64K",
      "FFM_8K",
      "FFM_64K",
      "NATIVE_64K",
      "SHA256_8K",
      "SHA256_64K"
    })
    public String impl;

    @Param({"4096", "65536", "1048576", "67108864"})
    public int size;

    Path dir;
    Path file;
    PathFragment fragment;
    FileSystem blake3Fs;
    FileSystem sha256Fs;
    byte[] buf8k = new byte[8192];
    byte[] buf64k = new byte[65536];

    @Setup(Level.Trial)
    public void setup() throws IOException {
      dir = Files.createTempDirectory("blake3bench");
      file = dir.resolve("f");
      byte[] data = new byte[size];
      new Random(42).nextBytes(data);
      Files.write(file, data);
      fragment = PathFragment.create(file.toString());
      blake3Fs =
          new UnixFileSystem(BazelHashFunctions.BLAKE3, "", new NativePosixFilesServiceImpl());
      sha256Fs =
          new UnixFileSystem(DigestHashFunction.SHA256, "", new NativePosixFilesServiceImpl());
      byte[] expected = Blake3HashFunction.INSTANCE.hashBytes(data).asBytes();
      check(expected, blake3Fs.getDigest(fragment));
      check(expected, Blake3Experimental.hashFile(file.toString(), 65536));
      check(expected, ffmLoop(this, buf64k));
      check(expected, guavaLoop(this, blake3Fs, buf64k));
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
      Files.delete(file);
      Files.delete(dir);
    }
  }

  private static byte[] guavaLoop(FileState s, FileSystem fs, byte[] buffer) throws IOException {
    Hasher hasher = fs.getDigestFunction().getHashFunction().newHasher();
    try (InputStream in = fs.getPath(s.fragment).getInputStream()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        hasher.putBytes(buffer, 0, read);
      }
    }
    return hasher.hash().asBytes();
  }

  private static byte[] ffmLoop(FileState s, byte[] buffer) throws IOException {
    var hasher = new Blake3Experimental.FfmHasher();
    try (InputStream in = s.blake3Fs.getPath(s.fragment).getInputStream()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        hasher.update(buffer, 0, read);
      }
    }
    return hasher.digestAndClose();
  }

  @Benchmark
  public byte[] fileDigest(FileState s) throws IOException {
    return switch (s.impl) {
      case "JNI_8K" -> s.blake3Fs.getDigest(s.fragment);
      case "JNI_64K" -> guavaLoop(s, s.blake3Fs, s.buf64k);
      case "FFM_8K" -> ffmLoop(s, s.buf8k);
      case "FFM_64K" -> ffmLoop(s, s.buf64k);
      case "NATIVE_64K" -> Blake3Experimental.hashFile(s.file.toString(), 65536);
      case "SHA256_8K" -> s.sha256Fs.getDigest(s.fragment);
      case "SHA256_64K" -> guavaLoop(s, s.sha256Fs, s.buf64k);
      default -> throw new IllegalStateException(s.impl);
    };
  }

  private static void check(byte[] expected, byte[] actual) {
    if (!Arrays.equals(expected, actual)) {
      throw new IllegalStateException(
          "digest mismatch: "
              + HexFormat.of().formatHex(expected)
              + " vs "
              + HexFormat.of().formatHex(actual));
    }
  }
}
