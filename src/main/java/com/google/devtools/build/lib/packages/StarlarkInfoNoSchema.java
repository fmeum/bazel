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

package com.google.devtools.build.lib.packages;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Compactable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.TokenKind;

/**
 * A struct-like Info (provider instance) for providers defined in Starlark that don't have a
 * schema.
 */
public class StarlarkInfoNoSchema extends StarlarkInfo {
  private final Provider provider;

  // For a n-element info, the table contains n key strings, sorted,
  // followed by the n corresponding legal Starlark values.
  // For an Optimized info, the table only contains the values.
  private final Object[] table;

  // TODO(adonovan): restrict type of provider to StarlarkProvider?
  // Do we ever need StarlarkInfos of BuiltinProviders? Such BuiltinProviders could
  // be  moved to Starlark using bzl builtins injection.
  // Alternatively: what about this implementation is specific to StarlarkProvider?
  // It's really just a "generic" or "dynamic" representation of a struct,
  // analogous to reflection versus generated message classes in the protobuf world.
  // The efficient table algorithms would be a nice addition to the Starlark
  // interpreter, to allow other clients to define their own fast structs
  // (or to define a standard one). See also comments at Info about upcoming clean-ups.
  private StarlarkInfoNoSchema(Provider provider, Object[] table) {
    this.provider = provider;
    this.table = table;
  }

  StarlarkInfoNoSchema(Provider provider, Map<String, Object> values) {
    this.provider = provider;
    this.table = toTable(values);
  }

  @Override
  public Provider getProvider() {
    return provider;
  }

  /** Returns the number of fields. */
  private int size() {
    return this instanceof Optimized optimized
        ? optimized.fieldNames.names.length
        : table.length / 2;
  }

  /** Returns an array whose first {@link #size} elements are the sorted keys. */
  private Object[] keys() {
    return this instanceof Optimized optimized ? optimized.fieldNames.names : table;
  }

  /**
   * Creates a schemaless provider instance with the given provider type and field values.
   *
   * @param provider A {@code Provider} without a schema. {@code StarlarkProvider} with a schema is
   *     not supported by this call.
   * @param values the field values
   */
  static StarlarkInfo createSchemaless(Provider provider, Map<String, Object> values) {
    Preconditions.checkArgument(
        !(provider instanceof StarlarkProvider)
            || ((StarlarkProvider) provider).getFields() == null);
    return new StarlarkInfoNoSchema(provider, values);
  }

  // Converts a map to a table of sorted keys followed by corresponding values.
  private static Object[] toTable(Map<String, Object> values) {
    int n = values.size();
    Object[] table = new Object[n + n];
    int i = 0;
    // TODO(b/380824219): Once fastcall and thus createFromNamedArgs is removed, consider whether
    // we can wrap values.entrySet() in a SortedSet and avoid and remove sortPairs().
    // Maybe an overloaded constructor StarlarkInfoNoSchema(Provider, SortedMap<>, Location)
    // could also be useful in this context. Connection with b/380824219: StarlarkInfoFactory
    // assembles values into a TreeMap and calls StarlarkInfoNoSchema(Provider, Map<>, Location).
    for (Map.Entry<String, Object> e : values.entrySet()) {
      table[i] = e.getKey();
      table[n + i] = Starlark.checkValid(e.getValue());
      i++;
    }
    // Sort keys, permuting values in parallel.
    if (n > 1) {
      sortPairs(table, 0, n - 1);
    }
    return table;
  }

  static StarlarkProvider.StarlarkInfoFactory newStarlarkInfoFactory(
      StarlarkProvider provider, StarlarkThread thread) {
    return new StarlarkInfoFactory(provider, thread);
  }

  /**
   * Constructs a StarlarkInfo with calls forwarded from one of the StarlarkInfo ArgumentProcessor
   * implementations. Checks that each key is provided at most once. This class exists solely for
   * the StarlarkInfo ArgumentProcessors.
   */
  static class StarlarkInfoFactory extends StarlarkProvider.StarlarkInfoFactory {
    private final Map<String, Object> namedArgMap;

    StarlarkInfoFactory(StarlarkProvider provider, StarlarkThread thread) {
      super(provider, thread);
      this.namedArgMap = new HashMap<>();
    }

