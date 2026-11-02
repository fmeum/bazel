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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.MapBasedActionGraph;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link IncrementalArtifactConflictFinder}. */
@RunWith(JUnit4.class)
public final class IncrementalArtifactConflictFinderTest {
  private final FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fileSystem.getPath("/execroot");
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out");
  private final ArtifactRoot sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));

  private final Map<SkyKey, SkyValue> values = new HashMap<>();
  private final Map<SkyKey, ImmutableList<SkyKey>> deps = new HashMap<>();
  private final IncrementalArtifactConflictFinder finder =
      new IncrementalArtifactConflictFinder(
          new MapBasedActionGraph(new ActionKeyContext()), new FakeWalkableGraph());

  private int nextInputIndex = 0;

  @Test
  public void sharedDependency_registeredOnce() throws Exception {
    ActionLookupKey c = key("c");
    define(c, ImmutableList.of(action(c, "c")));
    ActionLookupKey a = key("a");
    define(a, ImmutableList.of(action(a, "a")), c);
    ActionLookupKey b = key("b");
    define(b, ImmutableList.of(action(b, "b")), c);

    assertThat(check(a)).isEmpty();
    assertThat(check(b)).isEmpty();
    assertThat(finder.getOutputArtifactCount()).isEqualTo(3);
  }

  @Test
  public void conflict_failsKeysDependingOnTheConflictingAction() throws Exception {
    ActionLookupKey a = key("a");
    define(a, ImmutableList.of(action(a, "x")));
    ActionLookupKey b = key("b");
    ActionAnalysisMetadata bAction = action(b, "x");
    define(b, ImmutableList.of(bAction));
    ActionLookupKey c = key("c");
    define(c, ImmutableList.of(), b);
    ActionLookupKey d = key("d");
    define(d, ImmutableList.of(), a);

    assertThat(check(a)).isEmpty();
    // The action registered second loses the conflict.
    assertThat(check(b).keySet()).containsExactly(bAction);
    assertThat(check(c).keySet()).containsExactly(bAction);
    assertThat(check(d)).isEmpty();
  }

  @Test
  public void prefixConflictFoundLater_invalidatesMemoizedResults() throws Exception {
    // A first conflict makes the finder memoize the transitive conflicts of every visited key.
    ActionLookupKey p = key("p");
    define(p, ImmutableList.of(action(p, "p")));
    ActionLookupKey q = key("q");
    define(q, ImmutableList.of(action(q, "p")));
    assertThat(check(p)).isEmpty();
    assertThat(check(q)).isNotEmpty();

    ActionLookupKey l = key("l");
    ActionAnalysisMetadata lAction = action(l, "dir/file");
    define(l, ImmutableList.of(lAction));
    ActionLookupKey t1 = key("t1");
    define(t1, ImmutableList.of(), l);
    assertThat(check(t1)).isEmpty();

    // A prefix conflict marks the previously registered action of l as conflicting as well.
    ActionLookupKey n = key("n");
    ActionAnalysisMetadata nAction = action(n, "dir");
    define(n, ImmutableList.of(nAction));
    assertThat(check(n).keySet()).containsExactly(lAction, nAction);

    ActionLookupKey t2 = key("t2");
    define(t2, ImmutableList.of(), l);
    assertThat(check(t2).keySet()).containsExactly(lAction);
  }

  @Test
  public void failedDependency_skipped() throws Exception {
    ActionLookupKey p = key("p");
    define(p, ImmutableList.of(action(p, "p")));
    ActionLookupKey q = key("q");
    define(q, ImmutableList.of(action(q, "p")));
    assertThat(check(p)).isEmpty();
    assertThat(check(q)).isNotEmpty();

    ActionLookupKey failed = key("failed");
    ActionLookupKey a = key("a");
    define(a, ImmutableList.of(action(a, "a")), failed);
    assertThat(check(a)).isEmpty();
  }

  private ImmutableMap<ActionAnalysisMetadata, ActionConflictException> check(ActionLookupKey key)
      throws InterruptedException {
    return finder.findArtifactConflicts(key).conflicts();
  }

  private static ActionLookupKey key(String name) {
    return new InjectedActionLookupKey(name);
  }

  private void define(
      ActionLookupKey key, ImmutableList<ActionAnalysisMetadata> actions, SkyKey... directDeps) {
    values.put(key, new FakeActionLookupValue(actions));
    deps.put(key, ImmutableList.copyOf(directDeps));
  }

  /**
   * Creates an action owned by {@code owner} with the given outputs. Each action gets a unique
   * input so that two actions with the same outputs are never considered shareable.
   */
  private ActionAnalysisMetadata action(ActionLookupKey owner, String... outputs) {
    Artifact input = ActionsTestUtil.createArtifact(sourceRoot, "input" + nextInputIndex++);
    ImmutableSet.Builder<Artifact> outputArtifacts = ImmutableSet.builder();
    for (String output : outputs) {
      outputArtifacts.add(
          DerivedArtifact.create(
              outputRoot, outputRoot.getExecPath().getRelative(output), owner));
    }
    return new TestAction(
        TestAction.NO_EFFECT,
        NestedSetBuilder.create(Order.STABLE_ORDER, input),
        outputArtifacts.build());
  }

  private record FakeActionLookupValue(ImmutableList<ActionAnalysisMetadata> actions)
      implements ActionLookupValue {
    @Override
    public ImmutableList<ActionAnalysisMetadata> getActions() {
      return actions;
    }
  }

  /** A graph in which keys without a value are considered to have failed to evaluate. */
  private final class FakeWalkableGraph implements WalkableGraph {
    @Nullable
    @Override
    public SkyValue getValue(SkyKey key) {
      return values.get(key);
    }

    @Override
    public Iterable<SkyKey> getDirectDeps(SkyKey key) {
      assertThat(values).containsKey(key);
      return deps.get(key);
    }

    @Override
    public Map<SkyKey, SkyValue> getSuccessfulValues(Iterable<? extends SkyKey> keys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<SkyKey, Exception> getMissingAndExceptions(Iterable<SkyKey> keys) {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Exception getException(SkyKey key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCycle(SkyKey key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<SkyKey, Iterable<SkyKey>> getDirectDeps(Iterable<SkyKey> keys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<SkyKey, Iterable<SkyKey>> getReverseDeps(Iterable<? extends SkyKey> keys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<SkyKey, Pair<SkyValue, Iterable<SkyKey>>> getValueAndRdeps(Iterable<SkyKey> keys) {
      throw new UnsupportedOperationException();
    }
  }
}
