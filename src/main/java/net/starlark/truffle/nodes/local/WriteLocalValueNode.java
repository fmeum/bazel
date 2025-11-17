package net.starlark.truffle.nodes.local;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for writing a runtime value to a local variable slot (used in for loops). */
@NodeInfo(shortName = "writeLocalValue", description = "Write runtime value to local variable")
public final class WriteLocalValueNode extends ExpressionNode {
  private final int slot;

  public WriteLocalValueNode(int slot) {
    this.slot = slot;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    throw new UnsupportedOperationException("Use executeGeneric(frame, value)");
  }

  public Object executeGeneric(VirtualFrame frame, Object value) {
    frame.setObject(slot, value);
    return value;
  }
}
