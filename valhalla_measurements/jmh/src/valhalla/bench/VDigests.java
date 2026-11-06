package bench;

import java.util.Objects;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.vm.annotation.NullRestricted;

/** Valhalla variant: a Sha256 value class flattened into the FAV via @NullRestricted. */
public final class VDigests {
  @LooselyConsistentValue
  public static value class Sha256 {
    final long d0, d1, d2, d3;

    Sha256(byte[] d) {
      this.d0 = (long) Digests.LONGS.get(d, 0);
      this.d1 = (long) Digests.LONGS.get(d, 8);
      this.d2 = (long) Digests.LONGS.get(d, 16);
      this.d3 = (long) Digests.LONGS.get(d, 24);
    }

    byte[] toBytes() {
      byte[] b = new byte[32];
      Digests.LONGS.set(b, 0, d0);
      Digests.LONGS.set(b, 8, d1);
      Digests.LONGS.set(b, 16, d2);
      Digests.LONGS.set(b, 24, d3);
      return b;
    }
  }

  public static final class ValueRfav extends Digests.Fav {
    @NullRestricted private final Sha256 digest;
    private final Digests.Proxy proxy;
    private final long size;

    public ValueRfav(byte[] digest, Digests.Proxy proxy, long size) {
      this.digest = new Sha256(digest);
      this.proxy = proxy;
      this.size = size;
      super();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ValueRfav that)) return false;
      return digest == that.digest // value object substitutability: compares the four longs
          && Objects.equals(proxy, that.proxy)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      int h = (int) digest.d0;
      h = 31 * h + Objects.hashCode(proxy);
      return 31 * h + Long.hashCode(size);
    }

    @Override
    public byte[] getDigest() {
      return digest.toBytes();
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long foldDigest(long acc) {
      Sha256 d = digest;
      return acc ^ d.d0 ^ d.d1 ^ d.d2 ^ d.d3;
    }
  }
}
