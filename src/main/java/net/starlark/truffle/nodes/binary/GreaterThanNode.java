package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the greater-than operator (>). */
@NodeInfo(shortName = ">")
public abstract class GreaterThanNode extends BinaryOpNode {

  @Specialization
  protected boolean greaterThanLongs(long left, long right) {
    return left > right;
  }

  @Specialization
  protected boolean greaterThanDoubles(double left, double right) {
    return left > right;
  }

  @Specialization
  protected boolean greaterThanStrings(String left, String right) {
    return left.compareTo(right) > 0;
  }
}
