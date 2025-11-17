package net.starlark.truffle.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.values.StarlarkFunction;

/** Node for function definition (def name(params): body). */
@NodeInfo(shortName = "def", description = "Function definition")
public final class FunctionDefNode extends StatementNode {
  private final int functionSlot;
  private final StarlarkFunction function;

  public FunctionDefNode(int functionSlot, StarlarkFunction function) {
    this.functionSlot = functionSlot;
    this.function = function;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    // Store the function object in the frame slot
    frame.setObject(functionSlot, function);
  }
}
