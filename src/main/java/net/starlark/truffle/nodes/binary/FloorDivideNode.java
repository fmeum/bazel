package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the floor division operator (//). */
@NodeInfo(shortName = "//")
public abstract class FloorDivideNode extends BinaryOpNode {

  @Specialization
  protected long floorDivideLongs(long left, long right) {
    if (right == 0) {
      throw new ArithmeticException("integer division by zero");
    }
    // Python-style floor division (rounds toward negative infinity)
    long quotient = left / right;
    long remainder = left % right;
    // Adjust if signs differ and there's a remainder
    if ((left ^ right) < 0 && remainder != 0) {
      quotient--;
    }
    return quotient;
  }

  @Specialization
  protected long floorDivideDoubles(double left, double right) {
    if (right == 0.0) {
      throw new ArithmeticException("float floor division by zero");
    }
    return (long) Math.floor(left / right);
  }
}
