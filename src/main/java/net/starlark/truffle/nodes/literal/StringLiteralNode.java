package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node representing a string literal. */
@NodeInfo(shortName = "string", description = "String literal")
public final class StringLiteralNode extends ExpressionNode {
  private final String value;

  public StringLiteralNode(String value) {
    this.value = value;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    return value;
  }
}
