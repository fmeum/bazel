package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import net.starlark.truffle.TruffleStarlarkLanguage;

/** Base class for builtin function root nodes. */
public abstract class BuiltinRootNode extends RootNode {
  private final String builtinName;

  protected BuiltinRootNode(TruffleStarlarkLanguage language, String builtinName) {
    super(language, FrameDescriptor.newBuilder().build());
    this.builtinName = builtinName;
  }

  @Override
  public final Object execute(VirtualFrame frame) {
    Object[] arguments = frame.getArguments();
    try {
      return executeBuiltin(arguments);
    } catch (Exception e) {
      throw new RuntimeException(builtinName + "(): " + e.getMessage(), e);
    }
  }

  protected abstract Object executeBuiltin(Object[] arguments);

  @Override
  public String getName() {
    return builtinName;
  }
}
