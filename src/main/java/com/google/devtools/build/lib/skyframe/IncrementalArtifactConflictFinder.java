// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ActionConflictsAndStats;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An incremental artifact conflict finder that maintains a running state.
 *
 * <p>Once an ActionLookupKey is analyzed, its actions are registered with this conflict finder
 * before execution. The internal action graph accumulates these actions in order to detect a
 * conflict later on. There should be one instance of this class per build.
 */
@ThreadSafe
public final class IncrementalArtifactConflictFinder {
  private final MutableActionGraph threadSafeMutableActionGraph;
  private final ConcurrentMap<String, Object> pathFragmentTrieRoot;
  private final WalkableGraph walkableGraph;

  // The lock serializing the traversal-based conflict checks of top level keys.
  private final ReentrantLock lock = new ReentrantLock();

  /** The keys whose actions have already been registered with the action graph. */
  @GuardedBy("lock")
  private final Set<ActionLookupKey> visited = new HashSet<>();

  /** All conflicts found so far, keyed by the owner of the conflicting action. */
  @GuardedBy("lock")
  private final Map<ActionLookupKey, Map<ActionAnalysisMetadata, ActionConflictException>>
      badActionsByOwner = new HashMap<>();

  /**
   * Memoizes, for a key, the owners of conflicting actions in its transitive closure. Only valid
   * for the current contents of {@link #badActionsByOwner}: it is cleared whenever a new conflict
   * is found.
   */
  @GuardedBy("lock")
  private final Map<ActionLookupKey, ImmutableSet<ActionLookupKey>> reachableBadOwners =
      new HashMap<>();

  public IncrementalArtifactConflictFinder(
      MutableActionGraph threadSafeMutableActionGraph, WalkableGraph walkableGraph) {
    this.threadSafeMutableActionGraph = threadSafeMutableActionGraph;
    this.pathFragmentTrieRoot = new ConcurrentHashMap<>();
    this.walkableGraph = walkableGraph;
  }

  public int getOutputArtifactCount() {
    return threadSafeMutableActionGraph.getSize();
  }

  /**
   * Checks the actions in the transitive closure of a top level key for conflicts.
   *
   * <p>With Skymeld, conflict checking has to be done incrementally the moment each top level
   * target's analysis is finished. The checks of different top level keys are serialized and each
   * check consists of two steps:
   *
   * <ol>
   *   <li>The actions of all keys in the transitive closure that haven't been visited by a previous
   *       check are registered with the action graph. Since the checks are serialized, by the time
   *       a check returns, the actions of the entire transitive closure of its key have been
   *       registered and checked against each other as well as against the actions registered by
   *       previous checks. Any conflicts found are recorded globally, keyed by the owner of the
   *       conflicting action.
   *   <li>If any conflict has been found so far in this build, the transitive closure of the key
   *       is traversed again to collect the conflicting actions that it contains. This step is
   *       necessary since an action registered by a previous check may have been found to conflict
   *       only later and the result of the first step is thus not sufficient to decide whether
   *       the key is free of conflicts. Its cost is amortized across top level keys by memoizing
   *       the result for every visited key, which is only invalidated by a new conflict.
   * </ol>
   *
   * <p>Since each step is linear in the number of keys not yet visited by it, the total cost of
   * conflict checking is linear in the size of the analysis graph as long as the number of
   * distinct conflicts found is small.
   *
   * @return the conflicts found while registering the actions of {@code actionLookupKey}'s
   *     transitive closure as well as the conflicting actions contained in it, mapped to their
   *     exceptions. The key can only be executed if the map is empty. Both actions involved in a
   *     newly found conflict are included even if only one of them is contained in the transitive
   *     closure, so that the union of the results of all checks covers all conflicting actions.
   */
  ActionConflictsAndStats findArtifactConflicts(ActionLookupKey actionLookupKey)
      throws InterruptedException {
    lock.lockInterruptibly();
    try {
      Map<ActionAnalysisMetadata, ActionConflictException> conflicts = new HashMap<>();
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "Register actions")) {
        registerActionsInClosure(actionLookupKey, conflicts);
      }
      if (!conflicts.isEmpty()) {
        for (Map.Entry<ActionAnalysisMetadata, ActionConflictException> entry :
            conflicts.entrySet()) {
          badActionsByOwner
              .computeIfAbsent(getOwner(entry.getKey()), unused -> new HashMap<>())
              .put(entry.getKey(), entry.getValue());
        }
        reachableBadOwners.clear();
      }

