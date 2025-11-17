package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the greater-or-equal operator (>=). */
@NodeInfo(shortName = ">=")
public abstract class GreaterOrEqualNode extends BinaryOpNode {

  @Specialization
  protected boolean greaterOrEqualLongs(long left, long right) {
    return left >= right;
  }

  @Specialization
  protected boolean greaterOrEqualDoubles(double left, double right) {
    return left >= right;
  }

  @Specialization
  protected boolean greaterOrEqualStrings(String left, String right) {
    return left.compareTo(right) >= 0;
  }
}
