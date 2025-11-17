package net.starlark.truffle.nodes.expression;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.StarlarkList;

/** Node for indexed assignment: obj[index] = value. */
@NodeInfo(shortName = "indexWrite", description = "Index write")
@NodeChild("objectNode")
@NodeChild("indexNode")
@NodeChild("valueNode")
public abstract class IndexWriteNode extends ExpressionNode {

  @Specialization
  protected Object writeList(StarlarkList list, long index, Object value) {
    int idx = (int) index;
    // Handle negative indexing
    if (idx < 0) {
      idx = list.size() + idx;
    }
    // Allow appending when index == size
    if (idx == list.size()) {
      list.append(value);
    } else if (idx >= 0 && idx < list.size()) {
      list.set(idx, value);
    } else {
      throw new IndexOutOfBoundsException("list index out of range: " + index);
    }
    return value;
  }

  @Specialization
  protected Object writeDict(DictValue dict, Object key, Object value) {
    dict.put(key, value);
    return value;
  }
}
