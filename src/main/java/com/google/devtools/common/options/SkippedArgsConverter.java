package com.google.devtools.common.options;

import com.google.devtools.build.lib.util.Pair;

public interface SkippedArgsConverter {
  Pair<OptionDefinition, String> convertSkippedArg(String commandLineArg);
}
