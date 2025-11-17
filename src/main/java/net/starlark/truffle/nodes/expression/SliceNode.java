package net.starlark.truffle.nodes.expression;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkList;
import java.util.ArrayList;
import java.util.List;

/** Node for slice operations: obj[start:end]. */
@NodeInfo(shortName = "slice", description = "Slice operation")
@NodeChild("objectNode")
@NodeChild(value = "startNode", type = ExpressionNode.class)
@NodeChild(value = "endNode", type = ExpressionNode.class)
public abstract class SliceNode extends ExpressionNode {

  @Specialization
  protected String sliceString(String string, Object start, Object end) {
    int len = string.length();
    int startIdx = getIndex(start, 0, len);
    int endIdx = getIndex(end, len, len);

    // Clamp indices to valid range
    startIdx = Math.max(0, Math.min(startIdx, len));
    endIdx = Math.max(0, Math.min(endIdx, len));

    // Ensure start <= end
    if (startIdx > endIdx) {
      startIdx = endIdx;
    }

    return string.substring(startIdx, endIdx);
  }

  @Specialization
  protected StarlarkList sliceList(StarlarkList list, Object start, Object end) {
    int len = list.size();
    int startIdx = getIndex(start, 0, len);
    int endIdx = getIndex(end, len, len);

    // Clamp indices to valid range
    startIdx = Math.max(0, Math.min(startIdx, len));
    endIdx = Math.max(0, Math.min(endIdx, len));

    // Ensure start <= end
    if (startIdx > endIdx) {
      startIdx = endIdx;
    }

    List<Object> result = new ArrayList<>();
    for (int i = startIdx; i < endIdx; i++) {
      result.add(list.get(i));
    }

    return new StarlarkList(result);
  }

  /**
   * Convert slice index to actual index.
   * - null means use default (defaultValue)
   * - Negative indices count from end
   */
  private int getIndex(Object obj, int defaultValue, int length) {
    if (obj == null) {
      return defaultValue;
    }

    if (!(obj instanceof Long)) {
      throw new IllegalArgumentException("slice indices must be integers or None");
    }

    long idx = (Long) obj;

    // Handle negative indices
    if (idx < 0) {
      idx = length + idx;
    }

    return (int) idx;
  }
}
