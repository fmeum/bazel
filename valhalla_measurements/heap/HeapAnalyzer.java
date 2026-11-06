import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/**
 * Focused HPROF analyzer for two questions:
 *
 * <ol>
 *   <li>How much heap would RegularFileArtifactValue save by storing a SHA-256 digest as four
 *       longs instead of a byte[]? (Needs: RFAV count, digest lengths, whether the digest byte[]
 *       is shared with other referrers.)
 *   <li>How much heap would PathFragment save as a flattened (null-restricted) value class? (Needs:
 *       PathFragment count, inbound references classified by declared slot type.)
 * </ol>
 *
 * Usage: java HeapAnalyzer.java <dump.hprof> <A-server.jar>
 */
public class HeapAnalyzer {
  static final String PF = "com/google/devtools/build/lib/vfs/PathFragment";
  static final String RFAV =
      "com/google/devtools/build/lib/actions/FileArtifactValue$RegularFileArtifactValue";

  // ---- basic hprof plumbing ----
  static int idSize;

  static final class In implements Closeable {
    final InputStream in;
    final byte[] buf = new byte[1 << 20];
    int pos, lim;
    long offset;

    In(Path p) throws IOException {
      in = Files.newInputStream(p);
    }

    boolean fill() throws IOException {
      if (pos < lim) return true;
      offset += lim;
      lim = in.read(buf, 0, buf.length);
      pos = 0;
      if (lim <= 0) {
        lim = 0;
        return false;
      }
      return true;
    }

    int u1() throws IOException {
      if (pos >= lim && !fill()) throw new EOFException();
      return buf[pos++] & 0xff;
    }

    int u2() throws IOException {
      return (u1() << 8) | u1();
    }

    int u4() throws IOException {
      if (lim - pos >= 4) {
        int v =
            ((buf[pos] & 0xff) << 24)
                | ((buf[pos + 1] & 0xff) << 16)
                | ((buf[pos + 2] & 0xff) << 8)
                | (buf[pos + 3] & 0xff);
        pos += 4;
        return v;
      }
      return (u1() << 24) | (u1() << 16) | (u1() << 8) | u1();
    }

    long u8() throws IOException {
      return ((long) u4() << 32) | (u4() & 0xffffffffL);
    }

    long id() throws IOException {
      return idSize == 8 ? u8() : (u4() & 0xffffffffL);
    }

    void skip(long n) throws IOException {
      while (n > 0) {
        if (pos >= lim && !fill()) throw new EOFException();
        int k = (int) Math.min(n, lim - pos);
        pos += k;
        n -= k;
      }
    }

    void read(byte[] dst, int len) throws IOException {
      int off = 0;
      while (off < len) {
        if (pos >= lim && !fill()) throw new EOFException();
        int k = Math.min(len - off, lim - pos);
        System.arraycopy(buf, pos, dst, off, k);
        pos += k;
        off += k;
      }
    }

    long position() {
      return offset + pos;
    }

    public void close() throws IOException {
      in.close();
    }
  }

  static int typeSize(int t) {
    return switch (t) {
      case 2 -> idSize;
      case 4, 8 -> 1;
      case 5, 9 -> 2;
      case 6, 10 -> 4;
      case 7, 11 -> 8;
      default -> throw new IllegalStateException("type " + t);
    };
  }

  /** Real (JVM) size of a field of hprof basic type, assuming compressed oops. */
  static int jvmSize(int t) {
    return t == 2 ? 4 : typeSize(t);
  }

  // ---- open addressing long -> int map ----
  static final class LongIntMap {
    long[] keys;
    int[] vals;
    int size;
    int mask;

    LongIntMap(int cap) {
      int c = Integer.highestOneBit(Math.max(16, cap * 2) - 1) << 1;
      keys = new long[c];
      vals = new int[c];
      mask = c - 1;
    }

    static int h(long k) {
      k ^= k >>> 33;
      k *= 0xff51afd7ed558ccdL;
      k ^= k >>> 33;
      return (int) k;
    }

    void put(long k, int v) {
      if (size * 2 >= keys.length) grow();
      int i = h(k) & mask;
      while (keys[i] != 0 && keys[i] != k) i = (i + 1) & mask;
      if (keys[i] == 0) size++;
      keys[i] = k;
      vals[i] = v;
    }

    int get(long k) {
      int i = h(k) & mask;
      while (true) {
        long kk = keys[i];
        if (kk == k) return vals[i];
        if (kk == 0) return -1;
        i = (i + 1) & mask;
      }
    }

    void grow() {
      long[] ok = keys;
      int[] ov = vals;
      keys = new long[ok.length * 2];
      vals = new int[ok.length * 2];
      mask = keys.length - 1;
      size = 0;
      for (int i = 0; i < ok.length; i++) if (ok[i] != 0) put(ok[i], ov[i]);
    }
  }

