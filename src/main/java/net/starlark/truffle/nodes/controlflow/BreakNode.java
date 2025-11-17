package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.StatementNode;

/** Node for the 'break' statement. */
@NodeInfo(shortName = "break", description = "Break statement")
public final class BreakNode extends StatementNode {
  public static final BreakNode INSTANCE = new BreakNode();

  private BreakNode() {
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    throw BreakException.INSTANCE;
  }
}
