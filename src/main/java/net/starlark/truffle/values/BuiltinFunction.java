package net.starlark.truffle.values;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Starlark builtin function. */
@ExportLibrary(InteropLibrary.class)
public final class BuiltinFunction implements TruffleObject {
  private final String name;
  private final CallTarget callTarget;

  public BuiltinFunction(String name, CallTarget callTarget) {
    this.name = name;
    this.callTarget = callTarget;
  }

  public String getName() {
    return name;
  }

  public CallTarget getCallTarget() {
    return callTarget;
  }

  @ExportMessage
  boolean isExecutable() {
    return true;
  }

  @ExportMessage
  Object execute(Object[] arguments) {
    try {
      return callTarget.call(arguments);
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException("Error executing builtin: " + name, e);
    }
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    return "<builtin function " + name + ">";
  }

  @Override
  public String toString() {
    return toDisplayString(false).toString();
  }
}