      if (!badActionsByOwner.isEmpty()) {
        try (SilentCloseable c =
            Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "Find transitive conflicts")) {
          for (ActionLookupKey owner : findReachableBadOwners(actionLookupKey)) {
            conflicts.putAll(badActionsByOwner.get(owner));
          }
        }
      }
      return ActionConflictsAndStats.create(
          ImmutableMap.copyOf(conflicts), threadSafeMutableActionGraph.getSize());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Registers the actions of all keys in the transitive closure of {@code root} that haven't been
   * visited yet and collects the conflicts found while doing so.
   */
  @GuardedBy("lock")
  private void registerActionsInClosure(
      ActionLookupKey root, Map<ActionAnalysisMetadata, ActionConflictException> badActionMap)
      throws InterruptedException {
    if (!visited.add(root)) {
      return;
    }
    ArrayDeque<ActionLookupKey> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty()) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      ActionLookupKey key = queue.poll();
      SkyValue value = walkableGraph.getValue(key);
      if (value == null) { // The value failed to evaluate.
        continue;
      }
      for (SkyKey dep : walkableGraph.getDirectDeps(key)) {
        // The subgraph of dependencies of ActionLookupKeys never has a non-ActionLookupKey
        // depending on an ActionLookupKey. So we can skip any non-ActionLookupKeys in the
        // traversal as an optimization.
        if (dep instanceof ActionLookupKey depKey && visited.add(depKey)) {
          queue.add(depKey);
        }
      }
      // The value can be a non ActionLookupValue e.g. NonRuleConfiguredTargetValue.
      if (value instanceof ActionLookupValue alv) {
        actionRegistration(alv, threadSafeMutableActionGraph, pathFragmentTrieRoot, badActionMap);
      }
    }
  }

  /**
   * Returns the owners of conflicting actions in the transitive closure of {@code root}, memoizing
   * the result for every key visited along the way.
   */
  @GuardedBy("lock")
  private ImmutableSet<ActionLookupKey> findReachableBadOwners(ActionLookupKey root)
      throws InterruptedException {
    ImmutableSet<ActionLookupKey> memoized = reachableBadOwners.get(root);
    if (memoized != null) {
      return memoized;
    }
    // A post-order traversal with an explicit stack: the result for a key is computed once the
    // results for all of its dependencies are known.
    ArrayDeque<VisitState> stack = new ArrayDeque<>();
    stack.push(new VisitState(root, walkableGraph.getValue(root) == null));
    while (true) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      VisitState state = stack.peek();
      if (state.deps == null && !state.failed) {
        state.deps = walkableGraph.getDirectDeps(state.key).iterator();
      }
      ActionLookupKey unvisitedDep = null;
      while (state.deps != null && state.deps.hasNext()) {
        if (!(state.deps.next() instanceof ActionLookupKey depKey)) {
          continue;
        }
        ImmutableSet<ActionLookupKey> depResult = reachableBadOwners.get(depKey);
        if (depResult == null) {
          unvisitedDep = depKey;
          break;
        }
        state.accumulate(depResult);
      }
      if (unvisitedDep != null) {
        stack.push(new VisitState(unvisitedDep, walkableGraph.getValue(unvisitedDep) == null));
        continue;
      }
      if (badActionsByOwner.containsKey(state.key)) {
        state.accumulate(ImmutableSet.of(state.key));
      }
      ImmutableSet<ActionLookupKey> result = state.result;
      reachableBadOwners.put(state.key, result);
      stack.pop();
      if (stack.isEmpty()) {
        return result;
      }
      stack.peek().accumulate(result);
    }
  }

  /** The state of an in-progress visit of a key by {@link #findReachableBadOwners}. */
  private static final class VisitState {
    private final ActionLookupKey key;
    // Whether the key failed to evaluate, in which case it has neither deps nor actions.
    private final boolean failed;
    @Nullable private Iterator<SkyKey> deps = null;
    private ImmutableSet<ActionLookupKey> result = ImmutableSet.of();

    private VisitState(ActionLookupKey key, boolean failed) {
      this.key = key;
      this.failed = failed;
    }

    private void accumulate(ImmutableSet<ActionLookupKey> owners) {
      if (owners.isEmpty() || result.containsAll(owners)) {
        return;
      }
      if (result.isEmpty()) {
        // Share the set with the dependency it came from: most keys reach the same few owners.
        result = owners;
        return;
      }
      result = ImmutableSet.<ActionLookupKey>builder().addAll(result).addAll(owners).build();
    }
  }

  private static ActionLookupKey getOwner(ActionAnalysisMetadata action) {
    return ((DerivedArtifact) action.getPrimaryOutput()).getArtifactOwner();
  }

  private static void actionRegistration(
      ActionLookupValue alv,
      MutableActionGraph actionGraph,
      ConcurrentMap<String, Object> pathFragmentTrieRoot,
      Map<ActionAnalysisMetadata, ActionConflictException> badActionMap)
      throws InterruptedException {
    for (ActionAnalysisMetadata action : alv.getActions()) {
      try {
        actionGraph.registerAction(action);
      } catch (ActionConflictException e) {
        // It may be possible that we detect a conflict for the same action more than once, if
        // that action belongs to multiple aspect values. In this case we will harmlessly
        // overwrite the badActionMap entry.
        badActionMap.put(action, e);
        // We skip the rest of the loop, and do not add the path->artifact mapping for this
        // artifact below -- we don't need to check it since this action is already in
        // error.
        continue;
      }
      try {
        for (Artifact output : action.getOutputs()) {
          checkOutputPrefix(actionGraph, pathFragmentTrieRoot, output, badActionMap);
        }
      } catch (ActionConflictException e) {
        throw new IllegalStateException(
            "ActionConflictException aren't expected to be thrown here.", e);
      }
    }
  }

  public void conflictCheckPerAction(ActionAnalysisMetadata action)
      throws ActionConflictException, InterruptedException {
    threadSafeMutableActionGraph.registerAction(action);

    for (Artifact output : action.getOutputs()) {
      checkOutputPrefix(threadSafeMutableActionGraph, pathFragmentTrieRoot, output, null);
    }
  }

  /**
   * Fits the path segments into the existing trie.
   *
   * <p>A conceptual path segment TrieNode can be:
   *
   * <ul>
   *   <li>an Artifact if it's a leaf node, or
   *   <li>a {@code ConcurrentMap<String, Object>} if it's a non-leaf node. The mapping is from a
   *       path segment to another trie node.
   * </ul>
   *
   * <p>We do this instead of creating a proper wrapper TrieNode data structure to save memory, as
   * the trie is expected to get quite large.
   *
   * @throws ActionConflictException only when badActionMap is null.
   */
  private static void checkOutputPrefix(
      MutableActionGraph actionGraph,
      ConcurrentMap<String, Object> root,
      Artifact newArtifact,
      @Nullable Map<ActionAnalysisMetadata, ActionConflictException> badActionMap)
      throws ActionConflictException {
    Object existingTrieNode = root;
    PathFragment newArtifactPathFragment = newArtifact.getExecPath();
    Iterator<String> newPathIter = newArtifactPathFragment.segments().iterator();

    while (newPathIter.hasNext() && !(existingTrieNode instanceof Artifact)) {
      String newSegment = newPathIter.next();
      boolean isFinalSegmentOfNewPath = !newPathIter.hasNext();
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> existingNonLeafNode =
          (ConcurrentMap<String, Object>) existingTrieNode;

      // Look up the segment before attempting to insert it: the vast majority of segments are
      // already present and a plain get doesn't lock the map's bin.
      Object matchingChildNode = existingNonLeafNode.get(newSegment);
      if (matchingChildNode == null) {
        matchingChildNode =
            existingNonLeafNode.computeIfAbsent(
                newSegment,
                isFinalSegmentOfNewPath
                    ? unused -> newArtifact
                    : unused -> new ConcurrentHashMap<String, Object>());
      }

      // By the time we arrive in this method, we know for sure that there can't be any exact
      // matches in the paths since that would have been an ActionConflictException.
      boolean newPathIsPrefixOfExisting =
          !(matchingChildNode instanceof Artifact) && isFinalSegmentOfNewPath;
      boolean existingPathIsPrefixOfNew =
          matchingChildNode instanceof Artifact && !isFinalSegmentOfNewPath;

      if (existingPathIsPrefixOfNew || newPathIsPrefixOfExisting) {
        Artifact conflictingExistingArtifact = getOwningArtifactFromTrie(matchingChildNode);

        // If 2 paths collide, we need to update the Trie to contain only the shorter one.
        // This is required for correctness: the set of subsequent paths that could conflict with
        // the longer path is a subset of that of the shorter path.
        Artifact prefix;
        Artifact child;
        if (newPathIsPrefixOfExisting) {
          existingNonLeafNode.put(newSegment, newArtifact);
          prefix = newArtifact;
          child = conflictingExistingArtifact;
        } else {
          prefix = conflictingExistingArtifact;
          child = newArtifact;
        }

        if (!Actions.isRunfilesArtifactPair(prefix, child)) {
          ActionAnalysisMetadata priorAction =
              Preconditions.checkNotNull(
                  actionGraph.getGeneratingAction(conflictingExistingArtifact),
                  conflictingExistingArtifact);
          ActionAnalysisMetadata currentAction =
              Preconditions.checkNotNull(actionGraph.getGeneratingAction(newArtifact), newArtifact);
          ActionConflictException exception =
              ActionConflictException.createPrefix(
                  conflictingExistingArtifact, newArtifact, priorAction, currentAction);
          if (badActionMap == null) {
            throw exception;
          }

          badActionMap.put(priorAction, exception);
          badActionMap.put(currentAction, exception);

          break;
        }
      }
      existingTrieNode = matchingChildNode;
    }
  }

  // TODO(b/214389062) Fix the issue with SolibSymlinkAction before launch.
  private static Artifact getOwningArtifactFromTrie(Object trieNode) {
    Preconditions.checkArgument(
        trieNode instanceof Artifact || trieNode instanceof ConcurrentHashMap);
    if (trieNode instanceof Artifact artifact) {
      return artifact;
    }
    Object nodeIter = trieNode;
    while (!(nodeIter instanceof Artifact)) {
      // Just pick the first path available down the Trie.
      for (Object value : ((ConcurrentHashMap<?, ?>) nodeIter).values()) {
        nodeIter = value;
        break;
      }
    }
    return (Artifact) nodeIter;
  }
}
