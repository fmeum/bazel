package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.values.NoneValue;

/** Node for the inequality operator (!=). */
@NodeInfo(shortName = "!=")
public abstract class NotEqualNode extends BinaryOpNode {

  @Specialization
  protected boolean notEqualLongs(long left, long right) {
    return left != right;
  }

  @Specialization
  protected boolean notEqualDoubles(double left, double right) {
    return left != right;
  }

  @Specialization
  protected boolean notEqualBooleans(boolean left, boolean right) {
    return left != right;
  }

  @Specialization
  protected boolean notEqualStrings(String left, String right) {
    return !left.equals(right);
  }

  @Specialization
  protected boolean notEqualNone(NoneValue left, NoneValue right) {
    return false;
  }

  @Specialization
  protected boolean notEqualGeneric(Object left, Object right) {
    return !left.equals(right);
  }
}
