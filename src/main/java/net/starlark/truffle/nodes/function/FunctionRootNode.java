package net.starlark.truffle.nodes.function;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.nodes.controlflow.ReturnException;
import net.starlark.truffle.values.NoneValue;

/** Root node for a user-defined Starlark function. */
public final class FunctionRootNode extends RootNode {
  private final String functionName;
  private final int parameterCount;
  private final int firstParamSlot;
  @Child private StatementNode body;

  public FunctionRootNode(
      TruffleStarlarkLanguage language,
      FrameDescriptor frameDescriptor,
      String functionName,
      int parameterCount,
      int firstParamSlot,
      StatementNode body) {
    super(language, frameDescriptor);
    this.functionName = functionName;
    this.parameterCount = parameterCount;
    this.firstParamSlot = firstParamSlot;
    this.body = body;
  }

  @Override
  public Object execute(VirtualFrame frame) {
    // Arguments are passed as an Object[] and need to be written to frame slots
    Object[] arguments = frame.getArguments();

    if (arguments.length != parameterCount) {
      throw new IllegalArgumentException(
          functionName + "() takes " + parameterCount + " argument(s) but " +
          arguments.length + " were given");
    }

    // Write arguments to frame slots (starting from firstParamSlot)
    for (int i = 0; i < arguments.length; i++) {
      frame.setObject(firstParamSlot + i, arguments[i]);
    }

    try {
      body.executeVoid(frame);
      return NoneValue.INSTANCE;  // Implicit None return
    } catch (ReturnException e) {
      return e.getValue();
    } catch (Throwable t) {
      // Debug: catch everything to see what's happening
      throw new RuntimeException("Unexpected exception in function " + functionName + ": " + t.getClass().getName(), t);
    }
  }

  @Override
  public String getName() {
    return functionName;
  }
}
