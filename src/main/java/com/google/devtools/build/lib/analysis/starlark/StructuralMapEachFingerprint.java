// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.starlark;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Location;

/**
 * Computes the action key contribution of an {@code Args.add_all} or {@code Args.add_joined} call
 * with a Starlark {@code map_each} function without calling the function.
 *
 * <p>The strings a {@code map_each} function returns are a deterministic function of:
 *
 * <ul>
 *   <li>its code and the code it calls, as well as the values of the globals it reads, all of which
 *       are covered by the transitive digest of the .bzl file that defines it, and the repository
 *       mapping of that file;
 *   <li>the values it captures from enclosing functions and its default parameter values;
 *   <li>the values it is applied to, which are fingerprinted by everything Starlark code can
 *       observe about them (e.g., the root, path and owner of a {@code File});
 *   <li>the Starlark semantics, path mapping mode and Bazel version, which determine the behavior
 *       of builtins;
 *   <li>if it expands directories, the contents of the expanded tree artifacts. Expansion fails
 *       unless these are inputs of the action, so the action cache already checks their metadata.
 * </ul>
 *
 * <p>Fingerprinting these instead of the function's results avoids evaluating Starlark code when
 * checking the action cache, which can dominate the time spent in fully cached builds. Elements of
 * nested sets are fingerprinted with a singleton {@link CommandLineItem.MapFn}, so their digests
 * are memoized and shared across all actions in a build.
 *
 * <p>If any value is not supported, no structural fingerprint is computed and callers have to fall
 * back to evaluating the function.
 */
final class StructuralMapEachFingerprint {

  private static final UUID STRUCTURAL_MAP_EACH_UUID =
      UUID.fromString("0d3a2c4e-5b51-4b55-9a0c-8f9f86d2f1a7");

  // Repository mappings can be large and are shared by all functions defined in a .bzl file.
  private static final LoadingCache<RepositoryMapping, byte[]> REPO_MAPPING_DIGESTS =
      Caffeine.newBuilder()
          .weakKeys()
          .build(
              repoMapping -> {
                Fingerprint fp = new Fingerprint();
                fp.addString(repoMapping.contextRepo().getName());
                fp.addStringMap(
                    Maps.transformValues(repoMapping.entries(), RepositoryName::getName));
                return fp.digestAndReset();
              });

  // StarlarkSemantics#toString lists all non-default options in key order.
  private static final LoadingCache<StarlarkSemantics, byte[]> SEMANTICS_DIGESTS =
      Caffeine.newBuilder()
          .weakKeys()
          .build(semantics -> new Fingerprint().addString(semantics.toString()).digestAndReset());

  private StructuralMapEachFingerprint() {}

  /**
   * Adds a fingerprint of the result of applying {@code mapEach} to {@code values} to {@code
   * fingerprint} without calling {@code mapEach}.
   *
   * @param values either a {@link NestedSet} or a {@link List} of the values to fingerprint
   * @return false if no structural fingerprint can be computed, in which case nothing has been
   *     added to {@code fingerprint}
   */
  static boolean tryAddToFingerprint(
      StarlarkCallable mapEach,
      Object values,
      StarlarkSemantics starlarkSemantics,
      CoreOptions.OutputPathsMode outputPathsMode,
      ActionKeyContext actionKeyContext,
      Fingerprint fingerprint)
      throws InterruptedException {
    if (!(mapEach instanceof StarlarkFunction fn)
        || !(fn.getModule().getClientData() instanceof BazelModuleContext moduleContext)) {
      return false;
    }
    // Use a separate fingerprint so that nothing is added to the action key if any of the values
    // turns out to be unsupported.
    Fingerprint fp = new Fingerprint();
    Consumer<String> sink = fp::addString;
    try {
      fp.addUUID(STRUCTURAL_MAP_EACH_UUID);
      fp.addString(BazelVersion.RELEASE_NAME);
      fp.addBytes(SEMANTICS_DIGESTS.get(starlarkSemantics));
      fp.addInt(outputPathsMode.ordinal());

      fp.addBytes(moduleContext.bzlTransitiveDigest());
      // Not covered by the transitive digest, but affects e.g. Label("@foo//:bar").
      fp.addBytes(REPO_MAPPING_DIGESTS.get(moduleContext.repoMapping()));
      fp.addString(fn.getName());
      Location location = fn.getLocation();
      fp.addInt(location.line());
      fp.addInt(location.column());
      if (!fn.isGlobal()) {
        // The default values and captured variables of a top-level function are derived from the
        // module's globals and thus covered by its transitive digest, but those of nested functions
        // and lambdas may come from the locals of the enclosing function.
        List<Object> freeVarValues = fn.getFreeVarValues();
        fp.addInt(freeVarValues.size());
        for (Object value : freeVarValues) {
          addValue(value, sink, actionKeyContext);
        }
        int numParameters = fn.getParameterNames().size();
        for (int i = 0; i < numParameters; i++) {
          addValue(fn.getDefaultValue(i), sink, actionKeyContext);
        }
      }

      if (values instanceof NestedSet<?> nestedSet) {
        actionKeyContext.addNestedSetToFingerprint(IdentityMapFn.INSTANCE, fp, nestedSet);
      } else {
        List<?> list = (List<?>) values;
        fp.addInt(list.size());
        for (Object value : list) {
          addValue(value, sink, actionKeyContext);
        }
      }
    } catch (UnsupportedValueException e) {
      return false;
    } catch (CommandLineExpansionException e) {
      throw new IllegalStateException("IdentityMapFn does not throw checked exceptions", e);
    }
    fingerprint.addBytes(fp.digestAndReset());
    return true;
  }

