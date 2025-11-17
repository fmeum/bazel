package net.starlark.truffle.nodes.statement;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;

/** Node for an expression used as a statement (e.g., a function call). */
@NodeInfo(shortName = "exprStmt", description = "Expression statement")
public final class ExpressionStatementNode extends StatementNode {
  @Child private ExpressionNode expression;

  public ExpressionStatementNode(ExpressionNode expression) {
    this.expression = expression;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    expression.executeGeneric(frame);
  }
}
