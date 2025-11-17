package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node representing a float literal. */
@NodeInfo(shortName = "float", description = "Float literal")
public final class FloatLiteralNode extends ExpressionNode {
  private final double value;

  public FloatLiteralNode(double value) {
    this.value = value;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return value;
  }
}
