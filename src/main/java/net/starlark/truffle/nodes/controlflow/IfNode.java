package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;

/** Node for if/elif/else statements. */
@NodeInfo(shortName = "if", description = "If statement")
public final class IfNode extends StatementNode {
  @Children private final ExpressionNode[] conditions;
  @Children private final StatementNode[] bodies;
  @Child private StatementNode elseBlock;

  public IfNode(ExpressionNode[] conditions, StatementNode[] bodies, StatementNode elseBlock) {
    assert conditions.length == bodies.length;
    this.conditions = conditions;
    this.bodies = bodies;
    this.elseBlock = elseBlock;
  }

  @Override
  @ExplodeLoop
  public void executeVoid(VirtualFrame frame) {
    for (int i = 0; i < conditions.length; i++) {
      if (evaluateCondition(conditions[i].executeGeneric(frame))) {
        bodies[i].executeVoid(frame);
        return;
      }
    }

    // Execute else block if present
    if (elseBlock != null) {
      elseBlock.executeVoid(frame);
    }
  }

  private boolean evaluateCondition(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof Long) {
      return ((Long) value) != 0;
    }
    if (value instanceof Double) {
      return ((Double) value) != 0.0;
    }
    if (value instanceof String) {
      return !((String) value).isEmpty();
    }
    // NoneValue is falsy, everything else is truthy
    return value != null && !value.getClass().getSimpleName().equals("NoneValue");
  }
}
