package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.NoneValue;

/** Node representing the None literal. */
@NodeInfo(shortName = "None", description = "None literal")
public final class NoneLiteralNode extends ExpressionNode {
  public static final NoneLiteralNode INSTANCE = new NoneLiteralNode();

  private NoneLiteralNode() {}

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return NoneValue.INSTANCE;
  }
}
