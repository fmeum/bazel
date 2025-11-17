package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;

/** Node for while loop statements. */
@NodeInfo(shortName = "while", description = "While loop")
public final class WhileNode extends StatementNode {
  @Child private ExpressionNode condition;
  @Child private StatementNode body;

  public WhileNode(ExpressionNode condition, StatementNode body) {
    this.condition = condition;
    this.body = body;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    while (evaluateCondition(condition.executeGeneric(frame))) {
      try {
        body.executeVoid(frame);
      } catch (ContinueException e) {
        // Continue to next iteration
        continue;
      } catch (BreakException e) {
        // Exit loop
        break;
      }
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
