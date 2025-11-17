package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node representing an integer literal. */
@NodeInfo(shortName = "int", description = "Integer literal")
public final class IntLiteralNode extends ExpressionNode {
  private final long value;

  public IntLiteralNode(long value) {
    this.value = value;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return value;
  }
}
