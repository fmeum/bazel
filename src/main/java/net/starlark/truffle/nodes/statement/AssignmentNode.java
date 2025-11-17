package net.starlark.truffle.nodes.statement;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;

/** Node for an assignment statement. */
@NodeInfo(shortName = "assign", description = "Assignment statement")
public final class AssignmentNode extends StatementNode {
  @Child private ExpressionNode writeNode;

  public AssignmentNode(ExpressionNode writeNode) {
    this.writeNode = writeNode;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    writeNode.executeGeneric(frame);
  }
}
