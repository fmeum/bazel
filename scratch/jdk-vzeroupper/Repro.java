import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Critical downcalls on x86_64 don't clear the upper vector state before the native call, unlike
 * regular downcalls (MacroAssembler::set_last_Java_frame executes vzeroupper). Native code with
 * legacy SSE encoding then runs much slower if the compiled caller left the state dirty, e.g. by
 * zeroing a freshly allocated array with 256-bit stores.
 *
 * <p>Usage: java --enable-native-access=ALL-UNNAMED -Dlib=/path/to/libwork.so Repro.java
 */
public class Repro {
  static final Linker LINKER = Linker.nativeLinker();
  static final SymbolLookup LOOKUP =
      SymbolLookup.libraryLookup(Path.of(System.getProperty("lib")), Arena.global());
  static final FunctionDescriptor WORK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor NOOP = FunctionDescriptor.ofVoid();
  static final Linker.Option CRITICAL = Linker.Option.critical(true);

  static final MethodHandle WORK_CRITICAL = downcall("sse_work", WORK, CRITICAL);
  static final MethodHandle WORK_CRITICAL_VZEROUPPER =
      downcall("sse_work_vzeroupper", WORK, CRITICAL);
  static final MethodHandle NOOP_CRITICAL = downcall("noop", NOOP, CRITICAL);
  static final MethodHandle NOOP_CRITICAL_VZEROUPPER = downcall("noop_vzeroupper", NOOP, CRITICAL);
  static final MethodHandle NOOP_REGULAR = downcall("noop", NOOP);

  static MethodHandle downcall(String name, FunctionDescriptor descriptor, Linker.Option... opts) {
    return LINKER.downcallHandle(LOOKUP.find(name).orElseThrow(), descriptor, opts);
  }

  static final int N = 5_000_000;
  static final byte[] INPUT = new byte[1024];
  static int sink;

  static long workCritical() throws Throwable {
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      byte[] out = new byte[32];
      WORK_CRITICAL.invokeExact(MemorySegment.ofArray(out), MemorySegment.ofArray(INPUT));
      sink += out[0];
    }
    return System.nanoTime() - start;
  }

  static long workCriticalVzeroupper() throws Throwable {
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      byte[] out = new byte[32];
      WORK_CRITICAL_VZEROUPPER.invokeExact(
          MemorySegment.ofArray(out), MemorySegment.ofArray(INPUT));
      sink += out[0];
    }
    return System.nanoTime() - start;
  }

  static long noopCritical() throws Throwable {
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      NOOP_CRITICAL.invokeExact();
    }
    return System.nanoTime() - start;
  }

  static long noopCriticalVzeroupper() throws Throwable {
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      NOOP_CRITICAL_VZEROUPPER.invokeExact();
    }
    return System.nanoTime() - start;
  }

  static long noopRegular() throws Throwable {
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      NOOP_REGULAR.invokeExact();
    }
    return System.nanoTime() - start;
  }

  static void print(boolean print, String name, long nanos) {
    if (print) {
      System.out.printf("%-48s %8.2f ns/op%n", name, nanos / (double) N);
    }
  }

  public static void main(String[] args) throws Throwable {
    for (int round = 1; round <= 6; round++) {
      boolean print = round > 2;
      if (print) {
        System.out.println("-- round " + (round - 2));
      }
      print(print, "critical: new byte[32], sse_work", workCritical());
      print(print, "critical: new byte[32], vzeroupper, sse_work", workCriticalVzeroupper());
      print(print, "critical: noop", noopCritical());
      print(print, "critical: vzeroupper, noop", noopCriticalVzeroupper());
      print(print, "regular: noop", noopRegular());
    }
    System.out.println("(sink " + sink + ")");
  }
}