    @Override
    public void addNamedArg(String name, Object value) throws EvalException {
      // TODO(b/380824219): Evaluate whether we can know the number of named args here, and then
      // place the args into the table directly.
      Object oldValue = namedArgMap.put(name, value);
      if (oldValue != null) {
        throw Starlark.errorf(
            "got multiple values for parameter %s in call to instantiate provider %s",
            name, provider.getPrintableName());
      }
    }

    @Override
    public StarlarkInfo createFromArgs() {
      return new StarlarkInfoNoSchema(provider, namedArgMap);
    }

    @Override
    public StarlarkInfo createFromMap(Map<String, Object> map) {
      return new StarlarkInfoNoSchema(provider, map);
    }
  }

  // Sorts non-empty slice a[lo:hi] (inclusive) in place.
  // Elements a[n:2n) are permuted the same way as a[0:n),
  // where n = a.length / 2. The lower half must be strings.
  // Precondition: 0 <= lo <= hi < n.
  static void sortPairs(Object[] a, int lo, int hi) {
    String pivot = (String) a[lo + (hi - lo) / 2];

    int i = lo;
    int j = hi;
    while (i <= j) {
      while (((String) a[i]).compareTo(pivot) < 0) {
        i++;
      }
      while (((String) a[j]).compareTo(pivot) > 0) {
        j--;
      }
      if (i <= j) {
        int n = a.length >> 1;
        swap(a, i, j);
        swap(a, i + n, j + n);
        i++;
        j--;
      }
    }
    if (lo < j) {
      sortPairs(a, lo, j);
    }
    if (i < hi) {
      sortPairs(a, i, hi);
    }
  }

  private static void swap(Object[] a, int i, int j) {
    Object tmp = a[i];
    a[i] = a[j];
    a[j] = tmp;
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    // TODO(adonovan): opt: can we avoid allocating three objects?
    @SuppressWarnings("unchecked")
    List<String> keys = (List<String>) (List<?>) Arrays.asList(keys()).subList(0, size());
    return ImmutableList.copyOf(keys);
  }

