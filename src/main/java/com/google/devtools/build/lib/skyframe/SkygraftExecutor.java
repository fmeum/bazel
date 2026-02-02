package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;

public class SkygraftExecutor {

  public static void execute(
      InMemoryMemoizingEvaluator evaluator, ImmutableSet<Label> starlarkOptions)
      throws InterruptedException {
    var graph = evaluator.getInMemoryGraph();
  }
}
