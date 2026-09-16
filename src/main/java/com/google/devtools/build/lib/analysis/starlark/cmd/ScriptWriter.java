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

package com.google.devtools.build.lib.analysis.starlark.cmd;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.PathMapper;

/**
 * Accumulates the line-oriented script format understood by {@code cmd_runner}.
 *
 * <p>Each line consists of a keyword, optionally followed by a space and a payload in which
 * backslashes, newlines and carriage returns are escaped so that the script can be written as an
 * unquoted params file. See {@code src/tools/cmd_runner/cmd_runner.cc} for the grammar.
 */
final class ScriptWriter {
  static final String FORMAT_VERSION = "1";

  private final ImmutableList.Builder<String> lines = ImmutableList.builder();
  private final PathMapper pathMapper;

  ScriptWriter(PathMapper pathMapper) {
    this.pathMapper = pathMapper;
  }

  PathMapper pathMapper() {
    return pathMapper;
  }

  ScriptWriter line(String keyword) {
    lines.add(keyword);
    return this;
  }

  ScriptWriter line(String keyword, String payload) {
    lines.add(keyword + " " + escape(payload));
    return this;
  }

  ImmutableList<String> build() {
    return lines.build();
  }

  static String escape(String s) {
    StringBuilder sb = null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      String replacement =
          switch (c) {
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            default -> null;
          };
      if (replacement != null) {
        if (sb == null) {
          sb = new StringBuilder(s.length() + 8);
          sb.append(s, 0, i);
        }
        sb.append(replacement);
      } else if (sb != null) {
        sb.append(c);
      }
    }
    return sb == null ? s : sb.toString();
  }
}
