// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

package com.google.devtools.build.buildjar.instrumentation;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.google.devtools.build.buildjar.InvalidCommandLineException;
import com.google.devtools.build.buildjar.JavaLibraryBuildRequest;
import com.google.devtools.build.buildjar.jarhelper.JarCreator;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.instr.ClassInstrumenter;
import org.jacoco.core.internal.instr.IProbeArrayStrategy;
import org.jacoco.core.internal.instr.InstrSupport;
import org.jacoco.core.internal.instr.ProbeArrayStrategyFactory;
import org.jacoco.core.runtime.IExecutionDataAccessorGenerator;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

/** Instruments compiled java classes using Jacoco instrumentation library. */
public final class JacocoInstrumentationProcessor {

  public static JacocoInstrumentationProcessor create(List<String> args)
      throws InvalidCommandLineException {
    if (args.size() < 1) {
      throw new InvalidCommandLineException(
          "Number of arguments for Jacoco instrumentation should be 1+ (given "
              + args.size()
              + ": metadataOutput [filters*].");
    }

    // ignoring filters, they weren't used in the previous implementation
    // TODO(bazel-team): filters should be correctly handled
    return new JacocoInstrumentationProcessor(args.get(0));
  }

  private Path instrumentedClassesDirectory;
  private final String coverageInformation;
  private final boolean isNewCoverageImplementation;

  private JacocoInstrumentationProcessor(String coverageInfo) {
    this.coverageInformation = coverageInfo;
    // This is part of the new Java coverage implementation where JacocoInstrumentationProcessor
    // receives a file that includes the relative paths of the uninstrumented Java files, instead
    // of the metadata jar.
    this.isNewCoverageImplementation = coverageInfo.endsWith(".txt");
  }

  public boolean isNewCoverageImplementation() {
    return isNewCoverageImplementation;
  }

  /**
   * Instruments classes using Jacoco and keeps copies of uninstrumented class files in
   * jacocoMetadataDir, to be zipped up in the output file jacocoMetadataOutput.
   */
  public void processRequest(JavaLibraryBuildRequest build, JarCreator jar) throws IOException {
    // Use a directory for coverage metadata  that is unique to each built jar. Avoids
    // multiple threads performing read/write/delete actions on the instrumented classes directory.
    instrumentedClassesDirectory = getMetadataDirRelativeToJar(build.getOutputJar());
    Files.createDirectories(instrumentedClassesDirectory);
    if (jar == null) {
      jar = new JarCreator(coverageInformation);
    }
    jar.setNormalize(true);
    jar.setCompression(build.compressJar());
    instrumentRecursively(new OfflineInstrumentationAccessGenerator(), build.getClassDir());
    jar.addDirectory(instrumentedClassesDirectory);
    if (isNewCoverageImplementation) {
      jar.addEntry(coverageInformation, coverageInformation);
    } else {
      jar.execute();
      cleanup();
    }
  }

  public void cleanup() throws IOException {
    if (Files.exists(instrumentedClassesDirectory)) {
      MoreFiles.deleteRecursively(
          instrumentedClassesDirectory, RecursiveDeleteOption.ALLOW_INSECURE);
    }
  }

  // Return the path of the coverage metadata directory relative to the output jar path.
  private static Path getMetadataDirRelativeToJar(Path outputJar) {
    return outputJar.resolveSibling(outputJar + "-coverage-metadata");
  }

  /**
   * Runs Jacoco instrumentation processor over all .class files recursively, starting with root.
   */
  private void instrumentRecursively(IExecutionDataAccessorGenerator accessorGenerator, Path root)
      throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!file.getFileName().toString().endsWith(".class")) {
              return FileVisitResult.CONTINUE;
            }
            // TODO(bazel-team): filter with coverage_instrumentation_filter?
            // It's not clear whether there is any advantage in not instrumenting *Test classes,
            // apart from lowering the covered percentage in the aggregate statistics.

            // We first move the original .class file to our metadata directory, then instrument it
            // and output the instrumented version in the regular classes output directory.
            Path instrumentedCopy = file;
            Path uninstrumentedCopy;
            if (isNewCoverageImplementation) {
              Path absoluteUninstrumentedCopy = Paths.get(file + ".uninstrumented");
              uninstrumentedCopy =
                  instrumentedClassesDirectory.resolve(root.relativize(absoluteUninstrumentedCopy));
            } else {
              uninstrumentedCopy = instrumentedClassesDirectory.resolve(root.relativize(file));
            }
            Files.createDirectories(uninstrumentedCopy.getParent());
            Files.move(file, uninstrumentedCopy);
            Files.write(
                instrumentedCopy,
                instrument(Files.readAllBytes(uninstrumentedCopy), accessorGenerator));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private byte[] instrument(byte[] source, IExecutionDataAccessorGenerator accessorGenerator) {
    // Modified from
    // https://github.com/jacoco/jacoco/blob/20f076cb921588b80e6bb0b397b9aaf4dde910b5/org.jacoco.core/src/org/jacoco/core/instr/Instrumenter.java#L76C1-L92C31
    final long classId = CRC64.classId(source);
    final ClassReader reader = InstrSupport.classReaderFor(source);
    final ClassWriter writer =
        new ClassWriter(reader, 0) {
          @Override
          protected String getCommonSuperClass(final String type1, final String type2) {
            throw new IllegalStateException();
          }
        };
    final IProbeArrayStrategy strategy =
        ProbeArrayStrategyFactory.createFor(classId, reader, accessorGenerator);
    final int version = InstrSupport.getMajorVersion(reader);
    final ClassVisitor visitor =
        new ClassProbesAdapter(
            new ClassInstrumenter(strategy, writer), InstrSupport.needsFrames(version));
    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }
}
