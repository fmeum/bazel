package bench;

import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(VRfavBench.N)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+UseG1GC"})
public class VRfavBench {
  public static final int N = 1 << 20;

  @Param({"value"})
  public String variant;

  Digests.Fav[] a, b;
  MessageDigest sha;

  static Digests.Fav make(String variant, byte[] d, Digests.Proxy p, long size) {
    return switch (variant) {
      case "bytes" -> new Digests.ByteArrayRfav(d, p, size);
      case "value" -> new VDigests.ValueRfav(d, p, size);
      default -> throw new IllegalArgumentException(variant);
    };
  }

  @Setup
  public void setup() throws Exception {
    a = new Digests.Fav[N];
    b = new Digests.Fav[N];
    Random r = new Random(42);
    for (int i = 0; i < N; i++) {
      byte[] d = new byte[32];
      r.nextBytes(d);
      long size = r.nextInt(1 << 20);
      a[i] = make(variant, d, new Digests.Proxy(i, i, i), size);
      b[i] = make(variant, d.clone(), new Digests.Proxy(i, i, i), size);
    }
    shuffle(a, b, new Random(7));
    sha = MessageDigest.getInstance("SHA-256");
  }

  static void shuffle(Object[] x, Object[] y, Random r) {
    for (int i = x.length - 1; i > 0; i--) {
      int j = r.nextInt(i + 1);
      Object t = x[i];
      x[i] = x[j];
      x[j] = t;
      t = y[i];
      y[i] = y[j];
      y[j] = t;
    }
  }

  /** Change pruning: compare old and new metadata of every file. */
  @Benchmark
  public int equalsAll() {
    int c = 0;
    for (int i = 0; i < N; i++) if (a[i].equals(b[i])) c++;
    return c;
  }

  @Benchmark
  public int hashAll() {
    int h = 0;
    for (int i = 0; i < N; i++) h += a[i].hashCode();
    return h;
  }

  /** Callers that need a byte[] (e.g. proto Digest, hex) – four-long variant must allocate. */
  @Benchmark
  public long getDigestAndRead() {
    long acc = 0;
    for (int i = 0; i < N; i++) {
      byte[] d = a[i].getDigest();
      acc += d[0] + d[31];
    }
    return acc;
  }

  /** Allocation-free API that a four-long FAV would offer instead of getDigest(). */
  @Benchmark
  public long foldDigest() {
    long acc = 0;
    for (int i = 0; i < N; i++) acc = a[i].foldDigest(acc);
    return acc;
  }

  /** Typical real consumer: action-cache style fingerprinting via MessageDigest. */
  @Benchmark
  public byte[] fingerprintDigests() {
    MessageDigest md = sha;
    for (int i = 0; i < N; i++) md.update(a[i].getDigest());
    return md.digest();
  }
}
