package com.google.devtools.build.buildjar.instrumentation;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class BaselineCoverageCollector {
  private final ImmutableSet<String> sourceFiles;
  private final TreeMultimap<String, Integer> sourceToLines = TreeMultimap.create();

  public static BaselineCoverageCollector create(Path coverageInformation) throws IOException {
    ImmutableSet<String> sourceFiles;
    try (Stream<String> lines = Files.lines(coverageInformation)) {
      sourceFiles = lines.collect(toImmutableSet());
    }
    return new BaselineCoverageCollector(sourceFiles);
  }

  private BaselineCoverageCollector(ImmutableSet<String> sourceFiles) {
    this.sourceFiles = sourceFiles;
  }

  void collect(byte[] bytes) {
    ClassReader reader = InstrSupport.classReaderFor(bytes);
    BaselineCoverageClassVisitor visitor = new BaselineCoverageClassVisitor();
    reader.accept(visitor, ClassReader.SKIP_FRAMES);

    String canonicalSourcePath = visitor.getCanonicalSourcePath();
    String actualSourcePath =
        sourceFiles.stream()
            .filter(
                path ->
                    path.equals(canonicalSourcePath) || path.endsWith("/" + canonicalSourcePath))
            .findFirst()
            .orElse(canonicalSourcePath);
    sourceToLines.putAll(actualSourcePath, visitor.getLines());
  }

  void writeTo(OutputStream out) throws IOException {
    try (PrintWriter printer = new PrintWriter(out, false)) {
      for (Map.Entry<String, Collection<Integer>> entry : sourceToLines.asMap().entrySet()) {
        printer.printf("SF:%s\n", entry.getKey());
        for (int line : entry.getValue()) {
          printer.printf("DA:%d,0\n", line);
        }
        printer.printf("LF:%d\n", entry.getValue().size());
        printer.print("LH:0\n");
        printer.print("end_of_record\n");
      }
      if (printer.checkError()) {
        throw new IOException("Failed to write baseline coverage information");
      }
    }
  }

  private static final class BaselineCoverageClassVisitor extends ClassVisitor {
    private String internalClassName;
    @Nullable private String sourceBasename;
    private final Set<Integer> lines = new HashSet<>();
    private final BaselineCoverageMethodVisitor methodVisitor = new BaselineCoverageMethodVisitor();

    private final class BaselineCoverageMethodVisitor extends MethodVisitor {
      BaselineCoverageMethodVisitor() {
        super(InstrSupport.ASM_API_VERSION, null);
      }

      @Override
      public void visitLineNumber(int line, Label start) {
        lines.add(line);
      }
    }

    BaselineCoverageClassVisitor() {
      super(InstrSupport.ASM_API_VERSION, null);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      internalClassName = name;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      return methodVisitor;
    }

    @Override
    public void visitSource(String source, String debug) {
      this.sourceBasename = source;
    }

    String getCanonicalSourcePath() {
      if (sourceBasename == null) {
        return internalClassName + ".java";
      } else {
        return internalClassName.substring(0, internalClassName.lastIndexOf('/') + 1)
            + sourceBasename;
      }
    }

    Set<Integer> getLines() {
      return Collections.unmodifiableSet(lines);
    }
  }
}
