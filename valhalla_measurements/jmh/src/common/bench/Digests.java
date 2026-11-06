package bench;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/** Models of FileArtifactValue.RegularFileArtifactValue. */
public final class Digests {
  static final VarHandle LONGS =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  /** Stand-in for FileContentsProxy (ctime, mtime, nodeId). */
  public static final class Proxy {
    final long ctime, mtime, nodeId;

    public Proxy(long ctime, long mtime, long nodeId) {
      this.ctime = ctime;
      this.mtime = mtime;
      this.nodeId = nodeId;
    }

    @Override
    public boolean equals(Object o) {
      return this == o
          || (o instanceof Proxy p && ctime == p.ctime && mtime == p.mtime && nodeId == p.nodeId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ctime, mtime, nodeId);
    }
  }

  public abstract static class Fav {
    public abstract byte[] getDigest();

    public abstract long getSize();

    /** Allocation-free alternative API: feed the digest to a sink without materializing it. */
    public abstract long foldDigest(long acc);
  }

  /** Today's layout: byte[] digest. */
  public static final class ByteArrayRfav extends Fav {
    private final byte[] digest;
    private final Proxy proxy;
    private final long size;

    public ByteArrayRfav(byte[] digest, Proxy proxy, long size) {
      this.digest = digest;
      this.proxy = proxy;
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ByteArrayRfav that)) return false;
      return Arrays.equals(digest, that.digest)
          && Objects.equals(proxy, that.proxy)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      // HashCodes.hashObjects(Arrays.hashCode(digest), proxy, size)
      int h = Arrays.hashCode(digest);
      h = 31 * h + Objects.hashCode(proxy);
      return 31 * h + Long.hashCode(size);
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long foldDigest(long acc) {
      byte[] d = digest;
      return acc ^ (long) LONGS.get(d, 0) ^ (long) LONGS.get(d, 8) ^ (long) LONGS.get(d, 16)
          ^ (long) LONGS.get(d, 24);
    }
  }

  /** "Even today" specialization: SHA-256 stored inline as four longs. */
  public static final class FourLongRfav extends Fav {
    private final long d0, d1, d2, d3;
    private final Proxy proxy;
    private final long size;

    public FourLongRfav(byte[] digest, Proxy proxy, long size) {
      this.d0 = (long) LONGS.get(digest, 0);
      this.d1 = (long) LONGS.get(digest, 8);
      this.d2 = (long) LONGS.get(digest, 16);
      this.d3 = (long) LONGS.get(digest, 24);
      this.proxy = proxy;
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof FourLongRfav that)) return false;
      return d0 == that.d0
          && d1 == that.d1
          && d2 == that.d2
          && d3 == that.d3
          && Objects.equals(proxy, that.proxy)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      // The digest is already a uniformly distributed hash.
      int h = (int) d0;
      h = 31 * h + Objects.hashCode(proxy);
      return 31 * h + Long.hashCode(size);
    }

    @Override
    public byte[] getDigest() {
      byte[] b = new byte[32];
      LONGS.set(b, 0, d0);
      LONGS.set(b, 8, d1);
      LONGS.set(b, 16, d2);
      LONGS.set(b, 24, d3);
      return b;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long foldDigest(long acc) {
      return acc ^ d0 ^ d1 ^ d2 ^ d3;
    }
  }
}
