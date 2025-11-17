package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the subtraction operator (-). */
@NodeInfo(shortName = "-")
public abstract class SubtractNode extends BinaryOpNode {

  @Specialization(rewriteOn = ArithmeticException.class)
  protected long subtractLongs(long left, long right) {
    return Math.subtractExact(left, right);
  }

  @Specialization(replaces = "subtractLongs")
  protected double subtractLongsOverflow(long left, long right) {
    return (double) left - (double) right;
  }

  @Specialization
  protected double subtractDoubles(double left, double right) {
    return left - right;
  }
}
