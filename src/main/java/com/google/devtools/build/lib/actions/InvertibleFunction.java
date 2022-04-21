package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public interface InvertibleFunction<T, R> extends Function<T, R> {
  T applyInverse(R r);

  static <T, R> InvertibleFunction<T, R> restrictionOf(Function<T, R> f, Collection<? extends T> domain) {
    ImmutableMap.Builder<T, R> mapBuilder = new Builder<>();
    ImmutableMap.Builder<R, T> inverseMapBuilder = new Builder<>();
    for (T x : domain) {
      R fx = f.apply(x);
      mapBuilder.put(x, fx);
      inverseMapBuilder.put(fx, x);
    }

    final Map<T, R> map = mapBuilder.build();
    final Map<R, T> inverseMap = inverseMapBuilder.build();
    return new InvertibleFunction<T, R>() {
      @Override
      public R apply(T t) {
        return map.get(t);
      }

      @Override
      public T applyInverse(R r) {
        return inverseMap.get(r);
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