  /**
   * Adds everything Starlark code can observe about {@code value} to {@code sink}.
   *
   * @param actionKeyContext used to fingerprint nested sets; null if nested sets are not supported
   *     in the current context
   * @throws UnsupportedValueException if {@code value} or any value it contains is not supported
   */
  private static void addValue(
      @Nullable Object value, Consumer<String> sink, @Nullable ActionKeyContext actionKeyContext)
      throws InterruptedException {
    switch (value) {
      // A required parameter or an unbound captured variable.
      case null -> sink.accept("0");
      case NoneType none -> sink.accept("N");
      case Boolean b -> sink.accept(b ? "T" : "F");
      case String s -> {
        sink.accept("s");
        sink.accept(s);
      }
      case StarlarkInt i -> {
        sink.accept("i");
        sink.accept(i.toString());
      }
      case StarlarkFloat f -> {
        sink.accept("f");
        sink.accept(Long.toHexString(Double.doubleToRawLongBits(f.toDouble())));
      }
      case Label label -> {
        sink.accept("l");
        sink.accept(label.getUnambiguousCanonicalForm());
      }
      case Artifact artifact -> addArtifact(artifact, sink);
      case Tuple tuple -> {
        sink.accept("t");
        sink.accept(Integer.toString(tuple.size()));
        for (Object element : tuple) {
          addValue(element, sink, actionKeyContext);
        }
      }
      case StarlarkList<?> list when list.isImmutable() -> {
        sink.accept("L");
        sink.accept(Integer.toString(list.size()));
        for (Object element : list) {
          addValue(element, sink, actionKeyContext);
        }
      }
      case Dict<?, ?> dict when dict.isImmutable() -> {
        sink.accept("d");
        sink.accept(Integer.toString(dict.size()));
        for (Map.Entry<?, ?> entry : dict.entrySet()) {
          addValue(entry.getKey(), sink, actionKeyContext);
          addValue(entry.getValue(), sink, actionKeyContext);
        }
      }
      case Depset depset when actionKeyContext != null -> {
        Fingerprint nested = new Fingerprint();
        try {
          actionKeyContext.addNestedSetToFingerprint(
              IdentityMapFn.INSTANCE, nested, depset.getSet());
        } catch (CommandLineExpansionException e) {
          throw new IllegalStateException("IdentityMapFn does not throw checked exceptions", e);
        }
        sink.accept("D");
        sink.accept(nested.hexDigestAndReset());
      }
      default -> throw UnsupportedValueException.INSTANCE;
    }
  }

  private static void addArtifact(Artifact artifact, Consumer<String> sink) {
    if (artifact.isFileset()) {
      // The contents of a fileset may be observed via expand_directories, but are not reflected in
      // the metadata of the fileset artifact.
      throw UnsupportedValueException.INSTANCE;
    }
    sink.accept("a");
    // The kind of artifact determines is_source, is_directory and is_symlink.
    sink.accept(artifact.getClass().getSimpleName());
    sink.accept(artifact.isTreeArtifact() ? "t" : artifact.isSymlink() ? "u" : "f");
    sink.accept(artifact.getRoot().getExecPathString());
    // The exec path determines path, short_path, basename, dirname and extension.
    sink.accept(artifact.getExecPathString());
    Label owner = artifact.getOwnerLabel();
    sink.accept(owner == null ? "" : owner.getUnambiguousCanonicalForm());
    if (artifact instanceof TreeFileArtifact treeFileArtifact) {
      // Determines tree_relative_path.
      sink.accept(treeFileArtifact.getParent().getExecPathString());
    }
  }

  /**
   * Holds the Bazel version, which is initialized lazily as it is only available after server
   * startup. Avoids synchronization on {@link BlazeVersionInfo#instance} for every action key.
   */
  private static final class BazelVersion {
    static final String RELEASE_NAME = BlazeVersionInfo.instance().getReleaseName();
  }

  /** Fingerprints the elements of nested sets for {@link #tryAddToFingerprint}. */
  private static final class IdentityMapFn implements CommandLineItem.MapFn<Object> {
    static final IdentityMapFn INSTANCE = new IdentityMapFn();

    @Override
    public void expandToCommandLine(Object object, Consumer<String> args)
        throws InterruptedException {
      // Nested sets can't contain nested sets, but a struct-like value could reference one. Since
      // the digests computed by this function are memoized per nested set node, it must not depend
      // on other nested sets.
      addValue(object, args, /* actionKeyContext= */ null);
    }
  }

  /**
   * Thrown if a value can't be fingerprinted structurally. Unchecked so that it can be thrown from
   * a {@link CommandLineItem.MapFn}.
   */
  private static final class UnsupportedValueException extends RuntimeException {
    static final UnsupportedValueException INSTANCE = new UnsupportedValueException();

    private UnsupportedValueException() {
      super(null, null, /* enableSuppression= */ false, /* writableStackTrace= */ false);
    }
  }
}
