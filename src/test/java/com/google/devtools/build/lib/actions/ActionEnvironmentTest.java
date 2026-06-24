// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionEnvironment.EnvVarConflictException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link ActionEnvironment}Test */
@RunWith(JUnit4.class)
public final class ActionEnvironmentTest {

  @Test
  public void compoundEnvOrdering() {
    ActionEnvironment env1 =
        ActionEnvironment.create(
            ImmutableMap.of("FOO", "foo1", "BAR", "bar"), ImmutableSet.of("baz"));
    // entries added by env2 override the existing entries
    ActionEnvironment env2 = env1.withAdditionalFixedVariables(ImmutableMap.of("FOO", "foo2"));

    assertThat(env1.getFixedEnv()).containsExactly("FOO", "foo1", "BAR", "bar");
    assertThat(env1.getInheritedEnv()).containsExactly("baz");

    assertThat(env2.getFixedEnv()).containsExactly("FOO", "foo2", "BAR", "bar");
    assertThat(env2.getInheritedEnv()).containsExactly("baz");
  }

  @Test
  public void fixedInheritedInteraction() {
    ActionEnvironment env =
        ActionEnvironment.create(
                ImmutableMap.of("FIXED_ONLY", "fixed"),
                ImmutableSet.of("INHERITED_ONLY", "FIXED_AND_INHERITED"))
            .withAdditionalFixedVariables(ImmutableMap.of("FIXED_AND_INHERITED", "fixed"));
    Map<String, String> clientEnv =
        ImmutableMap.of("INHERITED_ONLY", "inherited", "FIXED_AND_INHERITED", "inherited");
    Map<String, String> result = new HashMap<>();
    env.resolve(result, clientEnv);

    assertThat(result)
        .containsExactly(
            "FIXED_ONLY",
            "fixed",
            "FIXED_AND_INHERITED",
            "inherited",
            "INHERITED_ONLY",
            "inherited");
  }

  @Test
  public void emptyEnvironmentInterning() {
    ActionEnvironment emptyEnvironment =
        ActionEnvironment.create(ImmutableMap.of(), ImmutableSet.of());
    assertThat(emptyEnvironment).isSameInstanceAs(ActionEnvironment.EMPTY);

    ActionEnvironment base =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo1"), ImmutableSet.of("baz"));
    assertThat(base.withAdditionalFixedVariables(ImmutableMap.of())).isSameInstanceAs(base);
  }

  @Test
  public void mergeWith_emptyOther_returnsSameInstance() throws Exception {
    ActionEnvironment env =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo"), ImmutableSet.of("BAR"));
    assertThat(env.mergeWith(ActionEnvironment.EMPTY, "a", "b")).isSameInstanceAs(env);
  }

  @Test
  public void mergeWith_emptyThis_returnsOther() throws Exception {
    ActionEnvironment other =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo"), ImmutableSet.of("BAR"));
    assertThat(ActionEnvironment.EMPTY.mergeWith(other, "a", "b")).isSameInstanceAs(other);
  }

  @Test
  public void mergeWith_disjointVariables_takesUnion() throws Exception {
    ActionEnvironment env1 =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo"), ImmutableSet.of("INHERITED1"));
    ActionEnvironment env2 =
        ActionEnvironment.create(ImmutableMap.of("BAR", "bar"), ImmutableSet.of("INHERITED2"));

    ActionEnvironment merged = env1.mergeWith(env2, "env1", "env2");

    assertThat(merged.getFixedEnv()).containsExactly("FOO", "foo", "BAR", "bar");
    assertThat(merged.getInheritedEnv()).containsExactly("INHERITED1", "INHERITED2");
  }

  @Test
  public void mergeWith_agreeingVariables_succeeds() throws Exception {
    ActionEnvironment env1 =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo"), ImmutableSet.of("BAR"));
    ActionEnvironment env2 =
        ActionEnvironment.create(ImmutableMap.of("FOO", "foo"), ImmutableSet.of("BAR"));

    ActionEnvironment merged = env1.mergeWith(env2, "env1", "env2");

    assertThat(merged.getFixedEnv()).containsExactly("FOO", "foo");
    assertThat(merged.getInheritedEnv()).containsExactly("BAR");
  }

  @Test
  public void mergeWith_conflictingFixedValues_throws() {
    ActionEnvironment env1 = ActionEnvironment.create(ImmutableMap.of("FOO", "one"));
    ActionEnvironment env2 = ActionEnvironment.create(ImmutableMap.of("FOO", "two"));

    EnvVarConflictException e =
        assertThrows(
            EnvVarConflictException.class,
            () -> env1.mergeWith(env2, "the target", "the --run_under target"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "the target sets the environment variable 'FOO' to 'one', but the --run_under target"
                + " sets it to 'two'");
  }

  @Test
  public void mergeWith_fixedVersusInherited_throws() {
    ActionEnvironment env1 = ActionEnvironment.create(ImmutableMap.of("FOO", "one"));
    ActionEnvironment env2 = ActionEnvironment.create(ImmutableMap.of(), ImmutableSet.of("FOO"));

    EnvVarConflictException e =
        assertThrows(
            EnvVarConflictException.class,
            () -> env1.mergeWith(env2, "the target", "the --run_under target"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "the target sets the environment variable 'FOO' to the fixed value 'one', but the"
                + " --run_under target inherits it from the client environment");
  }
}
