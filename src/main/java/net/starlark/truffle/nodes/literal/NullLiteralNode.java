package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for null literal (used for missing slice indices). */
@NodeInfo(shortName = "null", description = "Null literal")
public final class NullLiteralNode extends ExpressionNode {

  public static final NullLiteralNode INSTANCE = new NullLiteralNode();

  private NullLiteralNode() {
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return null;
  }
}
