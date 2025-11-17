package net.starlark.truffle.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.DictValue;

/** Node for dictionary literal expressions: {k1: v1, k2: v2, ...} */
@NodeInfo(shortName = "dictLiteral", description = "Dictionary literal")
public final class DictLiteralNode extends ExpressionNode {
  @Children private final ExpressionNode[] keys;
  @Children private final ExpressionNode[] values;

  public DictLiteralNode(ExpressionNode[] keys, ExpressionNode[] values) {
    if (keys.length != values.length) {
      throw new IllegalArgumentException("Keys and values arrays must have same length");
    }
    this.keys = keys;
    this.values = values;
  }

  @Override
  @ExplodeLoop
  public Object executeGeneric(VirtualFrame frame) {
    DictValue dict = new DictValue();
    for (int i = 0; i < keys.length; i++) {
      Object key = keys[i].executeGeneric(frame);
      Object value = values[i].executeGeneric(frame);
      dict.put(key, value);
    }
    return dict;
  }
}