  @Override
  public boolean isImmutable() {
    // If the provider is not yet exported, the hash code of the object is subject to change.
    if (!provider.isExported()) {
      return false;
    }
    for (int i = table.length - size(); i < table.length; i++) {
      if (!Starlark.isImmutable(table[i])) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void checkHashable() throws EvalException {
    super.checkHashable(); // Verifies that the values are immutable.
    // Bazel has historically allowed structs of immutable values to be considered hashable even if
    // those values are not Starlark-hashable by themselves (e.g. frozen lists). This is
    // inconsistent and arguably wrong, but fixing it would be a breaking change.
    // Thus, instead of checking whether the values are Starlark-hashable, below we only check
    // whether they have a usable hashCode() implementation.
    for (int i = table.length - size(); i < table.length; i++) {
      Object val = table[i];
      if (!Starlark.isAcyclic(val)) {
        // A self-referential value's hashCode() can cause a stack overflow. Trigger it early; the
        // StackOverflowError will be caught by Starlark.checkHashable() and rethrown as an
        // EvalException.
        var unused = val.hashCode();
      }
    }
  }

  @Nullable
  @Override
  public Object getValue(String name) {
    int n = size();
    Object[] keys = keys();
    int i;
    if (n <= BINARY_SEARCH_THRESHOLD) {
      i = -1;
      for (int j = 0; j < n; j++) {
        if (keys[j].equals(name)) {
          i = j;
          break;
        }
      }
    } else {
      i = Arrays.binarySearch(keys, 0, n, name);
    }
    if (i < 0) {
      return null;
    }
    return table[table.length - n + i];
  }

  @Nullable
  @Override
  public StarlarkInfo binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
    if (op == TokenKind.PLUS && that instanceof StarlarkInfo) {
      final Provider thatProvider = ((StarlarkInfo) that).getProvider();
      if (!provider.equals(thatProvider)) {
        throw Starlark.errorf(
            "Cannot use '+' operator on instances of different providers (%s and %s)",
            provider.getPrintableName(), thatProvider.getPrintableName());
      }
      Preconditions.checkArgument(that instanceof StarlarkInfoNoSchema);
      return thisLeft
          ? plus(this, (StarlarkInfoNoSchema) that) //
          : plus((StarlarkInfoNoSchema) that, this);
    }
    return null;
  }

  private static StarlarkInfo plus(StarlarkInfoNoSchema x, StarlarkInfoNoSchema y)
      throws EvalException {
    // ztable = merge(x.table, y.table)
    int xsize = x.size();
    int ysize = y.size();
    Object[] xkeys = x.keys();
    Object[] ykeys = y.keys();
    int xoffset = x.table.length - xsize;
    int yoffset = y.table.length - ysize;
    int zsize = xsize + ysize;
    Object[] ztable = new Object[zsize + zsize];
    int xi = 0;
    int yi = 0;
    int zi = 0;
    while (xi < xsize && yi < ysize) {
      String xk = (String) xkeys[xi];
      String yk = (String) ykeys[yi];
      int cmp = xk.compareTo(yk);
      if (cmp < 0) {
        ztable[zi] = xk;
        ztable[zi + zsize] = x.table[xoffset + xi];
        xi++;
      } else if (cmp > 0) {
        ztable[zi] = yk;
        ztable[zi + zsize] = y.table[yoffset + yi];
        yi++;
      } else {
        throw Starlark.errorf("cannot add struct instances with common field '%s'", xk);
      }
      zi++;
    }
    while (xi < xsize) {
      ztable[zi] = xkeys[xi];
      ztable[zi + zsize] = x.table[xoffset + xi];
      xi++;
      zi++;
    }
    while (yi < ysize) {
      ztable[zi] = ykeys[yi];
      ztable[zi + zsize] = y.table[yoffset + yi];
      yi++;
      zi++;
    }

    return new StarlarkInfoNoSchema(x.provider, ztable);
  }

  @Override
  public final boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StarlarkInfoNoSchema other)) {
      return false;
    }
    int n = size();
    return provider.equals(other.provider)
        && n == other.size()
        && Arrays.equals(keys(), 0, n, other.keys(), 0, n)
        && Arrays.equals(
            table,
            table.length - n,
            table.length,
            other.table,
            other.table.length - n,
            other.table.length);
  }

  @Override
  public final int hashCode() {
    // Hashes the sorted keys followed by the values, like Arrays.hashCode(table) before
    // optimization.
    int n = size();
    Object[] keys = keys();
    int hash = 1;
    for (int i = 0; i < n; i++) {
      hash = 31 * hash + keys[i].hashCode();
    }
    for (int i = table.length - n; i < table.length; i++) {
      hash = 31 * hash + table[i].hashCode();
    }
    return 31 * provider.hashCode() + hash;
  }

  @Override
  public StarlarkInfoNoSchema unsafeOptimizeMemoryLayout() {
    optimizeValues();
    // The keys are only shared now rather than during construction so that transient instances
    // don't pay for it.
    int n = size();
    return new Optimized(
        provider,
        FieldNames.intern(new FieldNames(Arrays.copyOf(table, n, String[].class))),
        Arrays.copyOfRange(table, n, table.length));
  }

  final void optimizeValues() {
    for (int i = table.length - size(); i < table.length; i++) {
      if (table[i] instanceof Compactable compactable) {
        table[i] = compactable.unsafeOptimizeMemoryLayout();
      }
    }
  }

  /** Sorted field names, weakly interned so that they are shared by all optimized instances. */
  @AutoCodec
  static final class FieldNames {
    private static final Interner<FieldNames> interner = BlazeInterners.newWeakInterner();

    private final String[] names;

    private FieldNames(String[] names) {
      this.names = names;
    }

    @AutoCodec.Interner
    static FieldNames intern(FieldNames fieldNames) {
      return interner.intern(fieldNames);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof FieldNames other && Arrays.equals(names, other.names);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(names);
    }
  }

  /**
   * An instance returned by {@link #unsafeOptimizeMemoryLayout}, which stores only the values in
   * its table and shares its keys with other instances with the same fields.
   */
  private static final class Optimized extends StarlarkInfoNoSchema {
    private final FieldNames fieldNames;

    private Optimized(Provider provider, FieldNames fieldNames, Object[] values) {
      super(provider, values);
      this.fieldNames = fieldNames;
    }

    @Override
    public Optimized unsafeOptimizeMemoryLayout() {
      optimizeValues();
      return this;
    }
  }
}
