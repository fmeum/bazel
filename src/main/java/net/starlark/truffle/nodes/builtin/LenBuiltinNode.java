package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkList;

/** Node for the len() builtin function. */
@NodeInfo(shortName = "len")
@NodeChild(value = "objectNode", type = ExpressionNode.class)
public abstract class LenBuiltinNode extends ExpressionNode {

  @Specialization
  protected long lenList(StarlarkList list) {
    return list.size();
  }

  @Specialization
  protected long lenString(String string) {
    return string.length();
  }

  @Specialization(guards = "interop.hasArrayElements(object)")
  protected long lenArray(Object object,
                         @CachedLibrary(limit = "3") InteropLibrary interop) {
    try {
      return interop.getArraySize(object);
    } catch (UnsupportedMessageException e) {
      throw new RuntimeException("len() not supported for this object");
    }
  }
}
