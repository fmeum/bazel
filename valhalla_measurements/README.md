# What would Valhalla buy Bazel?

Two questions, measured on 2026-11-06:

1. Specialize `FileArtifactValue.RegularFileArtifactValue` (RFAV) to SHA-256 by storing the digest
   as four `long`s instead of a `byte[]` (possible today; Valhalla equivalent: a
   `@LooselyConsistentValue value class Sha256` in a `@NullRestricted` field).
2. Make `PathFragment` a value class and mark the fields that hold it `@NullRestricted`.

JDKs: Valhalla EA `27-jep401ea3+1-1` (`--enable-preview`, JDK-internal annotations via
`--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED`), and Zulu JDK 27 GA (the JDK
embedded in Bazel at HEAD, compact object headers on by default). All sizes below assume
compressed oops; "COH" = compact object headers (8-byte header), which Bazel HEAD enables.

## Method

* `layout/Layout.java`: field layouts printed by the EA JVM (`-XX:+PrintFieldLayout`), see
  `results/layout-ea3*.txt`.
* `heap/HeapAnalyzer.java`: HPROF analyzer. For RFAV: counts instances and classifies every
  digest `byte[]` by who else holds it. For `PathFragment`: classifies every inbound reference by
  the *declared* type of the referencing field (looked up via reflection in `A-server.jar`), so it
  knows which references could be flattened and which still need a heap box. Shallow sizes are
  modelled for both header layouts; the model matches the JVM's reported used heap within ~5%.
* Workloads (heap dumped with `jcmd GC.heap_dump` after the build):
  * **A**: Bazel 9.3.2 building `//src:bazel-dev` (5,789 actions). 142 MB used heap after GC.
    9.3.2 does not use COH. Output: `results/selfbuild-9.3.2.txt`.
  * **B**: Bazel HEAD (`bazel-dev`) building `synth/gen.py`'s workspace: 150k source files,
    15k genrules. 231 MB used heap after GC, COH. Output: `results/synth-head.txt`.
* `jmh/`: JMH microbenchmarks over 1M objects in shuffled order (memory-bound, like Skyframe
  walks). `ns/op` is per element. `results/jmh-*.txt`.

## 1. RFAV digest as four longs

Per-object layout (bytes):

|                                              | 12-byte headers | COH |
|----------------------------------------------|----------------:|----:|
| today: RFAV + `byte[32]`                     | 32 + 48 = 80    | 24 + 48 = 72 |
| four longs inline                            | 56              | 56  |
| Valhalla `Sha256` (`@LooselyConsistentValue` + `@NullRestricted`) | 56 | 56 |
| Valhalla `Sha256`, nullable field (no `@NullRestricted`)          | 64 | 56 |
| Valhalla `Sha256` without `@LooselyConsistentValue`               | not flattened (ref to 56/48-byte box) | |

So Valhalla gives exactly the layout of the hand-written four longs; it adds nothing on memory
(it would add a reusable type that could also flatten into other holders).

**The catch: the digest `byte[]` is shared.** `DigestUtils`' digest cache (`CachedDigest`,
`--cache_computed_file_digests=50000` by default) holds the *same* `byte[]` as the RFAV. Inlining
only frees the array for files that are not in that cache:

| per file                                         | 12-byte headers | COH |
|--------------------------------------------------|----------------:|----:|
| array exclusive to the RFAV                      | −24 | −16 |
| array also in digest cache, only RFAV specialized | +24 | +32 |
| array also in digest cache, RFAV and `CachedDigest` specialized (40→72 / 40→64) | +8 | +8 |

Measured:

| workload | RFAVs | digest arrays also in digest cache | only RFAV specialized | RFAV + DigestUtils specialized |
|---|---:|---:|---|---|
| A (9.3.2 self-build) | 23,087 | 100% | −0.55 MB / −0.74 MB (COH), i.e. **worse** | −0.19 MB (−0.13%) |
| B (HEAD, 150k files, COH) | 165,003 | 30% (50,000) | +0.24 MB (+0.11%) | **+1.44 MB (+0.63%)** |

With far more files than the cache holds, the saving tends to 16 B/file with COH (24 B without).
In workload B the heap costs ~1.4 KB per file, so the ceiling is ≈1.1% of heap (≈1.5% without
COH).

