package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkRange;

/** Node for range(start, stop, step). */
@NodeInfo(shortName = "range")
@NodeChild(value = "startNode", type = ExpressionNode.class)
@NodeChild(value = "stopNode", type = ExpressionNode.class)
@NodeChild(value = "stepNode", type = ExpressionNode.class)
public abstract class Range3Node extends ExpressionNode {
  @Specialization
  protected StarlarkRange doRange(long start, long stop, long step) {
    return new StarlarkRange(start, stop, step);
  }
}
