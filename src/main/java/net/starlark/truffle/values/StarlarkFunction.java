package net.starlark.truffle.values;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Starlark user-defined function. */
@ExportLibrary(InteropLibrary.class)
public final class StarlarkFunction implements TruffleObject {
  private final String name;
  private final String[] parameters;
  private final CallTarget callTarget;

  public StarlarkFunction(String name, String[] parameters, CallTarget callTarget) {
    this.name = name;
    this.parameters = parameters;
    this.callTarget = callTarget;
  }

  public String getName() {
    return name;
  }

  public String[] getParameters() {
    return parameters;
  }

  public CallTarget getCallTarget() {
    return callTarget;
  }

  public int getParameterCount() {
    return parameters.length;
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
      // Re-throw as RuntimeException to avoid Truffle wrapping
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException("Error executing function: " + name, e);
    }
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    return "<function " + name + ">";
  }

  @Override
  public String toString() {
    return toDisplayString(false).toString();
  }
}
