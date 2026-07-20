---
created: 2026-07-20
last updated: 2026-07-20
status: Draft
reviewers:
title: "Aspect finalizers and pinned scopes: always-fresh compilation databases and developer environments"
authors:
  - @fmeum
---

# Abstract

Generating a `compile_commands.json` file for a single `cc_binary` is a natural fit for an aspect, but scaling this to many targets and keeping the result up to date as users edit files and `BUILD` files currently requires external tooling, manual refresh commands, or both.
This proposal adds two generic features to Bazel: *aspect finalizers*, which allow an aspect to register a single action that merges its per-target outputs across all top-level targets of a build, and *pinned scopes* in `PROJECT.scl`, which declare target patterns and aspects that Bazel additionally builds as part of every invocation.
Together, these features make artifacts such as compilation databases a side effect of the builds users already run, matching or exceeding the ergonomics of CMake's `CMAKE_EXPORT_COMPILE_COMMANDS`.
The same mechanism keeps [`bazel_env.bzl`](https://github.com/buildbuddy-io/bazel_env.bzl) environments fresh without user interaction, replacing its hand-maintained file watching machinery.

# Background

## The ergonomics bar

CMake emits a complete `compile_commands.json` at the build root as a side effect of every configure when `CMAKE_EXPORT_COMPILE_COMMANDS` is set.
Users never run a separate command, and the file is exactly as fresh as their last build.
This ergonomic property has a single cause: the database is a byproduct of commands users already run, over the scope they already build.
Any Bazel solution that requires a separate refresh command, an external daemon, or a wrapper script falls short of this bar.

## Existing approaches and their limitations

Applying an aspect to many targets is not the hard part.
`--aspects` and `--aspects_parameters` are ordinary build options ([`BuildRequestOptions`]) and can be set in `.bazelrc`, so an aspect can already be applied to whatever set of top-level targets a user builds, on every build.
The problems lie downstream of the aspect:

1. **No merge point.**
   An aspect emits one output (or output group) per target, and there is no way to register an action that consumes these outputs across all top-level targets.
   Bazel contains exactly one instance of this shape, the coverage combined report: a singleton `ActionLookupKey` with null label and configuration ([`CoverageReportValue`]), a hand-built `AbstractAction` created "at the very end of the analysis phase" ([`CoverageReportActionBuilder`]), injected outside the normal analysis graph via `BuildView#constructCoverageArtifacts` and `SkyframeExecutor#injectCoverageReportData`.
   Nothing Starlark-facing can express this, so existing solutions such as [hedron_compile_commands](https://github.com/hedronvision/bazel-compile-commands-extractor) and the IntelliJ aspect merge client-side in wrapper scripts run via `bazel run`.
2. **No freshness mechanism.**
   Nothing in Bazel re-runs work on behalf of the user.
   `--watchfs` only accelerates diff computation for the *next* user-initiated invocation, validation actions can only write declared outputs under `bazel-out`, and the only place user code executes after a build is `bazel run` itself.
   External watchers such as [ibazel](https://github.com/bazelbuild/bazel-watcher) exist, but require per-user setup and a dedicated terminal.
3. **Scope is not declared anywhere Bazel can act on.**
   A wrapper script must be told which targets to cover, duplicating information that increasingly lives in `PROJECT.scl`.

## PROJECT.scl today

`PROJECT.scl` files currently contain three scope-like concepts, all of which are *match-only*: they filter or classify targets the user already supplied on the command line, and none of them is ever expanded into a set of targets.

1. A build is associated with a project by walking up from each top-level target's package to the innermost `PROJECT.scl` ([`Project#getProjectFiles`]).
2. `active_directories` is a named map of workspace-relative directory prefixes with `-` exclusions, parsed into a `PathFragmentPrefixTrie` and consumed by Skyfocus as the working set for Skyframe graph minimization.
3. Each `buildable_unit` carries `target_patterns`, `flags`, and `is_default`.
   The patterns are matched by [`SimpleTargetPatternMatcher`], which deliberately implements only a subset of target pattern syntax (exact labels, `//pkg/...` prefix wildcards, and negations; no `:all` or `:*`, no `BUILD` loading) so that [`FlagSetFunction`] can answer the *reverse* question cheaply and before loading: given the targets the user requested, which default buildable unit's flags apply.

## bazel_env.bzl

`bazel_env.bzl` stages tool launchers and toolchain symlinks under the stable path `bazel-out/bazel_env-opt/bin/...`, which `direnv` adds to `PATH`.
Building the `bazel_env` target refreshes all tools; only cleanup of removed tools requires `bazel run`.
Since Bazel offers no way to rebuild the target automatically, the ruleset ships a hand-maintained approximation of Bazel's own dependency tracking: `watch_dirs` and `watch_files` attributes whose contents each launcher hashes with `sha256sum` on every tool invocation, triggering a `bazel build` when a hash changes.
The documentation of these attributes describes them as "a heuristic set of directories that approximates what Bazel tracks during the analysis phase", maintained manually and traded off against per-invocation hashing overhead.

# Proposed changes

## 1. Top-level aspect finalizers

An aspect may declare a *finalizer*: a Starlark function that Bazel invokes once per build after the aspect has been applied to all top-level targets, and that may register actions consuming the aspect's per-target outputs.

```starlark
def _compile_commands_impl(target, ctx):
    # Emits one JSON fragment per compilation action of the target.
    ...
    return [OutputGroupInfo(compile_commands_fragments = depset([fragment]))]

def _compile_commands_finalizer(ctx, targets):
    # targets: a list of structs with the fields `label` and `providers` for
    # every top-level target the aspect was successfully applied to.
    out = ctx.actions.declare_file("compile_commands.json")
    ctx.actions.run(
        executable = ctx.executable._merger,
        inputs = depset(transitive = [
            t.providers[OutputGroupInfo].compile_commands_fragments
            for t in targets
        ]),
        outputs = [out],
        ...
    )
    return [DefaultInfo(files = depset([out]))]

compile_commands = aspect(
    implementation = _compile_commands_impl,
    finalizer = _compile_commands_finalizer,
    attrs = {
        "_merger": attr.label(default = ..., executable = True, cfg = "exec"),
    },
)
```

The per-target fragments are ordinary artifacts, so incrementality is inherited from Skyframe: editing one source file re-runs one fragment action and the merge.

### Implementation details

- The implementation generalizes the coverage combined report machinery: instead of the hardcoded singleton `CoverageReportValue#COVERAGE_REPORT_KEY`, each finalizer receives an `ActionLookupKey` derived from the aspect's identity (definition label, aspect name, and a digest of its parameters).
- Keying by aspect identity rather than by the top-level target set means that successive invocations with different top-level targets recompute the actions under the *same* key, so the new merge action supersedes the old one instead of conflicting with it, mirroring how `injectCoverageReportData` replaces the coverage actions on each invocation.
- Finalizer outputs are placed under a configuration-independent directory derived from the aspect's identity, e.g. `bazel-out/_finalizers/<mangled aspect id>/`, following the precedent of `bazel-out/_coverage/`.
  This is necessary for correctness, not just stability: a merged artifact aggregates fragments across configurations and must not live under any single configuration's output directory.
  A configuration-dependent location would also make the artifact's path flip with `-c` settings and be skipped as ambiguous by the convenience symlink logic in multi-configuration builds.
- A project may assign a short alias for a finalizer's output directory (e.g. `bazel-out/_ide/`) via `PROJECT.scl`.
  Placing the alias claim in the project file rather than in the aspect definition makes clashes impossible by construction: the aliases form a dict keyed by name in a single file, whereas aspect-declared aliases from independent rulesets could silently claim the same directory and only collide when co-built.
- Within an invocation, output clashes between finalizers are prevented by the derived namespace and caught by the existing artifact prefix and action conflict checks, the same machinery that today rejects two aspects declaring the same output on one target.
- Analysis failures of individual top-level targets do not fail the finalizer; the `targets` list contains the successful applications only.
  This gives builds within a broken repository the `--keep_going`-like behavior a background artifact needs: a broken target in some corner of the scope degrades the merged output instead of failing unrelated builds.

### Backwards compatibility

This change is purely additive.
Aspects that do not declare a finalizer are unaffected, and coverage's bespoke implementation can be migrated to the generic mechanism separately (see follow-ups).

## 2. Pinned scopes in PROJECT.scl

A `PROJECT.scl` file may declare *pinned scopes*: combinations of target patterns, aspects, and output groups that Bazel adds to the set of top-level requests of every build-like invocation associated with the project.

```starlark
project = {
    "pinned_scopes": [
        pinned_scope(
            # Reuses the buildable unit's target_patterns and flags.
            buildable_unit = "ide",
            aspects = ["//tools/ide:compile_commands.bzl%compile_commands"],
            output_groups = ["compile_commands_fragments"],
        ),
        pinned_scope(
            target_patterns = ["//tools:bazel_env"],
        ),
    ],
}
```

Semantically, a pinned scope is equivalent to the user appending the expanded patterns (and aspect requests) to every command line: it introduces no new execution capability and builds in the same incremental Skyframe graph, so the steady-state cost when nothing relevant changed is change pruning only.
Pinned scopes are build-only; no target is ever *run* as a side effect of a build.

### Forward evaluation of target patterns

Pinned scopes require expanding buildable unit target patterns into a target set, which is a new capability: today these patterns are only ever *matched* backwards by [`SimpleTargetPatternMatcher`] for flag dispatch.
The restricted matcher grammar is a strict subset of real target pattern syntax, so the same `target_patterns` list can serve both roles without diverging semantics: matched backwards for flag resolution (cheap, before loading, as today) and expanded forwards through the ordinary target pattern machinery at loading time, where expansion is routine.
To keep the two readings consistent, pinnable buildable units initially inherit the matcher's restricted grammar; `:all`-style patterns are rejected with an error pointing at this limitation.

### Configuration

The targets of a pinned scope are built in the configuration described by the referenced buildable unit's `flags`, not in the invocation's configuration.
A background artifact serves the human rather than the current build and benefits from never moving: building with the user's flags of the day would re-analyze the pinned scope on every `-c` flip.
This is the same judgment `bazel_env.bzl` encodes in its fixed `bazel_env-opt` output directory transition.
A pinned scope without a buildable unit reference builds in the invocation's configuration.

### Error isolation

Errors in a pinned scope must not fail the user's requested build.
Loading and analysis errors within the pinned scope are reported as warnings and reduce the scope (feeding into finalizers as described in Change 1); only the user's explicit targets determine the invocation's exit code.

### Interaction with Skyfocus

A pinned scope may be declared as `scope = "active_directories"`, restricting the expansion to packages under the project's resolved working set (whether it came from `PROJECT.scl` or `--experimental_active_directories`).
The correspondence is direct: `active_directories` entries parse into directory prefix tries with exclusions, and each entry maps one-to-one onto the pattern grammar (`foo` to `//foo/...`, `-bar/baz` to `-//bar/baz/...`).
This is the semantically correct scope for a compilation database, not merely a compatibility measure: the working set and the database answer the same question, namely which files a human will edit here.
Under this scoping, pinned aspect applications land on targets whose Skyframe nodes Skyfocus retains anyway, dependencies outside collapse to the frontier as usual, and the database's memory and analysis cost scales with the working set rather than the repository.
The failure mode also matches: a file outside the working set has no database entry, and the fix, widening `active_directories`, is the same fix Skyfocus demands for editing that file at all.
Directory containment and target ownership are not identical (a file inside an active directory can belong to a target whose package lies outside it); the initial implementation restricts expansion to packages under the active directories and treats cross-package ownership as a reason to adjust the working set.

### Backwards compatibility

`pinned_scopes` is a new key in the project file schema and has no effect on Bazel versions that do not know it.
Builds in repositories without a `PROJECT.scl`, or whose project file declares no pinned scopes, are unaffected.

# Example: an always-fresh compile_commands.json

With both changes in place, the end-to-end setup for a project is:

1. A ruleset ships the `compile_commands` aspect and its finalizer (Change 1).
2. The project's `PROJECT.scl` declares a buildable unit `ide` with the desired flags and pins the aspect over the working set (Change 2), assigning the alias `_ide` to the finalizer's output directory.
3. A checked-in `.clangd` file points clangd at the merged database:

```yaml
CompileFlags:
  CompilationDatabase: bazel-out/_ide
```

Every `bazel build`, of any target, then incrementally refreshes exactly the fragments invalidated by the user's edits and re-merges the database.
No separate command, no daemon, no wrapper script, and no state outside the action graph.
Because the fragments are built (not merely analyzed), generated headers referenced by the compile commands exist on disk, which command-line-dump approaches cannot guarantee.
Per-configuration fragment keying additionally allows the merger to emit correct entries when the same source file is compiled for multiple platforms, a case CMake handles poorly.

# Application: bazel_env.bzl

A pinned scope containing the `bazel_env` target keeps all tool launchers and toolchain symlinks fresh as a side effect of every build.
This makes the ruleset's entire exec-time staleness machinery unnecessary: the `watch_dirs` and `watch_files` attributes, the lock file, and the per-invocation `sha256sum` comparison in the launcher template can all be deleted, replacing a manually maintained approximation of Bazel's dependency tracking with the real thing.

The remaining `bazel run`-only functionality is cleanup of tools removed from the environment, since Bazel does not retract no-longer-declared outputs of previous builds from `bazel-out`.
This can be addressed in the ruleset without further Bazel changes by making the `PATH`-facing `bin` directory a tree artifact of trampoline scripts (`exec`ing the real launchers, which keep their adjacent runfiles trees at a sibling output prefix): tree artifact rematerialization replaces the directory wholesale, so removals are handled by builds too.
A full tree artifact conversion is not possible because runfiles trees cannot be materialized inside tree artifacts and toolchain repository symlinks must not be traversed during tree artifact output collection, but the trampoline split sidesteps both constraints.

# Suggested follow-up changes

The following changes are deliberately kept out of the initial proposal, but the design leaves room for them.

1. **Runnable pinned targets.**
   An opt-in `run_on_change` flavor of pinned scopes that re-executes an executable target when its output digests change would cover cleanup-style use cases without trampoline workarounds.
   Running binaries as a side effect of `bazel build` is a new capability with real security and surprise concerns (restricted environment, explicit declaration in a checked-in file, BEP visibility), which is why it is not part of this proposal.
2. **Project introspection.**
   A machine-readable dump of the resolved project file, e.g. `bazel project describe --scl_config=ide --output=json`, would let editors and external tools answer "what should I build for this repository" from the same source of truth.
   The data is already structured and has protos ([`buildable_unit.proto`]).
   With pinned scopes, nothing in this proposal depends on it.
3. **Migrating the coverage report.**
   The combined coverage report is a hardcoded instance of an aspect finalizer.
   Once Change 1 is stable, the bespoke machinery in `CoverageReportActionBuilder` and its special-case injection path can be reimplemented on top of it.

# Alternatives considered

## aquery-based database generation

`bazel aquery 'mnemonic("CppCompile", deps(//...))' --output=jsonproto --include_commandline` renders full compile command lines from analysis state without executing anything, and external tools successfully build on this.
However, this path cannot guarantee that generated headers exist on disk, requires an external converter process and thus a refresh command or daemon, and duplicates scope information outside the build.
This proposal deliberately routes everything through aspects at build time instead.

## BEP-driven merge daemon

An aspect registered in `.bazelrc` combined with an editor-side process tailing the Build Event Protocol can maintain a merged database incrementally today, since BEP's `NamedSetOfFiles` events identify changed fragments per output group.
This achieves the ergonomics goal without Bazel changes, but at the cost of a per-user daemon, state outside the action graph, and a union-across-invocations cache with eviction logic.
Under this proposal, the pinned scope makes every merge total, eliminating the daemon and its state entirely.

## A native watch mode

A server-side continuous build mode would provide freshness even without user-initiated builds.
It is a much larger project, and the CMake bar only requires freshness after every configure or build, which pinned scopes meet.
Pinned scopes are also exactly what a future watch loop would re-evaluate, so nothing in this proposal is lost by deferring it.

## Starlark-declared convenience symlinks

An earlier draft proposed letting finalizers materialize a symlink at a workspace-relative path (e.g. `<workspace>/compile_commands.json`).
Since clangd, clang-tidy, and comparable consumers all accept a database *directory* via configuration, a checked-in `.clangd` pointing under `bazel-out` achieves the same discoverability while being versioned with the repository, so the feature was dropped in favor of configuration-independent finalizer output directories.

## Run-based refresh rules

A hedron-style `refresh` rule pinned as a runnable target would reuse existing ruleset code, but performs its work at `bazel run` time and therefore requires the runnable pinned targets deferred to follow-up work, reintroduces bazel-in-bazel invocations, and leaves scope discovery unsolved.

[`BuildRequestOptions`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/buildtool/BuildRequestOptions.java
[`CoverageReportValue`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/skyframe/CoverageReportValue.java
[`CoverageReportActionBuilder`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/bazel/coverage/CoverageReportActionBuilder.java
[`Project#getProjectFiles`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/analysis/Project.java
[`SimpleTargetPatternMatcher`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/collect/SimpleTargetPatternMatcher.java
[`FlagSetFunction`]: https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/skyframe/config/FlagSetFunction.java
[`buildable_unit.proto`]: https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/project/buildable_unit.proto
