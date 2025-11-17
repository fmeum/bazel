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

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.StarlarkTypes;

/**
 * Base class for all Starlark expression nodes.
 *
 * <p>Expressions evaluate to a value and can be used anywhere a value is expected.
 */
@TypeSystemReference(StarlarkTypes.class)
@NodeInfo(description = "Starlark expression")
public abstract class ExpressionNode extends Node {

  /**
   * Execute this expression and return the result.
   *
   * @param frame the current execution frame
   * @return the value produced by this expression
   */
  public abstract Object executeGeneric(VirtualFrame frame);
}
