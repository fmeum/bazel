package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.values.NoneValue;

/** Node for return statement. */
@NodeInfo(shortName = "return", description = "Return statement")
public final class ReturnNode extends StatementNode {
  @Child private ExpressionNode valueNode;

  public ReturnNode(ExpressionNode valueNode) {
    this.valueNode = valueNode;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    Object value = valueNode != null ? valueNode.executeGeneric(frame) : NoneValue.INSTANCE;
    throw new ReturnException(value);
  }
}
