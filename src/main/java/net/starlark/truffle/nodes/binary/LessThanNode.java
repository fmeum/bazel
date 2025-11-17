package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the less-than operator (<). */
@NodeInfo(shortName = "<")
public abstract class LessThanNode extends BinaryOpNode {

  @Specialization
  protected boolean lessThanLongs(long left, long right) {
    return left < right;
  }

  @Specialization
  protected boolean lessThanDoubles(double left, double right) {
    return left < right;
  }

  @Specialization
  protected boolean lessThanStrings(String left, String right) {
    return left.compareTo(right) < 0;
  }
}
