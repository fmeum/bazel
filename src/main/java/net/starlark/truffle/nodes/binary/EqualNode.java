package net.starlark.truffle.nodes.binary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.values.NoneValue;

/** Node for the equality operator (==). */
@NodeInfo(shortName = "==")
public abstract class EqualNode extends BinaryOpNode {

  @Specialization
  protected boolean equalLongs(long left, long right) {
    return left == right;
  }

  @Specialization
  protected boolean equalDoubles(double left, double right) {
    return left == right;
  }

  @Specialization
  protected boolean equalBooleans(boolean left, boolean right) {
    return left == right;
  }

  @Specialization
  protected boolean equalStrings(String left, String right) {
    return left.equals(right);
  }

  @Specialization
  protected boolean equalNone(NoneValue left, NoneValue right) {
    return true;
  }

  @Specialization
  protected boolean equalGeneric(Object left, Object right) {
    return left.equals(right);
  }
}
