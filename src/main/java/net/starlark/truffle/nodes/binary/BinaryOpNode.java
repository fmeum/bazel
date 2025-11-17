package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Base class for all binary operation nodes. */
@NodeInfo(description = "Binary operation")
@NodeChild("leftNode")
@NodeChild("rightNode")
public abstract class BinaryOpNode extends ExpressionNode {
  // Subclasses implement specific operations using @Specialization
}
