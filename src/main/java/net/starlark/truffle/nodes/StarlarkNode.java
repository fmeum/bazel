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
import com.oracle.truffle.api.nodes.Node;
import net.starlark.truffle.TruffleStarlarkContext;

/**
 * Base class for all Truffle Starlark AST nodes.
 */
public abstract class StarlarkNode extends Node {

  /**
   * Execute this node and return the result.
   *
   * @param frame the current execution frame
   * @param context the Starlark execution context
   * @return the result of executing this node
   */
  public abstract Object execute(VirtualFrame frame, TruffleStarlarkContext context);
}
