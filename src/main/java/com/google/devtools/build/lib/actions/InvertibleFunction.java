package com.google.devtools.build.lib.actions;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import java.util.Collection;
import java.util.function.Function;

public interface InvertibleFunction<T, R> extends Function<T, R> {
  T applyInverse(R r);

  static <T, R> InvertibleFunction<T, R> restrictionOf(Function<T, R> f, Collection<? extends T> domain) {
    ImmutableBiMap.Builder<T, R> bimapBuilder = new ImmutableBiMap.Builder<>();
    for (T x : domain) {
      R fx = f.apply(x);
      bimapBuilder.put(x, fx);
    }

    final BiMap<T, R> bimap = bimapBuilder.build();
    return new InvertibleFunction<>() {
      @Override
      public R apply(T t) {
        return bimap.get(t);
      }

      @Override
      public T applyInverse(R r) {
        return bimap.inverse().get(r);
      }
    };
  }

  static <T> InvertibleFunction<T, T> identity() {
    return new InvertibleFunction<>() {
      @Override
      public T apply(T t) {
        return t;
      }

      @Override
      public T applyInverse(T t) {
        return t;
      }
    };
  }
}
