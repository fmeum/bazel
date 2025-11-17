package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node representing a boolean literal (True or False). */
@NodeInfo(shortName = "bool", description = "Boolean literal")
public final class BooleanLiteralNode extends ExpressionNode {
  private final boolean value;

  public BooleanLiteralNode(boolean value) {
    this.value = value;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return value;
  }
}
