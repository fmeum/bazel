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

package net.starlark.java.eval;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.starlark.java.syntax.Statement;

/**
 * The strategy used to execute the body of a {@link StarlarkFunction}: the single seam between a
 * function call and the engine that runs its statements.
 *
 * <p>It exists so that an alternative execution engine (for example a bytecode or Truffle-based
 * compiler) can be slotted in behind {@link StarlarkThread} without disturbing callers, the runtime
 * value model, or the {@code Starlark.*call*} entry points. The default, {@link #TREE_WALKING}, is
 * the historical recursive {@link Eval tree-walking evaluator}.
 *
 * <p>Any engine installed via {@link StarlarkThread#setEngine} must preserve the observable behavior
 * of {@link #TREE_WALKING}: identical results, side effects, and thrown {@link EvalException}s. The
 * {@code StarlarkEngineConsistencyTest} differential harness exists to enforce this as engines are
 * ported node type by node type.
 *
 * <p>This interface is intentionally package-private for now: the abstraction is internal until a
 * second engine and a way to select it (e.g. an {@code --experimental_starlark_engine} flag) are
 * designed.
 */
@FunctionalInterface
interface StarlarkEngine {

  /**
   * Executes the body of the {@link StarlarkFunction} whose active call frame is {@code fr} and
   * returns the function's result.
   *
   * @param fr the active frame; {@code fr.locals} have already been populated with the bound
   *     arguments, and {@code fr.fn} is the {@link StarlarkFunction} being called
   * @param statements the resolved body of {@code fr.fn} (i.e. {@code fn.rfn.getBody()})
   */
  Object execFunctionBody(StarlarkThread.Frame fr, List<Statement> statements)
      throws EvalException, InterruptedException;

  /** The default engine: Bazel's recursive tree-walking evaluator ({@link Eval}). */
  StarlarkEngine TREE_WALKING = Eval::execFunctionBody;

  // ---- name-based registry, used to resolve --experimental_starlark_engine ----

  /** Engines registered by name. The default (tree-walking) is resolved without a lookup. */
  ConcurrentHashMap<String, StarlarkEngine> REGISTRY = new ConcurrentHashMap<>();

  /**
   * Maps engine names that are not built in to the class expected to register them. Loading that
   * class (which lives in a separate, optional build target) triggers its static registration.
   */
  Map<String, String> LAZY_PROVIDERS =
      ImmutableMap.of("truffle", "net.starlark.java.eval.TruffleStarlarkEngine");

  /** Registers {@code engine} under {@code name}; typically called from an engine's static init. */
  static void register(String name, StarlarkEngine engine) {
    REGISTRY.put(name, engine);
  }

  /**
   * Resolves an engine name (as supplied by {@link StarlarkSemantics#EXPERIMENTAL_STARLARK_ENGINE})
   * to an engine. The empty string and {@code "tree-walking"} resolve to {@link #TREE_WALKING}.
   * Other names are looked up in the {@link #REGISTRY}, lazily loading a known provider class if
   * needed.
   *
   * @throws IllegalArgumentException if the name is non-empty and no such engine is available
   */
  static StarlarkEngine forName(String name) {
    if (name.isEmpty() || name.equals("tree-walking")) {
      return TREE_WALKING;
    }
    StarlarkEngine engine = REGISTRY.get(name);
    if (engine == null) {
      String providerClass = LAZY_PROVIDERS.get(name);
      if (providerClass != null) {
        try {
          // Loading the class runs its static initializer, which calls register(name, ...).
          Class.forName(providerClass);
        } catch (ClassNotFoundException e) {
          throw new IllegalArgumentException(
              "Starlark engine '"
                  + name
                  + "' is not available; its provider "
                  + providerClass
                  + " is not on the classpath",
              e);
        }
      }
      engine = REGISTRY.get(name);
    }
    if (engine == null) {
      throw new IllegalArgumentException("unknown Starlark engine: '" + name + "'");
    }
    return engine;
  }
}
