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
 * memoized across calls until {@link #clearCache} is called or the node or the key becomes
 * unreachable. Filtering a set that extends a previously filtered one then only visits the new
 * nodes, which turns a chain of filters over growing transitive sets from quadratic into linear
 * total work.
 */
public final class NestedSetFilter {

  /** A predicate on the elements of a nested set that may fail. */
  @FunctionalInterface
  interface Predicate<E, X extends Exception> {
    boolean test(E element) throws X, InterruptedException;
  }

  /**
   * The result of filtering a children array that loses no elements. Using a sentinel rather than
   * the array itself avoids a strong reference from a cache value to its weak key.
   */
  private static final Object UNCHANGED = new Object();

  /**
   * For each cache key, maps a children array (by identity) to the result of filtering it: either
   * {@link #UNCHANGED} or the filtered children ({@link NestedSet#EMPTY_CHILDREN}, a single element
   * or an array).
   *
   * <p>Both levels hold their keys weakly: An entry is dropped once the children array is no longer
   * part of any live nested set or once the cache key becomes unreachable. Values never reference
   * their own key, only (filtered versions of) its successors, so they don't keep it alive.
   */
  private static final Cache<Object, ConcurrentMap<Object[], Object>> cache =
      Caffeine.newBuilder().weakKeys().build();

  private NestedSetFilter() {}

  /**
   * Drops all memoized results. This trades CPU for memory and never affects the results of {@link
   * #filter}.
   */
  public static void clearCache() {
    cache.invalidateAll();
  }

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

    Map<Object[], Object> nodes =
        cacheKey == null
            ? new IdentityHashMap<>()
            : cache.get(cacheKey, unused -> new MapMaker().weakKeys().makeMap());
    Object filtered = new Walker<E, X>(predicate, nodes).filter(array);
    if (filtered == UNCHANGED) {
      return set;
    }
    if (filtered == EMPTY_CHILDREN) {
      return set.getOrder().emptySet();
    }
    // Filtering never increases the depth, so the depth of the original set is a valid
    // overapproximation (see NestedSet#getApproxDepth). Tracking the exact depth would cost memory
    // for every cache entry.
    return NestedSet.create(
        set.getOrder(), filtered instanceof Object[] ? set.getApproxDepth() : 1, filtered);
  }

  private static final class Walker<E, X extends Exception> {
    private final Predicate<? super E, X> predicate;
    private final Map<Object[], Object> nodes;
    // Keyed by equality rather than identity since NestedSet deduplicates elements by equality, so
    // equal elements must receive the same verdict.
    private final Map<Object, Boolean> elements = new HashMap<>();

    Walker(Predicate<? super E, X> predicate, Map<Object[], Object> nodes) {
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

    /** Returns {@link #UNCHANGED} or the filtered children of {@code array}. */
    private Object filter(Object[] array) throws X, InterruptedException {
      Object cached = nodes.get(array);
      if (cached != null) {
        return cached;
      }

      // A copy of array that is only allocated once the first member changes. Until then, the
      // first n members of the result agree with those of array.
      Object[] members = null;
      int n = 0;
      // Whether a non-leaf member was replaced by a single element or a different array, which may
      // duplicate another member.
      boolean replacedNonLeaf = false;
      for (Object member : array) {
        Object newMember;
        if (member instanceof Object[] subarray) {
          Object filtered = filter(subarray);
          if (filtered == UNCHANGED) {
            newMember = subarray;
          } else {
            newMember = filtered;
            replacedNonLeaf = true;
          }
        } else {
          newMember = test(member) ? member : EMPTY_CHILDREN;
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
      }

      Object result;
      if (members == null) {
        result = UNCHANGED;
      } else {
        if (replacedNonLeaf) {
          n = removeDuplicates(members, n);
        }
        result =
            switch (n) {
              case 0 -> EMPTY_CHILDREN;
              // Like NestedSet#create, a node with a single successor is replaced by it.
              case 1 -> members[0];
              default ->
                  NestedSetInterner.intern(
                      n == members.length ? members : Arrays.copyOf(members, n));
            };
      }
      // Under concurrent filtering with the same cache key, make all callers agree on one result so
      // that the filtered sets share structure.
      Object previous = nodes.putIfAbsent(array, result);
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
