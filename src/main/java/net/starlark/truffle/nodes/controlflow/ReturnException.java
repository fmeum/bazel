package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Exception for return statements (non-local control flow). */
public final class ReturnException extends ControlFlowException {
  private final Object value;

  public ReturnException(Object value) {
    this.value = value;
  }

  public Object getValue() {
    return value;
  }
}
