package exp;

import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.vm.annotation.NullRestricted;

public class Layout {
  public static value class PF {
    final String s;
    PF(String s) { this.s = s; }
  }

  @LooselyConsistentValue
  public static value class Sha256 {
    final long a, b, c, d;
    Sha256(long a, long b, long c, long d) { this.a = a; this.b = b; this.c = c; this.d = d; }
  }

  public static value class Sha256Atomic {
    final long a, b, c, d;
    Sha256Atomic(long a, long b, long c, long d) { this.a = a; this.b = b; this.c = c; this.d = d; }
  }

  // Identity holder with null-restricted value field (record fields are strict).
  public record PFHolderRecord(@NullRestricted PF path, Object other) {}

  public static final class PFHolderPlain {
    @NullRestricted final PF path;
    final Object other;
    PFHolderPlain(PF path, Object other) { this.path = path; this.other = other; super(); }
  }

  public static final class PFHolderNullable {
    final PF path;
    final Object other;
    PFHolderNullable(PF path, Object other) { this.path = path; this.other = other; super(); }
  }

  public record RfavLoose(@NullRestricted Sha256 digest, Object proxy, long size) {}
  public record RfavAtomic(@NullRestricted Sha256Atomic digest, Object proxy, long size) {}
  public record RfavNullableLoose(Sha256 digest, Object proxy, long size) {}

  public static void main(String[] args) {
    var h1 = new PFHolderRecord(new PF("a/b"), null);
    var h2 = new PFHolderPlain(new PF("a/b"), null);
    var h3 = new PFHolderNullable(new PF("a/b"), null);
    var r1 = new RfavLoose(new Sha256(1, 2, 3, 4), null, 5);
    var r2 = new RfavAtomic(new Sha256Atomic(1, 2, 3, 4), null, 5);
    var r3 = new RfavNullableLoose(new Sha256(1, 2, 3, 4), null, 5);
    System.out.println(h1 + " " + h2.path + " " + h3.path + " " + r1 + r2 + r3);
  }
}
