package net.starlark.truffle.nodes.expression;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.StarlarkList;

/** Node for indexing operations: obj[index]. */
@NodeInfo(shortName = "index", description = "Index read")
@NodeChild("objectNode")
@NodeChild("indexNode")
public abstract class IndexReadNode extends ExpressionNode {

  @Specialization
  protected Object readList(StarlarkList list, long index) {
    return list.get((int) index);
  }

  @Specialization
  protected Object readDict(DictValue dict, Object key) {
    return dict.get(key);
  }

  @Specialization(guards = "interop.hasArrayElements(object)")
  protected Object readArray(Object object, long index,
                            @CachedLibrary(limit = "3") InteropLibrary interop) {
    try {
      return interop.readArrayElement(object, index);
    } catch (InvalidArrayIndexException | UnsupportedMessageException e) {
      throw new RuntimeException("Index out of range: " + index);
    }
  }

  @Specialization
  protected Object readString(String string, long index) {
    int idx = (int) index;
    if (idx < 0) {
      idx = string.length() + idx;  // Negative indexing
    }
    if (idx < 0 || idx >= string.length()) {
      throw new RuntimeException("string index out of range: " + index);
    }
    return String.valueOf(string.charAt(idx));
  }
}
