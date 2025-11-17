package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.values.StarlarkList;
import java.util.ArrayList;
import java.util.List;

/** Node for the multiplication operator (*). */
@NodeInfo(shortName = "*")
public abstract class MultiplyNode extends BinaryOpNode {

  @Specialization(rewriteOn = ArithmeticException.class)
  protected long multiplyLongs(long left, long right) {
    return Math.multiplyExact(left, right);
  }

  @Specialization(replaces = "multiplyLongs")
  protected double multiplyLongsOverflow(long left, long right) {
    return (double) left * (double) right;
  }

  @Specialization
  protected double multiplyDoubles(double left, double right) {
    return left * right;
  }

  @Specialization
  protected String repeatString(String str, long count) {
    if (count <= 0) {
      return "";
    }
    if (count > 10000) {
      throw new IllegalArgumentException("String repetition count too large");
    }
    StringBuilder result = new StringBuilder((int) (str.length() * count));
    for (int i = 0; i < count; i++) {
      result.append(str);
    }
    return result.toString();
  }

  @Specialization
  protected String repeatStringReverse(long count, String str) {
    return repeatString(str, count);
  }

  @Specialization
  protected StarlarkList repeatList(StarlarkList list, long count) {
    if (count <= 0) {
      return new StarlarkList(new ArrayList<>());
    }
    if (count > 10000) {
      throw new IllegalArgumentException("List repetition count too large");
    }
    List<Object> result = new ArrayList<>((int) (list.size() * count));
    for (int i = 0; i < count; i++) {
      for (int j = 0; j < list.size(); j++) {
        result.add(list.get(j));
      }
    }
    return new StarlarkList(result);
  }

  @Specialization
  protected StarlarkList repeatListReverse(long count, StarlarkList list) {
    return repeatList(list, count);
  }
}
