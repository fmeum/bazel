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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link NestedSetFilter}. */
@RunWith(JUnit4.class)
public final class NestedSetFilterTest {

  private static final ImmutableList<String> ALPHABET =
      ImmutableList.of("a", "b", "c", "d", "e", "f", "g", "h");

  @Test
  public void filterAgreesWithFilteringTheFlattenedSet() throws Exception {
    for (Order order : Order.values()) {
      for (int seed = 0; seed < 200; seed++) {
        Random random = new Random(seed);
        List<NestedSet<String>> sets = randomDag(order, random);
        Set<String> kept = new HashSet<>();
        for (String element : ALPHABET) {
          if (random.nextBoolean()) {
            kept.add(element);
          }
        }
        // Filter the sets in construction order with a shared cache key, so that later sets reuse
        // the results for their successors.
        Object cacheKey = new Object();
        for (NestedSet<String> set : sets) {
          String context =
              String.format("order %s, seed %d, set %s, kept %s", order, seed, set, kept);
          ImmutableList<String> expected =
              set.toList().stream().filter(kept::contains).collect(toImmutableList());
          for (Object key : new Object[] {null, cacheKey}) {
            NestedSet<String> filtered = NestedSetFilter.filter(set, kept::contains, key);
            assertWithMessage(context)
                .that(filtered.toList())
                .containsExactlyElementsIn(expected)
                .inOrder();
            assertWithMessage(context).that(filtered.getOrder()).isEqualTo(order);
            checkStructure(context, filtered.children);
            // The depth may be overapproximated, but not beyond that of the original set.
            assertWithMessage(context)
                .that(filtered.getApproxDepth())
                .isIn(Range.closed(depth(filtered.children), set.getApproxDepth()));
          }
        }
      }
    }
  }

