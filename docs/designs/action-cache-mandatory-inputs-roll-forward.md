# Roll-forward design: invalidate discovered inputs when mandatory inputs change, without duplicate hashing

Status: proposal
Tracking: roll-forward of bazelbuild/bazel#27492 (reverted by `aea3f20` before 9.0.0)
Related discussion: bazelbuild/bazel#27492 (comment 3670388740)

## 1. Background

### 1.1 The bug the original PR fixed

PR #27492 ("Multiple Module Interfaces per `cc_library`") fixed a real correctness
bug with C++20 module compilation. The scenario:

- `a.cppm` and `b.cppm` live in the same `cc_library`.
- In build N, `a.cppm` imports `b`, so compiling `a.pcm` **discovers** `b.pcm`
  as an input. The action cache persists `b.pcm` in the entry's
  `discoveredInputPaths`.
- The developer edits the sources so that in build N+1, `a` no longer imports
  `b`, but `b` now imports `a`.

On build N+1, before the action for `a.pcm` runs, Skyframe asks the action cache
for the previously discovered inputs and requests them as dependencies
(`ActionExecutionFunction.collectInputs` ->
`AllInputs.getAllInputs()`, which folds in
`ActionCacheChecker.getCachedInputs(...)` ->
`ActionCache.Entry.getDiscoveredInputPaths()`). This re-introduces the stale
edge `a.pcm -> b.pcm`, while the new sources introduce `b.pcm -> a.pcm`. The
result is a **cycle in the Skyframe action graph**, surfaced before the action
even executes.

The mandatory input that actually changed is `a`'s **modmap** (it lists the
modules `a` imports). The fix: when an action's *mandatory* inputs change, do
**not** reuse the previously *discovered* inputs; force re-discovery, which
produces the correct (cycle-free) input set.

### 1.2 Why it was rolled back

The original implementation stored a digest of the mandatory inputs in the
cache entry and, on the read path, recomputed the current mandatory-inputs
digest to compare against it. As lberki noted in comment 3670388740:

> mandatory inputs are now iterated over twice: in `computeMandatoryInputHash()`
> and where the key of the whole action is computed.

The duplicated work cost ~425s of extra CPU in internal benchmarks, dominated by
`MetadataDigestUtils.getDigest()` (~211s) and `Fingerprint.addString()` (~91s) —
i.e. the per-input path+metadata fingerprinting, done a second time for every
mandatory input of every action. lberki's suggested direction:

> [the fix] would require carefully distinguishing between mandatory and
> discovered inputs ... a counterpart for `Action.getMandatoryInputs()` called
> `getDiscoveredInputs()`.

This document specifies that clean roll-forward.

## 2. The key invariant we exploit

`DigestUtils.combineUnordered(byte[], byte[])`
(`src/main/java/com/google/devtools/build/lib/vfs/DigestUtils.java:192`) combines
two digests by **byte-wise addition mod 256**. That operation is **commutative
and associative**, and the empty seed (`new byte[1]` in
`MetadataDigestUtils.fromMetadata`,
`src/main/java/com/google/devtools/build/lib/actions/cache/MetadataDigestUtils.java:56`)
contributes `0`.

Therefore, for any partition of the per-input digests into disjoint subsets, the
combined digest of the whole set equals the `combineUnordered` of the subset
digests:

```
fromMetadata(M ∪ D) == combineUnordered(fromMetadata(M), fromMetadata(D))
```

where `M` = mandatory inputs and `D` = everything else (discovered inputs +
outputs + ...). This is what lets us compute the mandatory-inputs sub-digest as a
**by-product** of the same single pass that already produces the full entry
digest — each input's expensive per-input fingerprint is computed exactly once.

This property should be locked down by a unit test (see §7).

## 3. Today's digest computation (for reference)

The full entry digest is computed in `ActionCache.Entry.Builder.computeDigest`
(`src/main/java/com/google/devtools/build/lib/actions/cache/ActionCache.java:426`):

```java
fp.addString(actionKey);
fp.addBoolean(discoversInputs);
fp.addBytes(MetadataDigestUtils.fromMetadata(metadataMap)); // all inputs + outputs
fp.addBytes(computeMapDigest(clientEnv));
fp.addString(actionExecutionSalt);
fp.addInt(outputPermissions.getPermissionsMode());
fp.addBoolean(useArchivedTreeArtifacts);
```

