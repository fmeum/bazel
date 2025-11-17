package net.starlark.truffle.nodes.expression;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.StarlarkList;
import java.util.ArrayList;
import java.util.List;

/** Node for list comprehension: [expr for var in iterable if condition]. */
@NodeInfo(shortName = "listComp", description = "List comprehension")
public final class ListComprehensionNode extends ExpressionNode {
  @Child private ExpressionNode expression;
  @Children private final ComprehensionClause[] clauses;

  public ListComprehensionNode(
      ExpressionNode expression,
      ComprehensionClause[] clauses) {
    this.expression = expression;
    this.clauses = clauses;
  }

  @Override
  public Object executeGeneric(VirtualFrame frame) {
    List<Object> result = new ArrayList<>();
    executeComprehension(frame, 0, result);
    return new StarlarkList(result);
  }

  private void executeComprehension(
      VirtualFrame frame,
      int clauseIndex,
      List<Object> result) {

    if (clauseIndex >= clauses.length) {
      // Base case: evaluate expression and add to result
      Object value = expression.executeGeneric(frame);
      result.add(value);
      return;
    }

    ComprehensionClause clause = clauses[clauseIndex];
    clause.execute(frame, clauseIndex, result, this);
  }

  void continueComprehension(
      VirtualFrame frame,
      int clauseIndex,
      List<Object> result) {
    executeComprehension(frame, clauseIndex + 1, result);
  }

  /** Represents a single 'for' clause with optional 'if' filters. */
  public static final class ComprehensionClause {
    @Child private ExpressionNode iterableNode;
    @Children private final ExpressionNode[] filterNodes;
    private final int variableSlot;

    public ComprehensionClause(
        ExpressionNode iterableNode,
        int variableSlot,
        ExpressionNode[] filterNodes) {
      this.iterableNode = iterableNode;
      this.variableSlot = variableSlot;
      this.filterNodes = filterNodes;
    }

    @ExplodeLoop
    public void execute(
        VirtualFrame frame,
        int clauseIndex,
        List<Object> result,
        ListComprehensionNode owner) {

      // Evaluate the iterable
      Object iterable = iterableNode.executeGeneric(frame);

      // Save old value of loop variable (to restore later)
      Object oldValue = frame.isObject(variableSlot) ? frame.getObject(variableSlot) : null;

      try {
        // Iterate over the iterable
        if (iterable instanceof StarlarkList) {
          StarlarkList list = (StarlarkList) iterable;
          for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            frame.setObject(variableSlot, item);

            // Check all filter conditions
            if (passesFilters(frame)) {
              owner.continueComprehension(frame, clauseIndex, result);
            }
          }
        } else if (iterable instanceof String) {
          String str = (String) iterable;
          for (int i = 0; i < str.length(); i++) {
            String item = String.valueOf(str.charAt(i));
            frame.setObject(variableSlot, item);

            // Check all filter conditions
            if (passesFilters(frame)) {
              owner.continueComprehension(frame, clauseIndex, result);
            }
          }
        } else if (iterable instanceof DictValue) {
          // Iterate over dict keys
          DictValue dict = (DictValue) iterable;
          for (Object key : dict.getMap().keySet()) {
            frame.setObject(variableSlot, key);

            // Check all filter conditions
            if (passesFilters(frame)) {
              owner.continueComprehension(frame, clauseIndex, result);
            }
          }
        } else {
          throw new IllegalArgumentException("Cannot iterate over " + iterable.getClass().getSimpleName());
        }
      } finally {
        // Restore old value
        if (oldValue != null) {
          frame.setObject(variableSlot, oldValue);
        }
      }
    }

    @ExplodeLoop
    private boolean passesFilters(VirtualFrame frame) {
      for (ExpressionNode filter : filterNodes) {
        Object filterResult = filter.executeGeneric(frame);
        if (!isTruthy(filterResult)) {
          return false;
        }
      }
      return true;
    }

    private boolean isTruthy(Object obj) {
      if (obj instanceof Boolean) {
        return (Boolean) obj;
      } else if (obj instanceof Long) {
        return ((Long) obj) != 0L;
      } else if (obj instanceof String) {
        return !((String) obj).isEmpty();
      }
      return true;
    }
  }
}
