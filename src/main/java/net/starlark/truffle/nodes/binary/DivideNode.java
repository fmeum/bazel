package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the division operator (/). In Starlark, / always returns float. */
@NodeInfo(shortName = "/")
public abstract class DivideNode extends BinaryOpNode {

  @Specialization
  protected double divideLongs(long left, long right) {
    if (right == 0) {
      throw new ArithmeticException("division by zero");
    }
    return (double) left / (double) right;
  }

  @Specialization
  protected double divideDoubles(double left, double right) {
    if (right == 0.0) {
      throw new ArithmeticException("division by zero");
    }
    return left / right;
  }
}
