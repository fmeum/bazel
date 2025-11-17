package net.starlark.truffle.nodes.logical;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;

/** Node for the 'not' operator. */
@NodeInfo(shortName = "not")
@NodeChild("operandNode")
public abstract class NotNode extends ExpressionNode {

  @Specialization
  protected boolean doBoolean(boolean value) {
    return !value;
  }

  @Specialization
  protected boolean doLong(long value) {
    return value == 0;
  }

  @Specialization
  protected boolean doDouble(double value) {
    return value == 0.0;
  }

  @Specialization
  protected boolean doString(String value) {
    return value.isEmpty();
  }

  @Specialization
  protected boolean doGeneric(Object value) {
    // NoneValue is falsy, everything else is truthy
    if (value == null || value.getClass().getSimpleName().equals("NoneValue")) {
      return true;
    }
    return false;
  }
}
