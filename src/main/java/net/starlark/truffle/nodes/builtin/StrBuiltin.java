package net.starlark.truffle.nodes.builtin;

import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.values.NoneValue;

/** Builtin str() function. */
public final class StrBuiltin extends BuiltinRootNode {

  public StrBuiltin(TruffleStarlarkLanguage language) {
    super(language, "str");
  }

  @Override
  protected Object executeBuiltin(Object[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("str() takes exactly one argument (" + arguments.length + " given)");
    }

    Object obj = arguments[0];

    if (obj instanceof String) {
      return obj;
    } else if (obj instanceof Long) {
      return obj.toString();
    } else if (obj instanceof Double) {
      return obj.toString();
    } else if (obj instanceof Boolean) {
      return (Boolean) obj ? "True" : "False";
    } else if (obj instanceof NoneValue) {
      return "None";
    } else {
      return obj.toString();
    }
  }
}
