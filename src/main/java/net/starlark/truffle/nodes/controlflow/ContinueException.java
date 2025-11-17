package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Exception used to implement the 'continue' statement. */
public final class ContinueException extends ControlFlowException {
  public static final ContinueException INSTANCE = new ContinueException();

  private ContinueException() {
  }
}
