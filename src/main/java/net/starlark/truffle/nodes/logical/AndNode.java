package net.starlark.truffle.nodes.logical;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/**
 * Node for the 'and' operator with short-circuit evaluation.
 * Returns the first falsy value, or the last value if all are truthy.
 */
@NodeInfo(shortName = "and")
public abstract class AndNode extends ExpressionNode {
  @Child private ExpressionNode leftNode;
  @Child private ExpressionNode rightNode;

  protected AndNode(ExpressionNode leftNode, ExpressionNode rightNode) {
    this.leftNode = leftNode;
    this.rightNode = rightNode;
  }

  public static AndNode create(ExpressionNode leftNode, ExpressionNode rightNode) {
    return AndNodeGen.create(leftNode, rightNode);
  }

  @Specialization
  protected Object doAnd(VirtualFrame frame) {
    Object left = leftNode.executeGeneric(frame);
    if (!isTruthy(left)) {
      return left;  // Short-circuit: return first falsy value
    }
    return rightNode.executeGeneric(frame);  // Return right value
  }

  private boolean isTruthy(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof Long) {
      return ((Long) value) != 0;
    }
    if (value instanceof Double) {
      return ((Double) value) != 0.0;
    }
    if (value instanceof String) {
      return !((String) value).isEmpty();
    }
    // NoneValue is falsy, everything else is truthy
    return value != null && !value.getClass().getSimpleName().equals("NoneValue");
  }
}
