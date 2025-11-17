package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the addition operator (+). */
@NodeInfo(shortName = "+")
public abstract class AddNode extends BinaryOpNode {

  @Specialization(rewriteOn = ArithmeticException.class)
  protected long addLongs(long left, long right) {
    return Math.addExact(left, right);
  }

  @Specialization(replaces = "addLongs")
  protected double addLongsOverflow(long left, long right) {
    return (double) left + (double) right;
  }

  @Specialization
  protected double addDoubles(double left, double right) {
    return left + right;
  }

  @Specialization
  protected String addStrings(String left, String right) {
    return left + right;
  }

  // TODO: Add list concatenation, tuple concatenation when those types are implemented
}
