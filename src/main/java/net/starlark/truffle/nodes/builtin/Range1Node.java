package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkRange;

/** Node for range(stop). */
@NodeInfo(shortName = "range")
@NodeChild(value = "stopNode", type = ExpressionNode.class)
public abstract class Range1Node extends ExpressionNode {
  @Specialization
  protected StarlarkRange doRange(long stop) {
    return new StarlarkRange(stop);
  }
}
