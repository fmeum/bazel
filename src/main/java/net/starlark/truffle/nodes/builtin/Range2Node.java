package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkRange;

/** Node for range(start, stop). */
@NodeInfo(shortName = "range")
@NodeChild(value = "startNode", type = ExpressionNode.class)
@NodeChild(value = "stopNode", type = ExpressionNode.class)
public abstract class Range2Node extends ExpressionNode {
  @Specialization
  protected StarlarkRange doRange(long start, long stop) {
    return new StarlarkRange(start, stop);
  }
}