`metadataMap` is the union of inputs and outputs. The `Builder` already
distinguishes mandatory from discovered inputs implicitly: in
`ActionCacheChecker.updateActionCache`
(`.../ActionCacheChecker.java:716`), mandatory inputs are added with
`saveExecPath=false` and discovered inputs with `saveExecPath=true`
(`excludePathsFromActionCache = action.getMandatoryInputs().toSet()`).

## 4. Proposed design

### 4.1 Data model

Add an optional mandatory-inputs sub-digest to the cache entry, populated **only
for actions that discover inputs** (the only actions that can hit the cycle bug
and the only ones that reuse discovered inputs):

```java
// ActionCache.Entry
@Nullable private final byte[] mandatoryInputsDigest; // non-null iff discoversInputs()
```

Non-discovering actions get `null` here and incur **zero** additional work — any
change to their (entirely mandatory) inputs already invalidates the full entry
digest. This alone removes the regression for the overwhelming majority of
actions.

### 4.2 Write path — one pass, no duplicate hashing

Teach `ActionCache.Entry.Builder` to mark which entries are mandatory inputs and
to accumulate two digests in the **single** existing pass. Replace the
`metadataMap`-only flow with a structure that tags mandatory inputs, e.g.:

```java
// Builder: track which exec paths are mandatory inputs (only meaningful when discoversInputs).
private final HashMap<String, FileArtifactValue> metadataMap = new HashMap<>();
private final HashSet<String> mandatoryInputPaths = new HashSet<>(); // only filled when discovering

public Builder addInputFile(Artifact artifact, FileArtifactValue metadata, boolean saveExecPath) {
  String execPath = artifact.getExecPathString();
  if (discoveredInputPaths != null) {
    if (saveExecPath) {
      discoveredInputPaths.add(execPath);   // discovered input
    } else {
      mandatoryInputPaths.add(execPath);    // mandatory input
    }
  }
  metadataMap.put(execPath, metadata);
  return this;
}
```

Then compute both digests together, reusing each per-input digest:

```java
// MetadataDigestUtils: new entry point that returns the full digest and, as a
// by-product, the combined digest of the subset whose keys satisfy `isMandatory`.
public static SplitDigest fromMetadataSplit(
    Map<String, FileArtifactValue> mdMap, Predicate<String> isMandatory) {
  byte[] all = new byte[1];        // empty-string seed, contributes 0
  byte[] mandatory = new byte[1];
  Fingerprint fp = new Fingerprint();
  for (var entry : mdMap.entrySet()) {
    byte[] d = getDigest(fp, entry.getKey(), entry.getValue()); // computed ONCE
    all = DigestUtils.combineUnordered(all, d.clone());
    if (isMandatory.test(entry.getKey())) {
      mandatory = DigestUtils.combineUnordered(mandatory, d);
    }
  }
  return new SplitDigest(all, mandatory);
}
```

(The `clone()` is only needed because `combineUnordered` mutates its larger
argument in place; an equivalent allocation-free formulation that folds `d` into
both accumulators is fine too. The point is that `getDigest` — the part lberki
measured at ~211s — runs exactly once per input.)

`computeDigest` uses `all` exactly as `fromMetadata(metadataMap)` is used today,
so the on-the-wire meaning of the full digest is unchanged; `mandatory` becomes
the entry's `mandatoryInputsDigest`. For non-discovering actions, skip the
mandatory accumulator entirely and keep calling `fromMetadata`.

### 4.3 Read path — reuse the digest, don't recompute it

This is the crux of avoiding the regression. The reuse decision happens in
`ActionExecutionFunction.collectInputs`
(`.../ActionExecutionFunction.java:587`), which calls
`getActionCachedInputs` -> `ActionCacheChecker.getCachedInputs` and folds the
result into the Skyframe dep request — i.e. **before** `isUpToDate` runs. We must
gate the reuse of `entry.getDiscoveredInputPaths()` on the mandatory inputs being
unchanged.

To gate it without a second hash of the mandatory inputs:

1. The mandatory inputs' metadata is requested from Skyframe regardless (they are
   `action.getInputs()` for a non-pruned discovering action). Compute their digest
   **once** with `MetadataDigestUtils.fromMetadata(mandatoryInputs)` at the point
   we are about to decide on reuse, and compare against
   `entry.getMandatoryInputsDigest()`.
