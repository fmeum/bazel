package net.starlark.truffle.nodes.builtin;

import net.starlark.truffle.TruffleStarlarkLanguage;
import net.starlark.truffle.values.DictValue;
import net.starlark.truffle.values.NoneValue;
import net.starlark.truffle.values.StarlarkFunction;
import net.starlark.truffle.values.StarlarkList;

/** Builtin type() function. */
public final class TypeBuiltin extends BuiltinRootNode {

  public TypeBuiltin(TruffleStarlarkLanguage language) {
    super(language, "type");
  }

  @Override
  protected Object executeBuiltin(Object[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("type() takes exactly one argument (" + arguments.length + " given)");
    }

    Object obj = arguments[0];

    if (obj instanceof Long) {
      return "int";
    } else if (obj instanceof Double) {
      return "float";
    } else if (obj instanceof String) {
      return "str";
    } else if (obj instanceof Boolean) {
      return "bool";
    } else if (obj instanceof NoneValue) {
      return "NoneType";
    } else if (obj instanceof StarlarkList) {
      return "list";
    } else if (obj instanceof DictValue) {
      return "dict";
    } else if (obj instanceof StarlarkFunction) {
      return "function";
    } else {
      return obj.getClass().getSimpleName();
    }
  }
}
