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
package com.google.devtools.build.lib.collect.nestedset;

import static com.google.devtools.build.lib.collect.nestedset.NestedSet.EMPTY_CHILDREN;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.MapMaker;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Computes the subset of a {@link NestedSet} whose elements satisfy a predicate without flattening
 * it.
 *
 * <p>The filter is applied node by node: every distinct children array is visited once and the
 * predicate is evaluated once per distinct element. Subgraphs that lose no elements are shared with
 * the input, so memory is only allocated for nodes that change. The traversal order of the result
 * is that of the input with the rejected elements removed.
 *
 * <p>If the caller provides a cache key, the filtered version of every node is additionally
 * memoized across calls for as long as both the node and the key are reachable. Filtering a set
 * that extends a previously filtered one then only visits the new nodes, which turns a chain of
 * filters over growing transitive sets from quadratic into linear total work.
 */
final class NestedSetFilter {

  /** A predicate on the elements of a nested set that may fail. */
  @FunctionalInterface
  interface Predicate<E, X extends Exception> {
    boolean test(E element) throws X, InterruptedException;
  }

  /**
   * The result of filtering a children array.
   *
   * @param children the filtered children ({@link NestedSet#EMPTY_CHILDREN}, a single element or an
   *     array), or null if they are identical to the original array. Representing the latter case
   *     by null avoids a strong reference from a cache value to its weak key.
   * @param depth the depth of the filtered children
   */
  private record Filtered(@Nullable Object children, int depth) {}

  /**
   * For each cache key, maps a children array (by identity) to the result of filtering it.
   *
   * <p>Both levels hold their keys weakly: An entry is dropped once the children array is no longer
   * part of any live nested set or once the cache key becomes unreachable. Values never reference
   * their own key, only (filtered versions of) its successors, so they don't keep it alive.
   */
  private static final Cache<Object, ConcurrentMap<Object[], Filtered>> cache =
      Caffeine.newBuilder().weakKeys().build();

  private NestedSetFilter() {}

  /**
   * Returns the subset of {@code set} whose elements satisfy {@code predicate}, preserving order.
   * Returns {@code set} itself if no element is rejected.
   *
   * @param cacheKey if non-null, results for individual nodes are memoized across calls with the
   *     same key (compared by identity). The caller must ensure that for a given key, the predicate
   *     always returns the same value for equal elements.
   */
  static <E, X extends Exception> NestedSet<E> filter(
      NestedSet<E> set, Predicate<? super E, X> predicate, @Nullable Object cacheKey)
      throws X, InterruptedException {
    Object children = set.getChildrenInterruptibly();
    if (children == EMPTY_CHILDREN) {
      return set;
    }
    if (!(children instanceof Object[] array)) {
      @SuppressWarnings("unchecked")
      E element = (E) children;
      return predicate.test(element) ? set : set.getOrder().emptySet();
    }

    Map<Object[], Filtered> nodes =
        cacheKey == null
            ? new IdentityHashMap<>()
            : cache.get(cacheKey, unused -> new MapMaker().weakKeys().makeMap());
    Filtered filtered = new Walker<E, X>(predicate, nodes).filter(array);
    if (filtered.children() == null) {
      return set;
    }
    if (filtered.children() == EMPTY_CHILDREN) {
      return set.getOrder().emptySet();
    }
    return NestedSet.create(set.getOrder(), filtered.depth(), filtered.children());
  }

  private static final class Walker<E, X extends Exception> {
    private final Predicate<? super E, X> predicate;
    private final Map<Object[], Filtered> nodes;
    // Keyed by equality rather than identity since NestedSet deduplicates elements by equality, so
    // equal elements must receive the same verdict.
    private final Map<Object, Boolean> elements = new HashMap<>();

    Walker(Predicate<? super E, X> predicate, Map<Object[], Filtered> nodes) {
      this.predicate = predicate;
      this.nodes = nodes;
    }

    @SuppressWarnings("unchecked")
    private boolean test(Object element) throws X, InterruptedException {
      Boolean keep = elements.get(element);
      if (keep == null) {
        keep = predicate.test((E) element);
        elements.put(element, keep);
      }
      return keep;
    }

    private Filtered filter(Object[] array) throws X, InterruptedException {
      Filtered cached = nodes.get(array);
      if (cached != null) {
        return cached;
      }

      // A copy of array that is only allocated once the first member changes. Until then, the
      // first n members of the result agree with those of array.
      Object[] members = null;
      int n = 0;
      int maxMemberDepth = 0;
      // Whether a non-leaf member was replaced by a single element or a different array, which may
      // duplicate another member.
      boolean replacedNonLeaf = false;
      for (Object member : array) {
        Object newMember;
        int memberDepth;
        if (member instanceof Object[] subarray) {
          Filtered filtered = filter(subarray);
          if (filtered.children() == null) {
            newMember = subarray;
          } else {
            newMember = filtered.children();
            replacedNonLeaf = true;
          }
          memberDepth = filtered.depth();
        } else if (test(member)) {
          newMember = member;
          memberDepth = 1;
        } else {
          newMember = EMPTY_CHILDREN;
          memberDepth = 0;
        }

        if (members == null && newMember != member) {
          members = Arrays.copyOf(array, array.length);
        }
        if (newMember == EMPTY_CHILDREN) {
          continue;
        }
        if (members != null) {
          members[n] = newMember;
        }
        n++;
        maxMemberDepth = Math.max(maxMemberDepth, memberDepth);
      }

      Filtered result;
      if (members == null) {
        result = new Filtered(null, 1 + maxMemberDepth);
      } else {
        if (replacedNonLeaf) {
          n = removeDuplicates(members, n);
        }
        result =
            switch (n) {
              case 0 -> new Filtered(EMPTY_CHILDREN, 0);
              // Like NestedSet#create, a node with a single successor is replaced by it. Since
              // removeDuplicates only removes members equal to a remaining one, maxMemberDepth is
              // still the depth of the sole member.
              case 1 -> new Filtered(members[0], maxMemberDepth);
              default ->
                  new Filtered(
                      NestedSetInterner.intern(
                          n == members.length ? members : Arrays.copyOf(members, n)),
                      1 + maxMemberDepth);
            };
      }
      // Under concurrent filtering with the same cache key, make all callers agree on one result so
      // that the filtered sets share structure.
      Filtered previous = nodes.putIfAbsent(array, result);
      return previous != null ? previous : result;
    }

    /**
     * Removes all but the first occurrence of each member of {@code members[0..n)} in place and
     * returns the new length.
     *
     * <p>This is required since {@link NestedSet} does not deduplicate the elements of a flat set
     * when flattening it. As in a traversal of the set, the first occurrence wins, so the order of
     * the flattened set is unaffected. Non-leaf members are arrays and are thus compared by
     * identity.
     */
    private static int removeDuplicates(Object[] members, int n) {
      Set<Object> seen = CompactHashSet.createWithExpectedSize(n);
      int newN = 0;
      for (int i = 0; i < n; i++) {
        if (seen.add(members[i])) {
          members[newN++] = members[i];
        }
      }
      return newN;
    }
  }
}
