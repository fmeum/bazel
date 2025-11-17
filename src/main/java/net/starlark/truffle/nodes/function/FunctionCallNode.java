package net.starlark.truffle.nodes.function;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.values.StarlarkFunction;

/** Node for calling a user-defined or builtin function. */
@NodeInfo(shortName = "call", description = "Function call")
@NodeChild(value = "functionNode", type = ExpressionNode.class)
public abstract class FunctionCallNode extends ExpressionNode {
  @Children private final ExpressionNode[] argumentNodes;

  protected FunctionCallNode(ExpressionNode[] argumentNodes) {
    this.argumentNodes = argumentNodes;
  }

  @Specialization
  protected Object callFunction(
      VirtualFrame frame,
      StarlarkFunction function,
      @CachedLibrary(limit = "3") InteropLibrary interop) {

    // Evaluate all arguments
    Object[] arguments = new Object[argumentNodes.length];
    for (int i = 0; i < argumentNodes.length; i++) {
      arguments[i] = argumentNodes[i].executeGeneric(frame);
    }

    // Call the function via Truffle interop
    try {
      return interop.execute(function, arguments);
    } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
      throw new RuntimeException("Error calling function: " + e.getMessage(), e);
    }
  }

  @Specialization(guards = "interop.isExecutable(callable)")
  protected Object callGeneric(
      VirtualFrame frame,
      Object callable,
      @CachedLibrary(limit = "3") InteropLibrary interop) {

    // Evaluate all arguments
    Object[] arguments = new Object[argumentNodes.length];
    for (int i = 0; i < argumentNodes.length; i++) {
      arguments[i] = argumentNodes[i].executeGeneric(frame);
    }

    // Call via Truffle interop
    try {
      return interop.execute(callable, arguments);
    } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
      throw new RuntimeException("Error calling function: " + e.getMessage(), e);
    }
  }

  public static FunctionCallNode create(ExpressionNode functionNode, ExpressionNode[] argumentNodes) {
    return FunctionCallNodeGen.create(argumentNodes, functionNode);
  }
}