  static final class ClassInfo {
    long id;
    long superId;
    String name;
    long[] fieldNameIds;
    int[] fieldTypes;
    String[] fieldNames;
    ClassInfo superInfo;
    // flattened field list across the hierarchy, in hprof instance-dump order (own fields first)
    int[] allTypes;
    String[] allNames;
    String[] allOwners;
    int jvmPayload; // sum of real field sizes (incl. superclasses)
    // stats
    long count;
    // per field in allTypes order
    long[] nulls, pfRefs, otherRefs;
    boolean isPf, isRfav, isCachedDigest, isCaffeine;
  }

  static final Map<Long, String> strings = new HashMap<>();
  static final Map<Long, Long> classNameIds = new HashMap<>();
  static final Map<Long, ClassInfo> classes = new HashMap<>();

  // prim array stats
  static final long[] primArrayCount = new long[12];
  static final long[] primArrayBytesNoCoh = new long[12];
  static final long[] primArrayBytesCoh = new long[12];
  static long objArrayCount, objArrayBytesNoCoh, objArrayBytesCoh;
  static final Map<Long, long[]> objArrayByClass = new HashMap<>(); // count, bytesNoCoh

  static long align8(long x) {
    return (x + 7) & ~7L;
  }

  interface Visitor {
    default void instance(long id, ClassInfo ci, In in, int len) throws IOException {
      in.skip(len);
    }

    default void objArray(long id, long arrClass, int n, In in) throws IOException {
      in.skip((long) n * idSize);
    }

    default void primArray(long id, int type, int n, In in) throws IOException {
      in.skip((long) n * typeSize(type));
    }

    default void classDump(In in) throws IOException {
      skipClassDump(in);
    }
  }

  static void skipClassDump(In in) throws IOException {
    in.id();
    in.u4();
    for (int i = 0; i < 6; i++) in.id();
    in.u4();
    int cp = in.u2();
    for (int i = 0; i < cp; i++) {
      in.u2();
      int t = in.u1();
      in.skip(typeSize(t));
    }
    int st = in.u2();
    for (int i = 0; i < st; i++) {
      in.id();
      int t = in.u1();
      in.skip(typeSize(t));
    }
    int fi = in.u2();
    for (int i = 0; i < fi; i++) {
      in.id();
      in.u1();
    }
  }

