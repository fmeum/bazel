package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/** Node for the modulo operator (%). */
@NodeInfo(shortName = "%")
public abstract class ModuloNode extends BinaryOpNode {

  @Specialization
  protected long moduloLongs(long left, long right) {
    if (right == 0) {
      throw new ArithmeticException("integer modulo by zero");
    }
    // Python-style modulo (result has same sign as divisor)
    long remainder = left % right;
    if ((left ^ right) < 0 && remainder != 0) {
      remainder += right;
    }
    return remainder;
  }

  @Specialization
  protected double moduloDoubles(double left, double right) {
    if (right == 0.0) {
      throw new ArithmeticException("float modulo by zero");
    }
    double remainder = left % right;
    if ((left < 0) != (right < 0) && remainder != 0) {
      remainder += right;
    }
    return remainder;
  }

  // TODO: Add string formatting (str % args) when needed
}
