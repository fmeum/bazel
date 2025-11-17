// Copyright 2025 The Bazel Authors. All rights reserved.
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

package net.starlark.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.starlark.truffle.TruffleStarlarkContext;

/**
 * Node for the print() builtin function.
 */
public final class PrintNode extends StarlarkNode {

  @Child private StarlarkNode argument;

  public PrintNode(StarlarkNode argument) {
    this.argument = argument;
  }

  @Override
  public Object execute(VirtualFrame frame, TruffleStarlarkContext context) {
    Object value = argument.execute(frame, context);
    context.print(value);
    return null; // print() returns None
  }
}
