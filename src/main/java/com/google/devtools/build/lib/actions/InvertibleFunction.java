package com.google.devtools.build.lib.actions;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import java.util.Collection;
import java.util.function.Function;

public interface InvertibleFunction<T, R> extends Function<T, R> {
  T applyInverse(R r);

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
