package net.starlark.truffle.nodes.builtin;

import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.StarlarkList;

/** Builtin len() function. */
public final class LenBuiltin extends BuiltinRootNode {

  public LenBuiltin(TruffleStarlarkLanguage language) {
    super(language, "len");
  }

  @Override
  protected Object executeBuiltin(Object[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("len() takes exactly one argument (" + arguments.length + " given)");
    }

    Object obj = arguments[0];

    if (obj instanceof StarlarkList) {
      return (long) ((StarlarkList) obj).size();
    } else if (obj instanceof String) {
      return (long) ((String) obj).length();
    } else if (obj instanceof DictValue) {
      return (long) ((DictValue) obj).size();
    } else {
      throw new IllegalArgumentException("object of type '" + obj.getClass().getSimpleName() + "' has no len()");
    }
  }
}