  static void scan(Path file, Visitor v, boolean topLevel) throws IOException {
    try (In in = new In(file)) {
      // header
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = in.u1()) != 0) sb.append((char) c);
      idSize = in.u4();
      in.u8();
      while (in.fill()) {
        int tag = in.u1();
        in.u4();
        long len = in.u4() & 0xffffffffL;
        if (tag == 0x01 && topLevel) {
          long id = in.id();
          byte[] b = new byte[(int) (len - idSize)];
          in.read(b, b.length);
          strings.put(id, new String(b, java.nio.charset.StandardCharsets.UTF_8));
        } else if (tag == 0x02 && topLevel) {
          in.u4();
          long cid = in.id();
          in.u4();
          long nameId = in.id();
          classNameIds.put(cid, nameId);
        } else if (tag == 0x0C || tag == 0x1C) {
          long end = in.position() + len;
          while (in.position() < end) {
            int sub = in.u1();
            switch (sub) {
              case 0xFF -> in.id();
              case 0x01 -> {
                in.id();
                in.id();
              }
              case 0x02, 0x03, 0x08 -> {
                in.id();
                in.u4();
                in.u4();
              }
              case 0x04, 0x06 -> {
                in.id();
                in.u4();
              }
              case 0x05, 0x07 -> in.id();
              case 0x20 -> v.classDump(in);
              case 0x21 -> {
                long id = in.id();
                in.u4();
                long cid = in.id();
                int n = in.u4();
                ClassInfo ci = classes.get(cid);
                v.instance(id, ci, in, n);
              }
              case 0x22 -> {
                long id = in.id();
                in.u4();
                int n = in.u4();
                long ac = in.id();
                v.objArray(id, ac, n, in);
              }
              case 0x23 -> {
                long id = in.id();
                in.u4();
                int n = in.u4();
                int t = in.u1();
                v.primArray(id, t, n, in);
              }
              default ->
                  throw new IllegalStateException(
                      "bad subtag " + Integer.toHexString(sub) + " at " + in.position());
            }
          }
        } else {
          in.skip(len);
        }
      }
    }
  }

  static String cname(long cid) {
    Long nid = classNameIds.get(cid);
    return nid == null ? "?" + cid : strings.getOrDefault(nid, "?");
  }

  // ---- analysis state ----
  static LongIntMap pfIndex; // PF instance id -> idx
  static int pfCount;
  static long[] pfStringId;
  static LongIntMap digestIndex; // digest byte[] id -> idx
  static int digestCount;
  static int[] digestInbound;
  static int[] digestLen;
  static int[] digestNonRfavInbound;
  static byte[] digestMask; // 1 = RFAV, 2 = digest cache, 4 = other
  static long cachedDigestCount, cacheValueRefs;
  static final String CACHED_DIGEST = "com/google/devtools/build/lib/vfs/DigestUtils$CachedDigest";
  static long rfavCount, rfavNullDigest, rfavNullProxy;
  static final Map<String, Long> digestOtherReferrers = new TreeMap<>();
  static final Map<String, long[]> pfArrayReferrers = new TreeMap<>();
  static long pfRootRefs;
  static final LongIntMap pfArrays = new LongIntMap(1 << 16); // Object[] id -> #PF elements
  static final Map<String, long[]> pfArrayOwners = new TreeMap<>();

  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]);
    Path serverJar = Path.of(args[1]);
    long t0 = System.nanoTime();

    // Pass 1: strings, class names, class dumps.
    scan(
        file,
        new Visitor() {
          @Override
          public void classDump(In in) throws IOException {
            ClassInfo ci = new ClassInfo();
            ci.id = in.id();
            in.u4();
            ci.superId = in.id();
            for (int i = 0; i < 5; i++) in.id();
            in.u4();
            int cp = in.u2();
            for (int i = 0; i < cp; i++) {
              in.u2();
              int t = in.u1();
              in.skip(typeSize(t));
            }
            int st = in.u2();
            for (int i = 0; i < st; i++) {
              in.id();
              int t = in.u1();
              in.skip(typeSize(t));
            }
            int fi = in.u2();
            ci.fieldNameIds = new long[fi];
            ci.fieldTypes = new int[fi];
            for (int i = 0; i < fi; i++) {
              ci.fieldNameIds[i] = in.id();
              ci.fieldTypes[i] = in.u1();
            }
            classes.put(ci.id, ci);
          }
        },
        true);
    for (ClassInfo ci : classes.values()) {
      ci.name = cname(ci.id);
      ci.fieldNames = new String[ci.fieldNameIds.length];
      for (int i = 0; i < ci.fieldNames.length; i++)
        ci.fieldNames[i] = strings.getOrDefault(ci.fieldNameIds[i], "?");
      ci.superInfo = classes.get(ci.superId);
    }
    for (ClassInfo ci : classes.values()) {
      List<Integer> types = new ArrayList<>();
      List<String> names = new ArrayList<>();
      List<String> owners = new ArrayList<>();
      int payload = 0;
      for (ClassInfo c = ci; c != null; c = c.superInfo) {
        for (int i = 0; i < c.fieldTypes.length; i++) {
          types.add(c.fieldTypes[i]);
          names.add(c.fieldNames[i]);
          owners.add(c.name);
          payload += jvmSize(c.fieldTypes[i]);
        }
        if (c.name.equals(PF)) ci.isPf = true;
      }
      ci.allTypes = types.stream().mapToInt(Integer::intValue).toArray();
      ci.allNames = names.toArray(new String[0]);
      ci.allOwners = owners.toArray(new String[0]);
      ci.jvmPayload = payload;
      ci.nulls = new long[ci.allTypes.length];
      ci.pfRefs = new long[ci.allTypes.length];
      ci.otherRefs = new long[ci.allTypes.length];
      ci.isRfav = ci.name.equals(RFAV);
      ci.isCachedDigest = ci.name.equals(CACHED_DIGEST);
      ci.isCaffeine = ci.name.startsWith("com/github/benmanes/caffeine/cache/");
    }
    System.err.printf("pass1: %d classes, %.1fs%n", classes.size(), (System.nanoTime() - t0) / 1e9);

    // Pass 2: histogram, PF instances, RFAV instances.
    pfIndex = new LongIntMap(1 << 20);
    digestIndex = new LongIntMap(1 << 18);
    List<Long> pfStrings = new ArrayList<>();
    final byte[] scratch = new byte[1 << 16];
    scan(
        file,
        new Visitor() {
          @Override
          public void instance(long id, ClassInfo ci, In in, int len) throws IOException {
            ci.count++;
            if (ci.isPf) {
              // PathFragment subclasses have exactly one field: normalizedPath (in PathFragment).
              in.read(scratch, len);
              pfIndex.put(id, pfCount++);
              pfStrings.add(readId(scratch, 0));
            } else if (ci.isCachedDigest) {
              in.read(scratch, len);
              int off = 0;
              for (int i = 0; i < ci.allTypes.length; i++) {
                if (ci.allNames[i].equals("digest") && ci.allTypes[i] == 2) {
                  long d = readId(scratch, off);
                  if (d != 0 && digestIndex.get(d) < 0) digestIndex.put(d, digestCount++);
                }
                off += typeSize(ci.allTypes[i]);
              }
              cachedDigestCount++;
            } else if (ci.isRfav) {
              in.read(scratch, len);
              int off = 0;
              long digest = 0, proxy = 0;
              for (int i = 0; i < ci.allTypes.length; i++) {
                int t = ci.allTypes[i];
                if (ci.allOwners[i].equals(RFAV)) {
                  if (ci.allNames[i].equals("digest")) digest = readId(scratch, off);
                  if (ci.allNames[i].equals("proxy")) proxy = readId(scratch, off);
                }
                off += typeSize(t);
              }
              rfavCount++;
              if (digest == 0) rfavNullDigest++;
              else if (digestIndex.get(digest) < 0) digestIndex.put(digest, digestCount++);
              if (proxy == 0) rfavNullProxy++;
            } else {
              in.skip(len);
            }
          }

          @Override
          public void objArray(long id, long arrClass, int n, In in) throws IOException {
            objArrayCount++;
            objArrayBytesNoCoh += align8(16 + 4L * n);
            objArrayBytesCoh += align8(12 + 4L * n);
            long[] s = objArrayByClass.computeIfAbsent(arrClass, k -> new long[2]);
            s[0]++;
            s[1] += align8(16 + 4L * n);
            in.skip((long) n * idSize);
          }

          @Override
          public void primArray(long id, int type, int n, In in) throws IOException {
            primArrayCount[type]++;
            long data = (long) n * typeSize(type);
            primArrayBytesNoCoh[type] += align8(16 + data);
            primArrayBytesCoh[type] += align8(12 + data);
            in.skip(data);
          }
        },
        false);
    pfStringId = pfStrings.stream().mapToLong(Long::longValue).toArray();
    digestInbound = new int[digestCount];
    digestLen = new int[digestCount];
    digestNonRfavInbound = new int[digestCount];
    digestMask = new byte[digestCount];
    Arrays.fill(digestLen, -1);
    System.err.printf(
        "pass2: %d PF, %d RFAV, %.1fs%n", pfCount, rfavCount, (System.nanoTime() - t0) / 1e9);

    // Pass 3: references into PF instances and digest arrays.
    final int[] pfInbound = new int[pfCount];
    scan(
        file,
        new Visitor() {
          @Override
          public void instance(long id, ClassInfo ci, In in, int len) throws IOException {
            if (len > scratch.length) {
              in.skip(len);
              return;
            }
            in.read(scratch, len);
            int off = 0;
            int[] types = ci.allTypes;
            for (int i = 0; i < types.length; i++) {
              int t = types[i];
              if (t == 2) {
                long v = readId(scratch, off);
                if (v == 0) ci.nulls[i]++;
                else {
                  int pi = pfIndex.get(v);
                  if (pi >= 0) {
                    ci.pfRefs[i]++;
                    pfInbound[pi]++;
                  } else {
                    ci.otherRefs[i]++;
                    int di = digestIndex.get(v);
                    if (di >= 0) {
                      digestInbound[di]++;
                      if (!ci.isRfav) digestNonRfavInbound[di]++;
                      boolean cacheRef =
                          ci.isCachedDigest || (ci.isCaffeine && ci.allNames[i].equals("value"));
                      if (cacheRef && !ci.isCachedDigest) cacheValueRefs++;
                      digestMask[di] |= (byte) (ci.isRfav ? 1 : cacheRef ? 2 : 4);
                      if (!ci.isRfav)
                        digestOtherReferrers.merge(
                            ci.name + "." + ci.allNames[i], 1L, Long::sum);
                    }
                  }
                }
              }
              off += typeSize(t);
            }
          }

          @Override
          public void objArray(long id, long arrClass, int n, In in) throws IOException {
            for (int i = 0; i < n; i++) {
              long v = in.id();
              if (v == 0) continue;
              int pi = pfIndex.get(v);
              if (pi >= 0) {
                pfInbound[pi]++;
                pfArrayReferrers.computeIfAbsent(cname(arrClass), k -> new long[1])[0]++;
                int c = pfArrays.get(id);
                pfArrays.put(id, c < 0 ? 1 : c + 1);
              } else {
                int di = digestIndex.get(v);
                if (di >= 0) {
                  digestInbound[di]++;
                  digestNonRfavInbound[di]++;
                  digestMask[di] |= 4;
                  digestOtherReferrers.merge(cname(arrClass) + "[]", 1L, Long::sum);
                }
              }
            }
          }

          @Override
          public void primArray(long id, int type, int n, In in) throws IOException {
            if (type == 8) {
              int di = digestIndex.get(id);
              if (di >= 0) digestLen[di] = n;
            }
            in.skip((long) n * typeSize(type));
          }
        },
        false);
    System.err.printf("pass3: %.1fs%n", (System.nanoTime() - t0) / 1e9);

    // Resolve declared field types of all (class, field) slots that ever point at a PF.
    URLClassLoader loader =
        new URLClassLoader(new URL[] {serverJar.toUri().toURL()}, HeapAnalyzer.class.getClassLoader());
    Class<?> pfClass = Class.forName(PF.replace('/', '.'), false, loader);
    record Slot(ClassInfo ci, int idx, String declared, boolean flattenable) {}
    List<Slot> slots = new ArrayList<>();
    Map<ClassInfo, boolean[]> flatSlots = new HashMap<>();
    for (ClassInfo ci : classes.values()) {
      for (int i = 0; i < ci.allTypes.length; i++) {
        if (ci.pfRefs[i] == 0) continue;
        String declared = declaredType(loader, ci.allOwners[i], ci.allNames[i]);
        boolean flat = false;
        if (declared != null) {
          try {
            Class<?> d = Class.forName(declared, false, loader);
            flat = pfClass.isAssignableFrom(d);
          } catch (Throwable e) {
            // primitive / unknown
          }
        }
        slots.add(new Slot(ci, i, declared, flat));
        if (flat) flatSlots.computeIfAbsent(ci, k -> new boolean[ci.allTypes.length])[i] = true;
      }
    }

    // Pass 4: per PF, count inbound refs from flattenable slots.
    final int[] pfFlatInbound = new int[pfCount];
    scan(
        file,
        new Visitor() {
          @Override
          public void instance(long id, ClassInfo ci, In in, int len) throws IOException {
            boolean[] fs = flatSlots.get(ci);
            if (fs == null || len > scratch.length) {
              in.skip(len);
              return;
            }
            in.read(scratch, len);
            int off = 0;
            for (int i = 0; i < ci.allTypes.length; i++) {
              if (fs[i]) {
                long v = readId(scratch, off);
                int pi = pfIndex.get(v);
                if (pi >= 0) pfFlatInbound[pi]++;
              }
              off += typeSize(ci.allTypes[i]);
            }
          }
        },
        false);
    System.err.printf("pass4: %.1fs%n", (System.nanoTime() - t0) / 1e9);
    // Pass 5: who holds the Object[] arrays that contain PathFragments?
    scan(
        file,
        new Visitor() {
          @Override
          public void instance(long id, ClassInfo ci, In in, int len) throws IOException {
            if (len > scratch.length) {
              in.skip(len);
              return;
            }
            in.read(scratch, len);
            int off = 0;
            for (int i = 0; i < ci.allTypes.length; i++) {
              if (ci.allTypes[i] == 2) {
                int c = pfArrays.get(readId(scratch, off));
                if (c > 0) {
                  long[] e = pfArrayOwners.computeIfAbsent(
                      shortName(ci.name) + "." + ci.allNames[i], k -> new long[2]);
                  e[0]++;
                  e[1] += c;
                }
              }
              off += typeSize(ci.allTypes[i]);
            }
          }
        },
        false);

    // Pass 6/7: how many PathFragments share string *contents* with another PathFragment?
    LongIntMap pfStringSet = new LongIntMap(pfCount);
    for (long sid : pfStringId) pfStringSet.put(sid, 1);
    LongIntMap valueArrays = new LongIntMap(pfCount);
    scan(
        file,
        new Visitor() {
          @Override
          public void instance(long id, ClassInfo ci, In in, int len) throws IOException {
            if (!ci.name.equals("java/lang/String") || pfStringSet.get(id) < 0) {
              in.skip(len);
              return;
            }
            in.read(scratch, len);
            int off = 0;
            for (int i = 0; i < ci.allTypes.length; i++) {
              if (ci.allNames[i].equals("value")) valueArrays.put(readId(scratch, off), 1);
              off += typeSize(ci.allTypes[i]);
            }
          }
        },
        false);
    Map<String, Integer> contents = new HashMap<>();
    long[] dupBytes = new long[1];
    scan(
        file,
        new Visitor() {
          @Override
          public void primArray(long id, int type, int n, In in) throws IOException {
            if (type != 8 || valueArrays.get(id) < 0) {
              in.skip((long) n * typeSize(type));
              return;
            }
            byte[] b = new byte[n];
            in.read(b, n);
            String c = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (contents.merge(c, 1, Integer::sum) > 1) dupBytes[0] += align8(12 + n) + 24 + 16;
          }
        },
        false);
    System.out.printf(
        "PathFragment string contents: %,d PFs, %,d String objects, %,d distinct contents;"
            + " redundant PF+String+byte[] copies cost ~%s (compact headers)%n",
        pfCount, Arrays.stream(pfStringId).distinct().count(), contents.size(), mb(dupBytes[0]));
    report(slots.stream().map(s -> new Object[] {s.ci, s.idx, s.declared, s.flattenable}).collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
        pfInbound, pfFlatInbound);
  }

  static String declaredType(ClassLoader loader, String owner, String field) {
    try {
      Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
      Field f = c.getDeclaredField(field);
      return f.getType().getName();
    } catch (Throwable e) {
      return null;
    }
  }

  static long readId(byte[] b, int off) {
    if (idSize == 4) {
      return ((b[off] & 0xffL) << 24)
          | ((b[off + 1] & 0xffL) << 16)
          | ((b[off + 2] & 0xffL) << 8)
          | (b[off + 3] & 0xffL);
    }
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xffL);
    return v;
  }

  static String mb(double bytes) {
    return String.format("%.2f MB", bytes / 1e6);
  }

  static void report(List<Object[]> slots, int[] pfInbound, int[] pfFlatInbound) {
    // ---- totals ----
    long instNoCoh = 0, instCoh = 0, instCount = 0;
    List<Object[]> hist = new ArrayList<>();
    for (ClassInfo ci : classes.values()) {
      if (ci.count == 0) continue;
      long sNo = align8(12 + ci.jvmPayload) * ci.count;
      long sCoh = align8(8 + ci.jvmPayload) * ci.count;
      instNoCoh += sNo;
      instCoh += sCoh;
      instCount += ci.count;
      hist.add(new Object[] {ci.name, ci.count, sNo, sCoh});
    }
    long primNo = Arrays.stream(primArrayBytesNoCoh).sum();
    long primCoh = Arrays.stream(primArrayBytesCoh).sum();
    long totalNo = instNoCoh + primNo + objArrayBytesNoCoh;
    long totalCoh = instCoh + primCoh + objArrayBytesCoh;
    long totalObjs = instCount + Arrays.stream(primArrayCount).sum() + objArrayCount;
    System.out.println("=== Heap totals (modelled shallow sizes, compressed oops) ===");
    System.out.printf(
        "objects: %,d   heap (12-byte headers): %s   heap (compact 8-byte headers): %s%n",
        totalObjs, mb(totalNo), mb(totalCoh));
    String[] tn = {"", "", "", "", "boolean[]", "char[]", "float[]", "double[]", "byte[]", "short[]", "int[]", "long[]"};
    hist.add(new Object[] {"byte[]", primArrayCount[8], primArrayBytesNoCoh[8], primArrayBytesCoh[8]});
    hist.add(new Object[] {"int[]", primArrayCount[10], primArrayBytesNoCoh[10], primArrayBytesCoh[10]});
    hist.add(new Object[] {"long[]", primArrayCount[11], primArrayBytesNoCoh[11], primArrayBytesCoh[11]});
    hist.add(new Object[] {"Object[] (all ref arrays)", objArrayCount, objArrayBytesNoCoh, objArrayBytesCoh});
    hist.sort((a, b) -> Long.compare((long) b[2], (long) a[2]));
    System.out.println("\nTop 40 classes by shallow size (12-byte headers):");
    for (int i = 0; i < Math.min(40, hist.size()); i++) {
      Object[] h = hist.get(i);
      System.out.printf(
          "  %12s  %10s  %5.2f%%  %,12d  %s%n",
          mb((long) h[2]), mb((long) h[3]), 100.0 * (long) h[2] / totalNo, (long) h[1], h[0]);
    }

    // ---- Q1: RFAV ----
    System.out.println("\n=== Q1: RegularFileArtifactValue digest as four longs ===");
    System.out.printf(
        "RFAV instances: %,d (null digest: %,d, null proxy: %,d), distinct digest arrays: %,d%n",
        rfavCount, rfavNullDigest, rfavNullProxy, digestCount);
    Map<Integer, Long> lenHist = new TreeMap<>();
    long shared = 0, exclusive = 0, sharedAmongRfavsOnly = 0;
    long rfavRefsToArrays = 0;
    for (int i = 0; i < digestCount; i++) lenHist.merge(digestLen[i], 1L, Long::sum);
    System.out.println("digest length histogram (len -> #arrays): " + lenHist);
    // Count arrays referenced by exactly one ref (an RFAV) vs more.
    Map<Integer, Long> inboundHist = new TreeMap<>();
    for (int i = 0; i < digestCount; i++) inboundHist.merge(digestInbound[i], 1L, Long::sum);
    System.out.println("digest array inbound-ref histogram (#refs -> #arrays): " + inboundHist);
    System.out.println("non-RFAV referrers of tracked digest arrays: ");
    digestOtherReferrers.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
        .limit(15)
        .forEach(e -> System.out.printf("  %,10d  %s%n", e.getValue(), e.getKey()));
    long otherRefTotal = digestOtherReferrers.values().stream().mapToLong(Long::longValue).sum();
    // Arrays freed = arrays whose inbound refs all come from RFAVs. We approximate per array:
    // freed iff inbound == (#RFAV refs). We don't track per-array RFAV refs separately, so
    // compute: arrays with no non-RFAV referrers. Approximation: freed arrays = digestCount -
    // arrays that have any non-RFAV referrer (upper bound on that = otherRefTotal).
    long freedLowerBound = 0;
    for (int i = 0; i < digestCount; i++) if (digestNonRfavInbound[i] == 0) freedLowerBound++;
    System.out.printf("digest arrays referenced only by RFAVs (freed by inlining): %,d of %,d%n",
        freedLowerBound, digestCount);
    long rfav32 = rfavCount - rfavNullDigest;
    Map<Integer, Long> maskHist = new TreeMap<>();
    long s1Freed = 0, s2Freed = 0;
    for (int i = 0; i < digestCount; i++) {
      maskHist.merge((int) digestMask[i], 1L, Long::sum);
      if (digestMask[i] == 1) s1Freed++;
      if ((digestMask[i] & 4) == 0) s2Freed++;
    }
    long cacheHolders = cachedDigestCount + cacheValueRefs;
    System.out.printf(
        "digest arrays by holder set (1=RFAV, 2=digest cache, 4=other): %s; cache entries: %,d%n",
        maskHist, cacheHolders);
    long rfavNowNo = align8(12 + 4 + 4 + 8), rfavNowCoh = align8(8 + 4 + 4 + 8);
    long rfavNewNo = align8(12 + 4 + 8 + 32), rfavNewCoh = align8(8 + 4 + 8 + 32);
    // DigestUtils.CachedDigest(nodeId, changeTime, size, byte[] digest) -> four longs inline.
    long cdNowNo = align8(12 + 24 + 4), cdNowCoh = align8(8 + 24 + 4);
    long cdNewNo = align8(12 + 24 + 32), cdNewCoh = align8(8 + 24 + 32);
    long arr32 = align8(16 + 32); // same with COH (44 -> 48)
    for (String mode : new String[] {"12-byte headers", "compact headers"}) {
      boolean coh = mode.startsWith("compact");
      long total = coh ? totalCoh : totalNo;
      long rfGrowth = ((coh ? rfavNewCoh : rfavNewNo) - (coh ? rfavNowCoh : rfavNowNo)) * rfav32;
      long cdGrowth = ((coh ? cdNewCoh : cdNewNo) - (coh ? cdNowCoh : cdNowNo)) * cacheHolders;
      long s1 = arr32 * s1Freed - rfGrowth;
      long s2 = arr32 * s2Freed - rfGrowth - cdGrowth;
      System.out.printf(
          "[%s] RFAV %d->%d B, CachedDigest %d->%d B, byte[32] %d B%n"
              + "    S1 (only RFAV specialized): RFAV growth %s, arrays freed %s => net %s (%.2f%% of heap)%n"
              + "    S2 (RFAV + DigestUtils cache specialized): + cache growth %s, arrays freed %s => net %s (%.2f%% of heap)%n",
          mode, coh ? rfavNowCoh : rfavNowNo, coh ? rfavNewCoh : rfavNewNo,
          coh ? cdNowCoh : cdNowNo, coh ? cdNewCoh : cdNewNo, arr32,
          mb(rfGrowth), mb(arr32 * s1Freed), mb(s1), 100.0 * s1 / total,
          mb(cdGrowth), mb(arr32 * s2Freed), mb(s2), 100.0 * s2 / total);
    }

    // ---- Q2: PathFragment ----
    System.out.println("\n=== Q2: PathFragment as null-restricted value class ===");
    System.out.printf("PathFragment instances: %,d%n", pfCount);
    long distinctStrings = Arrays.stream(pfStringId).distinct().count();
    System.out.printf("distinct normalizedPath Strings among them: %,d%n", distinctStrings);
    Map<Integer, Long> inb = new TreeMap<>();
    for (int x : pfInbound) inb.merge(Math.min(x, 10), 1L, Long::sum);
    System.out.println("inbound ref histogram (refs, capped at 10 -> #PF): " + inb);
    System.out.println("\nSlots referencing PathFragment (class.field : declared type, pfRefs, nulls, otherRefs, flattenable):");
    slots.sort(
        (a, b) ->
            Long.compare(
                ((ClassInfo) b[0]).pfRefs[(int) b[1]], ((ClassInfo) a[0]).pfRefs[(int) a[1]]));
    long flatRefs = 0, boxRefs = 0, nullableFlatSlotsInstances = 0;
    long flatRefsInNullableSlots = 0;
    for (Object[] s : slots) {
      ClassInfo ci = (ClassInfo) s[0];
      int i = (int) s[1];
      boolean flat = (boolean) s[3];
      if (flat) {
        flatRefs += ci.pfRefs[i];
        if (ci.nulls[i] > 0) {
          flatRefsInNullableSlots += ci.pfRefs[i];
          nullableFlatSlotsInstances += ci.count;
        }
      } else boxRefs += ci.pfRefs[i];
    }
    for (int k = 0; k < Math.min(45, slots.size()); k++) {
      Object[] s = slots.get(k);
      ClassInfo ci = (ClassInfo) s[0];
      int i = (int) s[1];
      System.out.printf(
          "  %,10d  nulls=%,-9d other=%,-9d %-5s %s.%s : %s%n",
          ci.pfRefs[i], ci.nulls[i], ci.otherRefs[i], (boolean) s[3] ? "FLAT" : "box",
          shortName(ci.name), ci.allNames[i], s[2] == null ? "?" : shortName((String) s[2]));
    }
    long arrRefs = pfArrayReferrers.values().stream().mapToLong(a -> a[0]).sum();
    System.out.println("Array referrers of PathFragment:");
    pfArrayReferrers.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
        .limit(10)
        .forEach(e -> System.out.printf("  %,10d  %s%n", e.getValue()[0], e.getKey()));
    System.out.println("Holders of those arrays (owner.field: #arrays, #PF elements):");
    pfArrayOwners.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
        .limit(12)
        .forEach(e -> System.out.printf("  %,10d arrays %,10d PFs  %s%n", e.getValue()[0], e.getValue()[1], e.getKey()));
    System.out.printf(
        "refs from PF-typed fields: %,d (of which in fields that are sometimes null: %,d);"
            + " refs from non-PF-typed fields: %,d; refs from arrays: %,d%n",
        flatRefs, flatRefsInNullableSlots, boxRefs, arrRefs);

    Map<ClassInfo, Integer> flatFieldsPerClass = new HashMap<>();
    for (Object[] sl : slots) if ((boolean) sl[3]) flatFieldsPerClass.merge((ClassInfo) sl[0], 1, Integer::sum);
    long onlyFlat = 0, mixed = 0, onlyBox = 0, noRefs = 0, boxRefsOfMixed = 0;
    for (int p = 0; p < pfCount; p++) {
      int all = pfInbound[p], fl = pfFlatInbound[p];
      if (all == 0) noRefs++;
      else if (fl == all) onlyFlat++;
      else if (fl == 0) onlyBox++;
      else {
        mixed++;
        boxRefsOfMixed += all - fl;
      }
    }
    System.out.printf(
        "PF instances: only referenced from PF-typed fields: %,d; mixed: %,d (non-flat refs: %,d);"
            + " only from Object-typed slots/arrays: %,d; no heap refs (roots): %,d%n",
        onlyFlat, mixed, boxRefsOfMixed, onlyBox, noRefs);
    long pfNow = 16; // both header modes: 12+4 -> 16, 8+4 -> 16 (aligned)
    long bufNo = 24, bufCoh = 16; // EA3 buffered layout of a 1-ref value class
    for (String mode : new String[] {"12-byte headers", "compact headers"}) {
      boolean coh = mode.startsWith("compact");
      long buf = coh ? bufCoh : bufNo;
      long total = coh ? totalCoh : totalNo;
      // Savings: boxes eliminated for onlyFlat. Remaining boxes (mixed + onlyBox) change from
      // 16 bytes to the buffered size. Worst case: every non-flat ref of a mixed PF gets its
      // own buffer.
      long saveBoxes = pfNow * onlyFlat;
      long boxResize = (buf - pfNow) * (mixed + onlyBox);
      long dupWorst = buf * Math.max(0, boxRefsOfMixed - mixed);
      // Nullable PF fields: nullable flat is 8 bytes instead of 4 → up to +4 per holder.
      long nullableGrowth = 4 * nullableFlatSlotsInstances;
      long best = saveBoxes - boxResize;
      long worst = saveBoxes - boxResize - dupWorst - nullableGrowth;
      // Without @NullRestricted, EA3 still flattens nullable PF fields (NULLABLE_ATOMIC_FLAT, 8 B
      // instead of a 4 B ref). Compute holder growth with alignment.
      long hdr = coh ? 8 : 12;
      long nullableHolderGrowth = 0;
      for (Map.Entry<ClassInfo, Integer> e : flatFieldsPerClass.entrySet()) {
        ClassInfo ci = e.getKey();
        long before = align8(hdr + ci.jvmPayload);
        long after = align8(hdr + ci.jvmPayload + 4L * e.getValue());
        nullableHolderGrowth += (after - before) * ci.count;
      }
      System.out.printf(
          "[%s] value class WITHOUT @NullRestricted (nullable flat, 8 B per field): holder growth %s"
              + " => net saving %s (%.2f%% of modelled heap)%n",
          mode, mb(nullableHolderGrowth), mb(best - nullableHolderGrowth),
          100.0 * (best - nullableHolderGrowth) / total);
      System.out.printf(
          "[%s] boxes eliminated %s; remaining boxes resized (%d->%d B) %s; nullable-flat growth"
              + " <= %s; worst-case re-buffering %s => net saving %s (best) .. %s (worst);"
              + " %.2f%% .. %.2f%% of modelled heap%n",
          mode, mb(saveBoxes), pfNow, buf, mb(boxResize), mb(nullableGrowth), mb(dupWorst),
          mb(best), mb(worst), 100.0 * best / total, 100.0 * worst / total);
    }
  }

  static String shortName(String n) {
    return n.replace("com/google/devtools/build/lib/", "")
        .replace("com.google.devtools.build.lib.", "")
        .replace("com/google/common/collect/", "guava/")
        .replace("com.google.common.collect.", "guava.");
  }
}
