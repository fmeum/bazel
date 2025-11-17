package net.starlark.truffle.nodes.controlflow;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.NodeInfo;
import net.starlark.truffle.nodes.ExpressionNode;
import net.starlark.truffle.nodes.StatementNode;
import net.starlark.truffle.nodes.local.WriteLocalValueNode;

/** Node for 'for' loops. */
@NodeInfo(shortName = "for", description = "For loop")
public final class ForNode extends StatementNode {
  @Child private ExpressionNode iterableNode;
  @Child private WriteLocalValueNode writeVarNode;
  @Child private StatementNode bodyNode;

  public ForNode(ExpressionNode iterableNode, WriteLocalValueNode writeVarNode, StatementNode bodyNode) {
    this.iterableNode = iterableNode;
    this.writeVarNode = writeVarNode;
    this.bodyNode = bodyNode;
  }

  @Override
  public void executeVoid(VirtualFrame frame) {
    Object iterable = iterableNode.executeGeneric(frame);

    executeLoop(frame, iterable, InteropLibrary.getUncached());
  }

  private void executeLoop(VirtualFrame frame, Object iterable,
                          @CachedLibrary(limit = "3") InteropLibrary interop) {
    try {
      if (!interop.hasArrayElements(iterable)) {
        throw new RuntimeException("for loop requires an iterable");
      }

      long size = interop.getArraySize(iterable);
      for (long i = 0; i < size; i++) {
        try {
          Object element = interop.readArrayElement(iterable, i);
          writeVarNode.executeGeneric(frame, element);

          try {
            bodyNode.executeVoid(frame);
          } catch (ContinueException e) {
            // Continue to next iteration
            continue;
          } catch (BreakException e) {
            // Exit loop
            break;
          }
        } catch (InvalidArrayIndexException e) {
          throw new RuntimeException("Invalid index in for loop: " + i);
        }
      }
    } catch (UnsupportedMessageException e) {
      throw new RuntimeException("Error iterating in for loop", e);
    }
  }
}
