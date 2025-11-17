package net.starlark.truffle.nodes.builtin;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.values.BuiltinFunction;

/** Node that initializes a builtin function in a frame slot. */
public final class BuiltinInitNode extends StatementNode {
  private final TruffleStarlarkLanguage language;
  private final String builtinName;
  private final int slot;

  public BuiltinInitNode(TruffleStarlarkLanguage language, String builtinName, int slot) {
    this.language = language;
    this.builtinName = builtinName;
    this.slot = slot;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    // Create the appropriate builtin root node
    BuiltinRootNode rootNode = createBuiltinRootNode();

    // Create a CallTarget from the root node
    CallTarget callTarget = rootNode.getCallTarget();

    // Create and store the builtin function
    BuiltinFunction builtin = new BuiltinFunction(builtinName, callTarget);
    frame.setObject(slot, builtin);
  }

  private BuiltinRootNode createBuiltinRootNode() {
    switch (builtinName) {
      case "len":
        return new LenBuiltin(language);
      case "str":
        return new StrBuiltin(language);
      case "int":
        return new IntBuiltin(language);
      case "type":
        return new TypeBuiltin(language);
      case "bool":
        return new BoolBuiltin(language);
      default:
        throw new IllegalArgumentException("Unknown builtin: " + builtinName);
    }
  }
}