2. **Thread that already-computed digest through** to the action-cache check so
   `isUpToDate` does not fingerprint the mandatory inputs again. Concretely:
   - Stash the computed `currentMandatoryInputsDigest` on the `Token` (or on the
     input-discovery `state`).
   - In `isUpToDate` / `ActionCache.Entry.Builder`, accept the precomputed
     mandatory digest and, instead of re-fingerprinting mandatory inputs, fold it
     into the full digest via
     `combineUnordered(precomputedMandatoryDigest, fromMetadata(nonMandatoryEntries))`.
     The associativity invariant (§2) guarantees this equals the unsplit digest.

   This means mandatory inputs are fingerprinted **exactly once per build per
   action**, shared between the reuse-gate and the up-to-date check — eliminating
   the "iterated twice" cost that motivated the rollback.

Behavior when the mandatory digest differs (rare — only when modmap/mandatory
inputs actually change):

- `getCachedInputs` returns an empty/`defaultInputs`-only set so the stale
  discovered inputs are **not** requested as Skyframe deps -> no cycle.
- The action is re-discovered and re-executed (its full digest no longer matches
  anyway), and `updateActionCache` writes a fresh entry with a fresh
  `mandatoryInputsDigest` and fresh `discoveredInputPaths`.

### 4.4 `Action.getDiscoveredInputs()` (optional structural cleanup)

lberki's suggested counterpart `Action.getDiscoveredInputs()` is a clean way to
make the mandatory/discovered split explicit rather than inferring it from
`saveExecPath`. It is **not required** for the dedup (the `saveExecPath` flag
already encodes the split), but it makes the `Builder` API self-documenting and
removes the `mandatoryInputPaths` set in §4.2. Recommended as a follow-up rather
than a prerequisite, to keep the roll-forward small and reviewable.

## 5. Serialization & versioning

`ActionCache.Entry` is serialized in
`CompactPersistentActionCache` (`encode`/`decode`, around
`.../CompactPersistentActionCache.java:899`). Add the
`mandatoryInputsDigest` immediately after the discovered input paths, written
**only when `entry.discoversInputs()`** (mirroring `prunedInputs`):

```java
if (entry.discoversInputs()) {
  VarInt.putVarInt(entry.prunedInputs() ? 1 : 0, sink);
  // ... discoveredInputPaths ...
  MetadataDigestUtils.write(entry.getMandatoryInputsDigest(), sink); // new
}
```

Bump `CompactPersistentActionCache.VERSION` (currently `25`,
`.../CompactPersistentActionCache.java:79`) to `26` so existing on-disk caches are
discarded rather than misread. Discarding is safe: a cold action cache only costs
one rebuild.

## 6. Why this avoids the rollback's regression

- **Non-discovering actions**: untouched, `mandatoryInputsDigest == null`, no
  extra hashing. This is most actions.
- **Discovering actions, write path**: mandatory + full digests come from one
  pass; each per-input fingerprint runs once (vs. twice before).
- **Discovering actions, read path**: the mandatory digest computed for the
  reuse-gate is reused by the up-to-date check via `combineUnordered`, so
  mandatory inputs are fingerprinted once per build (vs. twice before).

Net: the dominant `getDigest()`/`addString()` cost that lberki measured is paid
once, restoring pre-#27492 hashing volume while keeping the correctness fix.

## 7. Testing

- **Invariant test**: `fromMetadataSplit` and the `combineUnordered`-based
  recombination produce byte-identical digests to `fromMetadata` over the union,
  for random partitions (locks in §2).
- **Correctness regression test**: the C++20 modules cycle from #27492 — flip the
  import direction between two `.cppm` files in one `cc_library` across two builds
  and assert no cycle and a correct rebuild. (Reuse/port the integration test the
  original PR added.)
- **Cache-hit stability**: an incremental no-op build still gets action-cache
  hits for discovering actions (the new digest field must be stable across builds
  with unchanged inputs).
- **Version bump**: old-version cache file is detected as incompatible and
  rebuilt, not misparsed.
- **Benchmark/sanity**: confirm no measurable CPU regression on a
  C++-heavy build relative to current master.

## 8. Files touched (summary)

- `actions/cache/MetadataDigestUtils.java` — add split/by-product digest helper.
- `actions/cache/ActionCache.java` — `Entry.mandatoryInputsDigest`, `Builder`
  changes, `computeDigest` split.
- `actions/ActionCacheChecker.java` — gate `getCachedInputs` reuse on mandatory
  digest; thread precomputed mandatory digest into `isUpToDate`/`updateActionCache`.
- `actions/cache/CompactPersistentActionCache.java` — (de)serialize new field;
  bump `VERSION`.
- `skyframe/ActionExecutionFunction.java` / `SkyframeActionExecutor.java` — pass
  the precomputed mandatory digest through the cache-check call.
- (optional) `actions/Action.java` + implementations — `getDiscoveredInputs()`.
- Tests as in §7.