  /** Returns a random DAG of nested sets in construction order. */
  private static List<NestedSet<String>> randomDag(Order order, Random random) {
    List<NestedSet<String>> sets = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      NestedSetBuilder<String> builder = NestedSetBuilder.newBuilder(order);
      int numDirect = random.nextInt(4);
      for (int j = 0; j < numDirect; j++) {
        builder.add(ALPHABET.get(random.nextInt(ALPHABET.size())));
      }
      int numTransitive = sets.isEmpty() ? 0 : random.nextInt(4);
      for (int j = 0; j < numTransitive; j++) {
        builder.addTransitive(sets.get(random.nextInt(sets.size())));
      }
      sets.add(builder.build());
    }
    return sets;
  }

  /**
   * Checks the representation invariants of {@link NestedSet}: every array has at least two
   * members and the elements of an array are distinct.
   */
  private static void checkStructure(String context, Object children) {
    if (!(children instanceof Object[] array) || children == NestedSet.EMPTY_CHILDREN) {
      return;
    }
    assertWithMessage(context).that(array.length).isAtLeast(2);
    Set<Object> elements = new HashSet<>();
    for (Object member : array) {
      if (member instanceof Object[]) {
        checkStructure(context, member);
      } else {
        assertWithMessage(context).that(elements.add(member)).isTrue();
      }
    }
  }

  private static int depth(Object children) {
    if (children == NestedSet.EMPTY_CHILDREN) {
      return 0;
    }
    if (!(children instanceof Object[] array)) {
      return 1;
    }
    int maxMemberDepth = 0;
    for (Object member : array) {
      maxMemberDepth = Math.max(maxMemberDepth, depth(member));
    }
    return 1 + maxMemberDepth;
  }

  @Test
  public void unchangedSubgraphsAreShared() throws Exception {
    NestedSet<String> unaffected = NestedSetBuilder.<String>stableOrder().add("a").add("b").build();
    NestedSet<String> affected = NestedSetBuilder.<String>stableOrder().add("c").add("x").build();
    NestedSet<String> set =
        NestedSetBuilder.<String>stableOrder()
            .add("d")
            .addTransitive(unaffected)
            .addTransitive(affected)
            .build();

    NestedSet<String> filtered = NestedSetFilter.filter(set, e -> !e.equals("x"), null);

    assertThat(filtered.toList()).containsExactly("a", "b", "c", "d").inOrder();
    // The unaffected successor is reused as is, the affected one collapsed to its sole remaining
    // element.
    Object[] members = (Object[]) filtered.children;
    assertThat(members).hasLength(3);
    assertThat(members[0]).isSameInstanceAs(unaffected.children);
    assertThat(members[1]).isEqualTo("c");
    assertThat(members[2]).isEqualTo("d");
  }

  @Test
  public void returnsInputIfNothingIsRemoved() throws Exception {
    NestedSet<String> set =
        NestedSetBuilder.<String>stableOrder()
            .add("a")
            .addTransitive(NestedSetBuilder.<String>stableOrder().add("b").add("c").build())
            .build();
    assertThat(NestedSetFilter.filter(set, e -> true, null)).isSameInstanceAs(set);
  }

  @Test
  public void returnsCanonicalEmptySetIfEverythingIsRemoved() throws Exception {
    for (Order order : Order.values()) {
      NestedSet<String> set = NestedSetBuilder.<String>newBuilder(order).add("a").add("b").build();
      assertThat(NestedSetFilter.filter(set, e -> false, null))
          .isSameInstanceAs(order.emptySet());
      NestedSet<String> singleton = NestedSetBuilder.<String>newBuilder(order).add("a").build();
      assertThat(NestedSetFilter.filter(singleton, e -> false, null))
          .isSameInstanceAs(order.emptySet());
    }
  }

  @Test
  public void collapsedSuccessorDoesNotDuplicateElement() throws Exception {
    // A flat set is flattened without deduplication, so inlining the remains of a successor must
    // not introduce a duplicate.
    NestedSet<String> set =
        NestedSetBuilder.<String>stableOrder()
            .add("a")
            .addTransitive(NestedSetBuilder.<String>stableOrder().add("a").add("b").build())
            .build();

    NestedSet<String> filtered = NestedSetFilter.filter(set, e -> e.equals("a"), null);

    assertThat(filtered.isSingleton()).isTrue();
    assertThat(filtered.toList()).containsExactly("a");
    assertThat(filtered.getApproxDepth()).isEqualTo(1);
  }

  @Test
  public void predicateIsCalledOncePerDistinctElement() throws Exception {
    NestedSet<String> shared = NestedSetBuilder.<String>stableOrder().add("a").add("b").build();
    NestedSet<String> set =
        NestedSetBuilder.<String>stableOrder()
            .add("b")
            .add("c")
            .addTransitive(shared)
            .addTransitive(
                NestedSetBuilder.<String>stableOrder().add("a").addTransitive(shared).build())
            .build();
    List<String> calls = new ArrayList<>();

    NestedSet<String> filtered =
        NestedSetFilter.filter(
            set,
            e -> {
              calls.add(e);
              return !e.equals("b");
            },
            null);

    assertThat(calls).containsExactly("a", "b", "c");
    assertThat(filtered.toList()).containsExactly("a", "c").inOrder();
  }

  @Test
  public void resultsAreReusedAcrossCallsWithSameCacheKey() throws Exception {
    NestedSet<String> inner = NestedSetBuilder.<String>stableOrder().add("a").add("b").build();
    NestedSet<String> outer =
        NestedSetBuilder.<String>stableOrder().add("c").addTransitive(inner).build();
    List<String> calls = new ArrayList<>();
    NestedSetFilter.Predicate<String, RuntimeException> predicate =
        e -> {
          calls.add(e);
          return !e.equals("b");
        };
    Object cacheKey = new Object();

    assertThat(NestedSetFilter.filter(inner, predicate, cacheKey).toList()).containsExactly("a");
    assertThat(calls).containsExactly("a", "b").inOrder();

    calls.clear();
    assertThat(NestedSetFilter.filter(outer, predicate, cacheKey).toList())
        .containsExactly("a", "c")
        .inOrder();
    assertThat(calls).containsExactly("c");

    calls.clear();
    assertThat(NestedSetFilter.filter(outer, predicate, new Object()).toList())
        .containsExactly("a", "c")
        .inOrder();
    assertThat(calls).containsExactly("a", "b", "c").inOrder();

    calls.clear();
    assertThat(NestedSetFilter.filter(outer, predicate, null).toList())
        .containsExactly("a", "c")
        .inOrder();
    assertThat(calls).containsExactly("a", "b", "c").inOrder();

    NestedSetFilter.clearCache();
    calls.clear();
    assertThat(NestedSetFilter.filter(outer, predicate, cacheKey).toList())
        .containsExactly("a", "c")
        .inOrder();
    assertThat(calls).containsExactly("a", "b", "c").inOrder();
  }

  private static final class TestException extends Exception {}

  @Test
  public void propagatesExceptions() throws Exception {
    NestedSet<String> set =
        NestedSetBuilder.<String>stableOrder()
            .addAll(ImmutableSet.of("a", "b"))
            .addTransitive(NestedSetBuilder.<String>stableOrder().add("c").add("d").build())
            .build();
    assertThrows(
        TestException.class,
        () ->
            NestedSetFilter.filter(
                set,
                e -> {
                  if (e.equals("d")) {
                    throw new TestException();
                  }
                  return true;
                },
                new Object()));
  }
}
