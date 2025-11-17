package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkList;

import java.util.ArrayList;
import java.util.List;

/** Node representing a list literal [e1, e2, ...]. */
@NodeInfo(shortName = "list", description = "List literal")
public final class ListLiteralNode extends ExpressionNode {
  @Children private final ExpressionNode[] elements;

  public ListLiteralNode(ExpressionNode[] elements) {
    this.elements = elements;
  }

  @Override
  @ExplodeLoop
  public Object executeGeneric(VirtualFrame frame) {
    List<Object> values = new ArrayList<>(elements.length);
    for (ExpressionNode element : elements) {
      values.add(element.executeGeneric(frame));
    }
    return new StarlarkList(values);
  }
}
