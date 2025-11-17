package net.starlark.truffle.nodes.builtin;

import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.NoneValue;
import net.starlark.truffle.values.StarlarkList;

/** Builtin bool() function. */
public final class BoolBuiltin extends BuiltinRootNode {

  public BoolBuiltin(TruffleStarlarkLanguage language) {
    super(language, "bool");
  }

  @Override
  protected Object executeBuiltin(Object[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("bool() takes exactly one argument (" + arguments.length + " given)");
    }

    Object obj = arguments[0];

    // Convert to boolean using Starlark truthiness rules
    if (obj instanceof Boolean) {
      return obj;
    } else if (obj instanceof Long) {
      return ((Long) obj) != 0L;
    } else if (obj instanceof Double) {
      return ((Double) obj) != 0.0;
    } else if (obj instanceof String) {
      return !((String) obj).isEmpty();
    } else if (obj instanceof NoneValue) {
      return false;
    } else if (obj instanceof StarlarkList) {
      return ((StarlarkList) obj).size() > 0;
    } else if (obj instanceof DictValue) {
      return ((DictValue) obj).size() > 0;
    } else {
      // Unknown types are truthy by default
      return true;
    }
  }
}
