package net.starlark.truffle.nodes.arithmetic;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for unary minus: -expr */
@NodeInfo(shortName = "unary-", description = "Unary minus")
@NodeChild(value = "operand", type = ExpressionNode.class)
public abstract class UnaryMinusNode extends ExpressionNode {

  @Specialization
  protected long doLong(long value) {
    return -value;
  }

  @Specialization
  protected double doDouble(double value) {
    return -value;
  }
}
