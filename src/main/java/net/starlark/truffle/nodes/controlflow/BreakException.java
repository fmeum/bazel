package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Exception used to implement the 'break' statement. */
public final class BreakException extends ControlFlowException {
  public static final BreakException INSTANCE = new BreakException();

  private BreakException() {
  }
}
