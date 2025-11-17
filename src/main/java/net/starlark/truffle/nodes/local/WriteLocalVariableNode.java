package net.starlark.truffle.nodes.local;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for writing a local variable to a frame slot. */
@NodeInfo(shortName = "writeLocal", description = "Write local variable")
@NodeChild("valueNode")
@NodeField(name = "slot", type = int.class)
public abstract class WriteLocalVariableNode extends ExpressionNode {

  protected abstract int getSlot();

  @Specialization
  protected Object writeObject(VirtualFrame frame, Object value) {
    frame.setObject(getSlot(), value);
    return value;
  }
}
