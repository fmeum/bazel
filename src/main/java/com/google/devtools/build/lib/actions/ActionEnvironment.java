// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Environment variables for build or test actions.
 *
 * <p>The action environment consists of two parts.
 *
 * <ol>
 *   <li>All the environment variables with a fixed value, stored in a map.
 *   <li>All the environment variables inherited from the client environment, stored in a set.
 * </ol>
 *
 * <p>Inherited environment variables must be declared in the Action interface (see {@link
 * Action#getClientEnvironmentVariables}), so that the dependency on the client environment is known
 * to the execution framework for correct incremental builds.
 *
 * <p>By splitting the environment, we can handle environment variable changes more efficiently -
 * the dependency of the action on the environment variable are tracked in Skyframe (and in the
 * action cache), such that Bazel knows exactly which actions it needs to rerun, and does not have
 * to reanalyze the entire dependency graph.
 */
public abstract class ActionEnvironment {

  public static final ActionEnvironment EMPTY = new EmptyActionEnvironment();

  private static final Interner<ActionEnvironment> actionEnvironmentInterner =
      BlazeInterners.newWeakInterner();

  /** Convenience method for creating an {@link ActionEnvironment} with no inherited variables. */
  public static ActionEnvironment create(ImmutableMap<String, String> fixedEnv) {
    return create(fixedEnv, /* inheritedEnv= */ ImmutableSet.of());
  }

  /**
   * Creates a new {@link ActionEnvironment}.
   *
   * <p>If an environment variable is contained both as a key in {@code fixedEnv} and in {@code
   * inheritedEnv}, the result of {@link #resolve} will contain the value inherited from the client
   * environment.
   */
  public static ActionEnvironment create(
      ImmutableMap<String, String> fixedEnv, ImmutableSet<String> inheritedEnv) {
    if (fixedEnv.isEmpty() && inheritedEnv.isEmpty()) {
      return EMPTY;
    }
    return actionEnvironmentInterner.intern(new SimpleActionEnvironment(fixedEnv, inheritedEnv));
  }

  /**
   * Splits the given map into a map of variables with a fixed value, and a set of variables that
   * should be inherited, the latter of which are identified by having a {@code null} value in the
   * given map. Returns these two parts as a new {@link ActionEnvironment} instance.
   */
  public static ActionEnvironment split(Map<String, String> env) {
    Map<String, String> fixedEnv = new TreeMap<>();
    Set<String> inheritedEnv = new TreeSet<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      if (entry.getValue() != null) {
        fixedEnv.put(entry.getKey(), entry.getValue());
      } else {
        inheritedEnv.add(entry.getKey());
      }
    }
    return create(ImmutableMap.copyOf(fixedEnv), ImmutableSet.copyOf(inheritedEnv));
  }

  private ActionEnvironment() {}

  /**
   * Returns the 'fixed' part of the environment, i.e., those environment variables that are set to
   * fixed values and their values. This should only be used for testing and to compute the cache
   * keys of actions. Use {@link #resolve} instead to get the complete environment.
   */
  public abstract ImmutableMap<String, String> getFixedEnv();

  /**
   * Returns the 'inherited' part of the environment, i.e., those environment variables that are
   * inherited from the client environment and therefore have no fixed value here. This should only
   * be used for testing and to compute the cache keys of actions. Use {@link #resolve} instead to
   * get the complete environment.
   */
  public abstract ImmutableSet<String> getInheritedEnv();

  /**
   * Returns an upper bound on the combined size of the fixed and inherited environments. A call to
   * {@link #resolve} may add fewer entries than this number if environment variables are contained
   * in both the fixed and the inherited environment.
   */
  public abstract int estimatedSize();

  /**
   * Resolves the action environment and adds the resulting entries to the given {@code result} map,
   * by looking up any inherited env variables in the given {@code clientEnv}.
   *
   * <p>We pass in a map to mutate to avoid creating and merging intermediate maps.
   */
  public final void resolve(Map<String, String> result, Map<String, String> clientEnv) {
    checkNotNull(clientEnv);
    result.putAll(getFixedEnv());
    for (String var : getInheritedEnv()) {
      String value = clientEnv.get(var);
      if (value != null) {
        result.put(var, value);
      }
    }
  }

  public final void addTo(Fingerprint f) {
    f.addStringMap(getFixedEnv());
    f.addStrings(getInheritedEnv());
  }

  /**
   * Returns a copy of the environment with the given fixed variables added to it, <em>overwriting
   * any existing occurrences of those variables</em>.
   */
  public final ActionEnvironment withAdditionalFixedVariables(Map<String, String> fixedVars) {
    if (fixedVars.isEmpty()) {
      return this;
    }
    if (this == EMPTY) {
      return actionEnvironmentInterner.intern(
          new SimpleActionEnvironment(ImmutableMap.copyOf(fixedVars), ImmutableSet.of()));
    }
    return actionEnvironmentInterner.intern(
        new CompoundActionEnvironment(this, ImmutableMap.copyOf(fixedVars)));
  }

  /**
   * Returns the union of this environment and {@code other}, requiring that the two environments
   * assign a consistent value to every variable they have in common.
   *
   * <p>Two environments are consistent for a given variable if they either both inherit it from the
   * client environment or both set it to the same fixed value. Setting a variable to differing
   * fixed values, or setting it to a fixed value in one environment while inheriting it in the
   * other, is a conflict that results in an {@link EnvVarConflictException}: such variables would
   * otherwise silently resolve to a single value even though the two environments disagree on what
   * it should be.
   *
   * @param thisDescription a human-readable description of the origin of this environment, used in
   *     the conflict message (e.g. {@code "the target //foo:bar"})
   * @param otherDescription a human-readable description of the origin of {@code other}
   */
  public final ActionEnvironment mergeWith(
      ActionEnvironment other, String thisDescription, String otherDescription)
      throws EnvVarConflictException {
    if (other.estimatedSize() == 0) {
      return this;
    }
    if (estimatedSize() == 0) {
      return other;
    }

    ImmutableMap<String, String> thisFixed = getFixedEnv();
    ImmutableSet<String> thisInherited = getInheritedEnv();
    ImmutableMap<String, String> otherFixed = other.getFixedEnv();
    ImmutableSet<String> otherInherited = other.getInheritedEnv();

    for (String name : Sets.union(thisFixed.keySet(), thisInherited)) {
      if (!otherFixed.containsKey(name) && !otherInherited.contains(name)) {
        continue;
      }
      // A variable that appears in the inherited set is treated as inherited even if it also has a
      // fixed value, since the inherited value takes precedence in resolve().
      boolean thisIsInherited = thisInherited.contains(name);
      boolean otherIsInherited = otherInherited.contains(name);
      if (thisIsInherited && otherIsInherited) {
        // Both inherit the same value from the client environment.
        continue;
      }
      if (!thisIsInherited && !otherIsInherited) {
        if (thisFixed.get(name).equals(otherFixed.get(name))) {
          // Both set the same fixed value.
          continue;
        }
        throw new EnvVarConflictException(
            String.format(
                "%s sets the environment variable '%s' to '%s', but %s sets it to '%s'",
                thisDescription,
                name,
                thisFixed.get(name),
                otherDescription,
                otherFixed.get(name)));
      }
      String fixedDescription = thisIsInherited ? otherDescription : thisDescription;
      String fixedValue = thisIsInherited ? otherFixed.get(name) : thisFixed.get(name);
      String inheritedDescription = thisIsInherited ? thisDescription : otherDescription;
      throw new EnvVarConflictException(
          String.format(
              "%s sets the environment variable '%s' to the fixed value '%s', but %s inherits it"
                  + " from the client environment",
              fixedDescription, name, fixedValue, inheritedDescription));
    }

    ImmutableMap<String, String> mergedFixed =
        ImmutableMap.<String, String>builder()
            .putAll(thisFixed)
            .putAll(otherFixed)
            .buildKeepingLast();
    ImmutableSet<String> mergedInherited =
        ImmutableSet.<String>builder().addAll(thisInherited).addAll(otherInherited).build();
    return create(mergedFixed, mergedInherited);
  }

  /**
   * Thrown by {@link #mergeWith} when two environments assign conflicting values to the same
   * environment variable.
   */
  public static final class EnvVarConflictException extends Exception {
    EnvVarConflictException(String message) {
      super(message);
    }
  }

  private static final class EmptyActionEnvironment extends ActionEnvironment {

    @Override
    public ImmutableMap<String, String> getFixedEnv() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableSet<String> getInheritedEnv() {
      return ImmutableSet.of();
    }

    @Override
    public int estimatedSize() {
      return 0;
    }
  }

  private static final class SimpleActionEnvironment extends ActionEnvironment {
    private final ImmutableMap<String, String> fixedEnv;
    private final ImmutableSet<String> inheritedEnv;

    SimpleActionEnvironment(
        ImmutableMap<String, String> fixedEnv, ImmutableSet<String> inheritedEnv) {
      this.fixedEnv = fixedEnv;
      this.inheritedEnv = inheritedEnv;
    }

    @Override
    public ImmutableMap<String, String> getFixedEnv() {
      return fixedEnv;
    }

    @Override
    public ImmutableSet<String> getInheritedEnv() {
      return inheritedEnv;
    }

    @Override
    public int estimatedSize() {
      return fixedEnv.size() + inheritedEnv.size();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SimpleActionEnvironment that)) {
        return false;
      }
      return fixedEnv.equals(that.fixedEnv) && inheritedEnv.equals(that.inheritedEnv);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fixedEnv, inheritedEnv);
    }
  }

  private static final class CompoundActionEnvironment extends ActionEnvironment {
    private final ActionEnvironment base;
    private final ImmutableMap<String, String> fixedVars;

    private CompoundActionEnvironment(
        ActionEnvironment base, ImmutableMap<String, String> fixedVars) {
      this.base = base;
      this.fixedVars = fixedVars;
    }

    @Override
    public ImmutableMap<String, String> getFixedEnv() {
      return ImmutableMap.<String, String>builder()
          .putAll(base.getFixedEnv())
          .putAll(fixedVars)
          .buildKeepingLast();
    }

    @Override
    public ImmutableSet<String> getInheritedEnv() {
      return base.getInheritedEnv();
    }

    @Override
    public int estimatedSize() {
      return base.estimatedSize() + fixedVars.size();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CompoundActionEnvironment that)) {
        return false;
      }
      return base.equals(that.base) && fixedVars.equals(that.fixedVars);
    }

    @Override
    public int hashCode() {
      return Objects.hash(base, fixedVars);
    }
  }
}
