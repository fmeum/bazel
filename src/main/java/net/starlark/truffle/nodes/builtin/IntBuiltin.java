package net.starlark.truffle.nodes.builtin;

import net.starlark.truffle.TruffleStarlarkLanguage;

/** Builtin int() function. */
public final class IntBuiltin extends BuiltinRootNode {

  public IntBuiltin(TruffleStarlarkLanguage language) {
    super(language, "int");
  }

  @Override
  protected Object executeBuiltin(Object[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("int() takes exactly one argument (" + arguments.length + " given)");
    }

    Object obj = arguments[0];

    if (obj instanceof Long) {
      return obj;
    } else if (obj instanceof Double) {
      return (long) ((Double) obj).doubleValue();
    } else if (obj instanceof String) {
      try {
        return Long.parseLong((String) obj);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("invalid literal for int() with base 10: '" + obj + "'");
      }
    } else if (obj instanceof Boolean) {
      return (Boolean) obj ? 1L : 0L;
    } else {
      throw new IllegalArgumentException("int() argument must be a string or a number, not '" + obj.getClass().getSimpleName() + "'");
    }
  }
}
