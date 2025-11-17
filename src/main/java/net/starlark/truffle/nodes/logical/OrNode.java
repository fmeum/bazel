package net.starlark.truffle.nodes.logical;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/**
 * Node for the 'or' operator with short-circuit evaluation.
 * Returns the first truthy value, or the last value if all are falsy.
 */
@NodeInfo(shortName = "or")
public abstract class OrNode extends ExpressionNode {
  @Child private ExpressionNode leftNode;
  @Child private ExpressionNode rightNode;

  protected OrNode(ExpressionNode leftNode, ExpressionNode rightNode) {
    this.leftNode = leftNode;
    this.rightNode = rightNode;
  }

  public static OrNode create(ExpressionNode leftNode, ExpressionNode rightNode) {
    return OrNodeGen.create(leftNode, rightNode);
  }

  @Specialization
  protected Object doOr(VirtualFrame frame) {
    Object left = leftNode.executeGeneric(frame);
    if (isTruthy(left)) {
      return left;  // Short-circuit: return first truthy value
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
