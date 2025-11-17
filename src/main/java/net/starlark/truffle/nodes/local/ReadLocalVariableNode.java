package net.starlark.truffle.nodes.local;

import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for reading a local variable from a frame slot. */
@NodeInfo(shortName = "readLocal", description = "Read local variable")
@NodeField(name = "slot", type = int.class)
public abstract class ReadLocalVariableNode extends ExpressionNode {

  protected abstract int getSlot();

  @Specialization
  protected Object readObject(VirtualFrame frame) {
    try {
      return frame.getObject(getSlot());
    } catch (Exception e) {
      throw new RuntimeException("Local variable at slot " + getSlot() + " not defined");
    }
  }
}
