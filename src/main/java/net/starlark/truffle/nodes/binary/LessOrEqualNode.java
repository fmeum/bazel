package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the less-or-equal operator (<=). */
@NodeInfo(shortName = "<=")
public abstract class LessOrEqualNode extends BinaryOpNode {

  @Specialization
  protected boolean lessOrEqualLongs(long left, long right) {
    return left <= right;
  }

  @Specialization
  protected boolean lessOrEqualDoubles(double left, double right) {
    return left <= right;
  }

  @Specialization
  protected boolean lessOrEqualStrings(String left, String right) {
    return left.compareTo(right) <= 0;
  }
}