CPU (JMH, ns per element, JDK 27 GA; EA3 is similar):

| benchmark | `byte[]` | four longs | Valhalla value |
|---|---:|---:|---:|
| `equals` of two equal RFAVs (change pruning) | 85 | 69 | 69–70 (EA3) |
| read digest without materializing (`foldDigest`) | 47 | 18 | 19 (EA3) |
| `getDigest()` + read | 24 | 28 **+48 B alloc** | 34–40 **+48 B alloc** |
| SHA-256 over all digests (like `MetadataDigestUtils`) | 180 | 125 (+48 B alloc) | 110–114 (+48 B alloc) |

Saving one dependent cache miss per value outweighs the extra allocation in these memory-bound
loops, but every `getDigest()` caller would allocate. `FileArtifactValue.addTo(Fingerprint)` is
`final` and goes through `getDigest()`; it would need an override to stay allocation-free.

## 2. `PathFragment` as a `@NullRestricted` value class

Layout (EA3): a value class with one `String` field flattens to **4 bytes** in a
`@NullRestricted` field, the same as the reference it replaces, so each box removed saves 16 B.
Without `@NullRestricted`, EA3 still flattens it, but as 8 bytes (reference + null marker). A value
that must be boxed (stored as `Object`, in a collection, as a map key) takes **24 B** with 12-byte
headers (payload is 8-byte aligned) versus 16 B for today's `PathFragment`; 16 B with COH.

Measured (where references to `PathFragment` come from):

| workload | PathFragments | referenced only from `PathFragment`-typed fields (box goes away) | also referenced as `Object` (box stays) |
|---|---:|---:|---:|
| A | 133,107 (1.4% of heap) | 88,090 | 45,017 (`ImmutableList/Set<PathFragment>`, `CustomCommandLine` args, `ArtifactFactory` skip-list keys, `Optional`) |
| B | 366,505 (2.6% of heap) | 166,472 | 200,033 (`ArtifactFactory` skip-list keys shared with `SourceArtifact.execPath`; `DigestUtils` cache keys) |

No `PathFragment`-typed field that ever held a `PathFragment` was ever `null` in either heap, so
`@NullRestricted` would be applicable to all of them.

| net heap saving | 12-byte headers | COH |
|---|---|---|
| A, `@NullRestricted` | 1.05 MB (0.70%) | 1.41 MB (1.0%) |
| A, nullable value class | 0.53 MB (0.36%) | 1.12 MB (0.80%) |
| B, `@NullRestricted` | 1.06 MB (0.41%) | 2.66 MB (1.17%) |
| B, nullable value class | −0.15 MB | 1.34 MB (0.59%) |

CPU (JMH, ns per element):

| benchmark | identity (JDK 27 GA / EA3) | value class (EA3 / EA3+COH) |
|---|---:|---:|
| `artifact.getExecPath().hashCode()` | 30 / 27 | 22 / 22 |
| `artifact.getExecPath().getPathString().length()` | 35 / 33 | 27 / 30 |
| `HashMap<PathFragment,_>.get(artifact.getExecPath())` | 144 / 128 | 152 **+24 B alloc** / 134 **+16 B alloc** |
| sort by exec path | 1756 / 1356 | 1402 / 1339 |

Field-access paths lose one dependent load. Every use as an `Object` (map key, collection
element) allocates a fresh box; C2 did not scalarize through `HashMap.get`.

Required refactoring: `PathFragment` is an `abstract sealed` class with three subclasses (the
subclass encodes the drive-string length); it would have to become a single `final` value class.
`@NullRestricted` fields must be strictly initialized (assigned before `super()`). Any
identity-based use (`==`, interners, weak keys) changes meaning.

## Side finding

In workload B, the 366k `PathFragment`s have only 216k distinct path strings: each source file's
path appears twice, as separate `PathFragment` + `String` + `byte[]` copies. Judging by the
instance counts, these are `RootedPath.rootRelativePath` and `SourceArtifact.execPath`. The
duplicates cost ~10.8 MB (≈4.7% of heap), about 4× what flattening `PathFragment` would save, and
removing them needs no Valhalla.
