package net.starlark.truffle.nodes.statement;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.StatementNode;

/** Node representing a sequence of statements. */
@NodeInfo(shortName = "block", description = "Block of statements")
public final class BlockNode extends StatementNode {
  @Children private final StatementNode[] statements;

  public BlockNode(StatementNode[] statements) {
    this.statements = statements;
  }

  @Override
  @ExplodeLoop
  public void executeVoid(VirtualFrame frame) {
    for (StatementNode statement : statements) {
      statement.executeVoid(frame);
    }
  }
}
