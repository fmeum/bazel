package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

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

  // TODO: Add string repetition (str * int, int * str)
  // TODO: Add list repetition when list type is implemented
}
