Project: /_project.yaml
Book: /_book.yaml
# Bazel roadmap

{% include "_buttons.html" %}

As Bazel continues to evolve in response to your needs, we want to share our
2025 roadmap update.

We plan to bring Bazel 9.0
[long term support (LTS)](https://bazel.build/release/versioning) to you in late
2025.

## Full transition to Bzlmod

[Bzlmod](https://bazel.build/docs/bzlmod) has been the standard external
dependency system in Bazel since Bazel 7, replacing the legacy WORKSPACE system.
As of March 2025, the [Bazel Central Registry](https://registry.bazel.build/)
hosts more than 650 modules.

With Bazel 9, we will completely remove WORKSPACE functionality, and Bzlmod will
be the only way to introduce external dependencies in Bazel. To minimize the
migration cost for the community, we'll focus on further improving our migration
[guide](https://bazel.build/external/migration) and
[tool](https://github.com/bazelbuild/bazel-central-registry/tree/main/tools#migrate_to_bzlmodpy).

Additionally, we aim to implement an improved shared repository cache (see
[#12227](https://github.com/bazelbuild/bazel/issues/12227))
with garbage collection, and may backport it to Bazel 8. The Bazel Central
Registry will also support verifying SLSA attestations.

## Migration of Android, C++, Java, Python, and Proto rules

With Bazel 8, we have migrated support for Android, Java, Python, and Proto
rules out of the Bazel codebase into Starlark rules in their corresponding
repositories. To ease the migration, we implemented the autoload features in
Bazel, which can be controlled with
[--incompatible_autoload_externally](https://github.com/bazelbuild/bazel/issues/23043)
and [--incompatible_disable_autoloads_in_main_repo](https://github.com/bazelbuild/bazel/issues/25755)
flags.

With Bazel 9, we aim to disable autoloads by default and require every project
to explicitly load required rules in BUILD files.

We will rewrite most of C++ language support to Starlark, detach it from Bazel
binary and move it into the [/rules_cc](https://github.com/bazelbuild/rules_cc)
repository. This is the last remaining major language support that is still part
of Bazel.

We're also porting unit tests for C++, Java, and Proto rules to Starlark, moving
them to repositories next to the implementation to increase velocity of rule
authors.

## Starlark improvements

Bazel will have the ability to evaluate symbolic macros lazily. This means that
a symbolic macro won't run if the targets it declares are not requested,
improving performance for very large packages.

Starlark will have an experimental type system, similar to Python's type
annotations. We expect the type system to stabilize _after_ Bazel 9 is launched.

## Configurability

Our main focus is reducing the cost and confusion of build flags.

We're [experimenting](https://github.com/bazelbuild/bazel/issues/24839) with a
new project configuration model that doesn't make users have to know which build
and test flags to set where. So `$ bazel test //foo` automatically sets the
right flags based on `foo`'s project's policy. This will likely remain
experimental in 9.0 but guiding feedback is welcome.

[Flag scoping](https://github.com/bazelbuild/bazel/issues/24042) lets you strip
out Starlark flags when they leave project boundaries, so they don't break
caching on transitive dependencies that don't need them. This makes builds that
use [transitions](https://bazel.build/extending/config#user-defined-transitions)
cheaper and faster.
[Here's](https://github.com/gregestren/snippets/tree/master/project_scoped_flags)
an example. We're extending the idea to control which flags propagate to
[exec configurations](https://bazel.build/extending/rules#configurations) and
are considering even more flexible support like custom Starlark to determine
which dependency edges should propagate flags.

We're up-prioritizing effort to move built-in language flags out of Bazel and
into Starlark, where they can live with related rule definitions.

## Remote execution improvements

We plan to add support for asynchronous execution, speeding up remote execution
by increasing parallelism.

---

To follow updates to the roadmap and discuss planned features, join the
community Slack server at [slack.bazel.build](https://slack.bazel.build/).

*This roadmap is intended to help inform the community about the team's
intentions for Bazel 9.0. Priorities are subject to change in response to
developer and customer feedback, or to new market opportunities.*
