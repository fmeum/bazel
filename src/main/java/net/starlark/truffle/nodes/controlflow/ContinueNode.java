package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.StatementNode;

/** Node for the 'continue' statement. */
@NodeInfo(shortName = "continue", description = "Continue statement")
public final class ContinueNode extends StatementNode {
  public static final ContinueNode INSTANCE = new ContinueNode();

  private ContinueNode() {
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    throw ContinueException.INSTANCE;
  }
}
