// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.BinaryOperatorExpression;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.ConditionalExpression;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FloatLiteral;
import net.starlark.java.syntax.FlowStatement;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.IfStatement;
import net.starlark.java.syntax.IndexExpression;
import net.starlark.java.syntax.IntLiteral;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.ReturnStatement;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.TokenKind;
import net.starlark.java.syntax.TypeTable;
import net.starlark.java.syntax.UnaryOperatorExpression;

/**
 * An experimental {@link StarlarkEngine} that compiles Starlark function bodies to Truffle {@link
 * RootCallTarget}s. Selected via {@code --experimental_starlark_engine=truffle}.
 *
 * <p>This is an incremental port: it natively handles a growing subset of node types and <b>falls
 * back to the tree-walking {@link Eval} evaluator</b> for everything else (top-level file bodies,
 * functions whose locals are shared with nested functions, and any unsupported construct). Every
 * native node reuses the exact same primitives as {@link Eval} (e.g. {@link EvalUtils#binaryOp} and
 * {@link Starlark#positionalOnlyCall}) so behavior is identical; the {@code
 * StarlarkEngineConsistencyTest} differential harness enforces this.
 *
 * <p><b>Frame model.</b> For functions with no "cell" locals (none of their locals are captured by a
 * nested function), the locals live in an unboxed Truffle {@code VirtualFrame} rather than the
 * heap-allocated {@link StarlarkThread.Frame#locals} array. A {@code VirtualFrame} is virtual under
 * partial evaluation, so once a hot function is JIT-compiled the Graal compiler keeps these slots in
 * registers - which is where the speedup over the tree-walker comes from. For the frame to stay
 * virtual, loops must use a Truffle {@link LoopNode} (not a host Java loop) and {@code return} must
 * unwind via a {@link ControlFlowException}. The Bazel {@link StarlarkThread.Frame} rides through as
 * the call argument ({@code frame.getArguments()[0]}) for globals, calls, and the thread. Functions
 * with cell locals keep using the Bazel frame and so fall back to {@link Eval}.
 */
final class TruffleStarlarkEngine implements StarlarkEngine {

  static {
    StarlarkEngine.register("truffle", new TruffleStarlarkEngine());
  }

  /** Sentinel cache value meaning "this function uses a construct we don't compile; use Eval". */
  private static final Object FALLBACK = new Object();

  /** Instrumentation: number of function bodies executed natively (read by tests/benchmarks). */
  static final AtomicLong NATIVE_CALLS = new AtomicLong();

  /** With -Dstarlark.truffle.debug=true, log the active Truffle runtime once (is JVMCI/Graal on?). */
  private static final boolean DEBUG = Boolean.getBoolean("starlark.truffle.debug");

  private static volatile boolean diagnosed = false;

  /** Compiled bodies, keyed by the resolved function (shared across closure instances). */
  private final ConcurrentHashMap<Resolver.Function, Object> compiled = new ConcurrentHashMap<>();

  @Override
  public Object execFunctionBody(StarlarkThread.Frame fr, List<Statement> statements)
      throws EvalException, InterruptedException {
    fr.thread.checkInterrupt();
    if (DEBUG && !diagnosed) {
      diagnosed = true;
      System.err.println(
          "[starlark-truffle] Truffle runtime: "
              + Truffle.getRuntime().getName()
              + " ["
              + Truffle.getRuntime().getClass().getName()
              + "]");
    }
    StarlarkFunction fn = (StarlarkFunction) fr.fn;
    // Top-level file bodies have export side effects handled by the tree-walker; never compile them.
    if (fn.isToplevel()) {
      return StarlarkEngine.TREE_WALKING.execFunctionBody(fr, statements);
    }
    Object target = compiled.computeIfAbsent(fn.rfn, TruffleStarlarkEngine::tryCompile);
    if (target == FALLBACK) {
      return StarlarkEngine.TREE_WALKING.execFunctionBody(fr, statements);
    }
    try {
      return ((RootCallTarget) target).call(fr);
    } catch (HostException e) {
      throw e.rethrow();
    }
  }

  /** Compiles a function body to a CallTarget, or returns {@link #FALLBACK} if it can't. */
  private static Object tryCompile(Resolver.Function rfn) {
    try {
      // Cell locals are shared with nested functions via the Bazel frame; can't move them to slots.
      if (rfn.getCellIndices().length != 0) {
        return FALLBACK;
      }
      // Slots: one per declared local, then one hidden iterator slot per for-loop (allocated below).
      int[] nextSlot = {rfn.getLocals().size()};
      SlotStmt[] body = compileBlock(rfn.getBody(), nextSlot);
      FrameDescriptor.Builder fd = FrameDescriptor.newBuilder();
      fd.addSlots(nextSlot[0], FrameSlotKind.Object);
      return new BodyRootNode(fd.build(), rfn.getLocals().size(), body).getCallTarget();
    } catch (RuntimeException | StackOverflowError e) {
      return FALLBACK;
    }
  }

  /** The Bazel frame for the current call, threaded through as the Truffle call argument. */
  private static StarlarkThread.Frame frameOf(VirtualFrame vf) {
    return (StarlarkThread.Frame) vf.getArguments()[0];
  }

  // ---- compilation (AST -> Truffle nodes), once per function ----

  /** Thrown when a construct is not yet handled natively, triggering Eval fallback. */
  private static final class Unsupported extends RuntimeException {
    Unsupported(String what) {
      super(what);
    }
  }

  private static SlotStmt[] compileBlock(List<Statement> stmts, int[] nextSlot) {
    SlotStmt[] out = new SlotStmt[stmts.size()];
    for (int i = 0; i < stmts.size(); i++) {
      out[i] = compileStmt(stmts.get(i), nextSlot);
    }
    return out;
  }

  private static SlotStmt compileStmt(Statement s, int[] nextSlot) {
    if (s instanceof ReturnStatement ret) {
      Expression r = ret.getResult();
      return new ReturnNode(r == null ? null : compileExpr(r));
    } else if (s instanceof IfStatement ifs) {
      return new IfNode(
          compileExpr(ifs.getCondition()),
          compileBlock(ifs.getThenBlock(), nextSlot),
          ifs.getElseBlock() == null ? null : compileBlock(ifs.getElseBlock(), nextSlot));
    } else if (s instanceof ForStatement fors) {
      if (!(fors.getVars() instanceof Identifier var)
          || var.getBinding().getScope() != Resolver.Scope.LOCAL) {
        throw new Unsupported("for target");
      }
      int iterSlot = nextSlot[0]++;
      return new ForNode(
          var.getBinding().getIndex(),
          iterSlot,
          compileExpr(fors.getCollection()),
          fors.getCollection().getStartLocation(),
          fors.getStartLocation(),
          compileBlock(fors.getBody(), nextSlot));
    } else if (s instanceof FlowStatement flow) {
      return new FlowNode(flow.getFlowKind());
    } else if (s instanceof AssignmentStatement assign) {
      return assign.isAugmented() ? compileAugmentedAssign(assign) : compileSimpleAssign(assign);
    } else if (s instanceof ExpressionStatement es) {
      return new ExprStmtNode(compileExpr(es.getExpression()));
    }
    throw new Unsupported("statement: " + s.kind());
  }

  private static SlotStmt compileSimpleAssign(AssignmentStatement node) {
    Expression lhs = node.getLHS();
    SlotExpr rhs = compileExpr(node.getRHS());
    Location opLoc = node.getOperatorLocation();
    if (lhs instanceof Identifier id) {
      return new AssignIdentNode(assignableBinding(id), rhs, opLoc);
    } else if (lhs instanceof IndexExpression index) {
      return new AssignIndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), rhs, opLoc);
    } else if (lhs instanceof DotExpression dot) {
      return new AssignDotNode(
          compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation(), rhs, opLoc);
    }
    throw new Unsupported("assignment target: " + lhs.kind());
  }

  private static SlotStmt compileAugmentedAssign(AssignmentStatement node) {
    Expression lhs = node.getLHS();
    SlotExpr rhs = compileExpr(node.getRHS());
    TokenKind op = node.getOperator();
    Location opLoc = node.getOperatorLocation();
    if (lhs instanceof Identifier id) {
      return new AugAssignIdentNode(assignableBinding(id), new IdentifierNode(id), op, rhs, opLoc);
    } else if (lhs instanceof IndexExpression index) {
      return new AugAssignIndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), op, rhs, opLoc);
    } else if (lhs instanceof DotExpression dot) {
      return new AugAssignDotNode(
          compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation(), op, rhs, opLoc);
    }
    throw new Unsupported("augmented assignment target: " + lhs.kind());
  }

  private static Resolver.Binding assignableBinding(Identifier id) {
    Resolver.Binding bind = id.getBinding();
    switch (bind.getScope()) {
      case LOCAL:
      case GLOBAL:
        return bind;
      default:
        throw new Unsupported("assignment to " + bind.getScope() + " variable");
    }
  }

  private static SlotExpr compileExpr(Expression e) {
    if (e instanceof IntLiteral lit) {
      Number n = lit.getValue();
      StarlarkInt v =
          n instanceof Integer i
              ? StarlarkInt.of(i)
              : n instanceof Long l ? StarlarkInt.of(l) : StarlarkInt.of((BigInteger) n);
      return new ConstNode(v);
    } else if (e instanceof StringLiteral lit) {
      return new ConstNode(lit.getValue());
    } else if (e instanceof FloatLiteral lit) {
      return new ConstNode(StarlarkFloat.of(lit.getValue()));
    } else if (e instanceof Identifier id) {
      return new IdentifierNode(id);
    } else if (e instanceof BinaryOperatorExpression bin) {
      return new BinaryNode(
          bin.getOperator(),
          bin.getOperatorLocation(),
          compileExpr(bin.getX()),
          compileExpr(bin.getY()));
    } else if (e instanceof UnaryOperatorExpression un) {
      return new UnaryNode(un.getOperator(), un.getStartLocation(), compileExpr(un.getX()));
    } else if (e instanceof ConditionalExpression cond) {
      return new CondNode(
          compileExpr(cond.getCondition()),
          compileExpr(cond.getThenCase()),
          compileExpr(cond.getElseCase()));
    } else if (e instanceof IndexExpression index) {
      return new IndexNode(
          compileExpr(index.getObject()), compileExpr(index.getKey()), index.getLbracketLocation());
    } else if (e instanceof DotExpression dot) {
      return new DotNode(compileExpr(dot.getObject()), dot.getField().getName(), dot.getDotLocation());
    } else if (e instanceof ListExpression list) {
      SlotExpr[] elems = new SlotExpr[list.getElements().size()];
      for (int i = 0; i < elems.length; i++) {
        elems[i] = compileExpr(list.getElements().get(i));
      }
      return new ListNode(elems, list.isTuple());
    } else if (e instanceof DictExpression dict) {
      List<DictExpression.Entry> entries = dict.getEntries();
      SlotExpr[] keys = new SlotExpr[entries.size()];
      SlotExpr[] values = new SlotExpr[entries.size()];
      Location[] colons = new Location[entries.size()];
      for (int i = 0; i < entries.size(); i++) {
        keys[i] = compileExpr(entries.get(i).getKey());
        values[i] = compileExpr(entries.get(i).getValue());
        colons[i] = entries.get(i).getColonLocation();
      }
      return new DictNode(keys, values, colons);
    } else if (e instanceof CallExpression call) {
      if (!(call.getFunction() instanceof Identifier)) {
        throw new Unsupported("call to non-identifier");
      }
      List<Argument> args = call.getArguments();
      SlotExpr[] argNodes = new SlotExpr[args.size()];
      for (int i = 0; i < args.size(); i++) {
        Argument a = args.get(i);
        if (!(a instanceof Argument.Positional)) {
          throw new Unsupported("non-positional argument");
        }
        argNodes[i] = compileExpr(a.getValue());
      }
      return new CallNode(compileExpr(call.getFunction()), argNodes);
    }
    throw new Unsupported("expression: " + e.kind());
  }

  /** Executes a straight-line block; {@code return} unwinds past it via {@link SlotReturnException}. */
  @ExplodeLoop
  private static TokenKind execBlock(SlotStmt[] body, VirtualFrame vf)
      throws EvalException, InterruptedException {
    for (SlotStmt s : body) {
      TokenKind flow = s.exec(vf);
      if (flow != TokenKind.PASS) {
        return flow; // BREAK or CONTINUE
      }
    }
    return TokenKind.PASS;
  }

  /** Reads an identifier; mirrors {@link Eval}'s {@code evalIdentifier} but locals come from slots. */
  private static Object readName(VirtualFrame vf, Resolver.Binding bind, String name, Location loc)
      throws EvalException {
    Object result;
    switch (bind.getScope()) {
      case LOCAL -> result = vf.getObject(bind.getIndex());
      case FREE -> result = ((StarlarkFunction) frameOf(vf).fn).getFreeVar(bind.getIndex()).x;
      case GLOBAL -> result = ((StarlarkFunction) frameOf(vf).fn).getGlobal(bind.getIndex());
      case PREDECLARED -> result = ((StarlarkFunction) frameOf(vf).fn).getModule().getPredeclared(name);
      case UNIVERSAL -> result = Starlark.UNIVERSE.get(name);
      default -> throw new Unsupported("read of " + bind.getScope() + " variable");
    }
    if (result == null) {
      frameOf(vf).setErrorLocation(loc);
      throw Starlark.errorf(
          "%s variable '%s' is referenced before assignment.", bind.getScope(), name);
    }
    return result;
  }

  /** Assigns an identifier; mirrors {@link Eval}'s {@code assignIdentifier} but locals are slots. */
  private static void assignName(VirtualFrame vf, Resolver.Binding bind, Object value) {
    switch (bind.getScope()) {
      case LOCAL -> vf.setObject(bind.getIndex(), value);
      case GLOBAL -> {
        StarlarkFunction fn = (StarlarkFunction) frameOf(vf).fn;
        fn.setGlobal(bind.getIndex(), value);
        TypeTable typeTable = fn.getTypeTable();
        if (typeTable != null) {
          fn.setGlobalDeclaredType(bind.getIndex(), typeTable.getGlobalDeclaredType(bind));
        }
      }
      default -> throw new IllegalStateException(bind.getScope().toString());
    }
  }

  /** Fast path for {@code int <op> int}, identical to the StarlarkInt cases in EvalUtils.binaryOp. */
  private static StarlarkInt fastIntArith(TokenKind op, StarlarkInt x, StarlarkInt y)
      throws EvalException {
    return switch (op) {
      case PLUS -> StarlarkInt.add(x, y);
      case MINUS -> StarlarkInt.subtract(x, y);
      case STAR -> StarlarkInt.multiply(x, y);
      case PERCENT -> StarlarkInt.mod(x, y);
      case AMPERSAND -> StarlarkInt.and(x, y);
      case PIPE -> StarlarkInt.or(x, y);
      case CARET -> StarlarkInt.xor(x, y);
      default -> null;
    };
  }

  /** Mirrors {@link Eval}'s {@code inplaceBinaryOp} (in-place {@code +=} etc. for collections). */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object inplaceBinaryOp(StarlarkThread.Frame fr, TokenKind op, Object x, Object y)
      throws EvalException {
    switch (op) {
      case PLUS:
        if (x instanceof StarlarkList<?> xList && y instanceof StarlarkList<?> yList) {
          xList.extend((StarlarkIterable) yList);
          return xList;
        }
        break;
      case PIPE:
        if (x instanceof Dict && y instanceof Map) {
          Dict<Object, Object> xDict = (Dict<Object, Object>) x;
          xDict.putEntries((Map<Object, Object>) y);
          return xDict;
        } else if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.update(Tuple.of(ySet));
          return xSet;
        }
        break;
      case AMPERSAND:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.intersectionUpdate(Tuple.of(ySet));
          return xSet;
        }
        break;
      case CARET:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.symmetricDifferenceUpdate(ySet);
          return xSet;
        }
        break;
      case MINUS:
        if (x instanceof StarlarkSet<?> xSet && y instanceof Set<?> ySet) {
          xSet.differenceUpdate(Tuple.of(ySet));
          return xSet;
        }
        break;
      default: // fall through
    }
    return EvalUtils.binaryOp(op, x, y, fr.thread);
  }

  // ---- @TruffleBoundary wrappers: calls into the broader Starlark runtime that partial evaluation
  // must NOT inline (otherwise it tries to inline the whole interpreter, "too deep / recursive").
  // The fast integer arithmetic above is intentionally NOT behind a boundary so it gets compiled. ----

  @TruffleBoundary
  private static StarlarkCallable asCallable(StarlarkThread thread, Object fn) throws EvalException {
    return Starlark.getStarlarkCallable(thread, fn);
  }

  @TruffleBoundary
  private static Object call(StarlarkThread thread, StarlarkCallable callable, Object[] args)
      throws EvalException, InterruptedException {
    return Starlark.positionalOnlyCall(thread, callable, args);
  }

  @TruffleBoundary
  private static Object binaryOpBoundary(TokenKind op, Object x, Object y, StarlarkThread thread)
      throws EvalException {
    return EvalUtils.binaryOp(op, x, y, thread);
  }

  @TruffleBoundary
  private static Object unaryOpBoundary(TokenKind op, Object x) throws EvalException {
    return EvalUtils.unaryOp(op, x);
  }

  @TruffleBoundary
  private static Object inplaceBoundary(StarlarkThread.Frame fr, TokenKind op, Object x, Object y)
      throws EvalException {
    return inplaceBinaryOp(fr, op, x, y);
  }

  @TruffleBoundary
  private static Iterable<?> toIterableBoundary(Object o, StarlarkThread.Frame fr, Location loc)
      throws EvalException {
    try {
      return Starlark.toIterable(o);
    } catch (EvalException ex) {
      fr.setErrorLocation(loc);
      throw ex;
    }
  }

  @TruffleBoundary
  private static Iterator<?> beginIteration(Iterable<?> seq) {
    EvalUtils.addIterator(seq);
    return seq.iterator();
  }

  @TruffleBoundary
  private static void endIteration(Iterable<?> seq) {
    EvalUtils.removeIterator(seq);
  }

  @TruffleBoundary
  private static boolean iterHasNext(Iterator<?> it) {
    return it.hasNext();
  }

  @TruffleBoundary
  private static Object iterNext(Iterator<?> it) {
    return it.next();
  }

  @TruffleBoundary
  private static Object getattrBoundary(StarlarkThread thread, Object o, String field)
      throws EvalException, InterruptedException {
    return Starlark.getattr(thread, o, field, /* defaultValue= */ null);
  }

  @TruffleBoundary
  private static Object indexBoundary(StarlarkThread thread, Object o, Object k) throws EvalException {
    return EvalUtils.index(thread, o, k);
  }

  @TruffleBoundary
  private static void setIndexBoundary(Object o, Object k, Object v) throws EvalException {
    EvalUtils.setIndex(o, k, v);
  }

  @TruffleBoundary
  private static void setFieldBoundary(Object o, String field, Object v) throws EvalException {
    EvalUtils.setField(o, field, v);
  }

  // ---- Truffle nodes (operate over a VirtualFrame; locals in slots, Bazel frame in arguments[0]) ----

  private abstract static class SlotExpr extends Node {
    abstract Object exec(VirtualFrame vf) throws EvalException, InterruptedException;
  }

  private abstract static class SlotStmt extends Node {
    /** Returns PASS, BREAK, or CONTINUE; {@code return} unwinds via {@link SlotReturnException}. */
    abstract TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException;
  }

  private static final class ConstNode extends SlotExpr {
    private final Object value;

    ConstNode(Object value) {
      this.value = value;
    }

    @Override
    Object exec(VirtualFrame vf) {
      return value;
    }
  }

  private static final class IdentifierNode extends SlotExpr {
    private final Resolver.Binding bind;
    private final String name;
    private final Location loc;

    IdentifierNode(Identifier id) {
      this.bind = id.getBinding();
      this.name = id.getName();
      this.loc = id.getStartLocation();
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException {
      return readName(vf, bind, name, loc);
    }
  }

  private static final class BinaryNode extends SlotExpr {
    private final TokenKind op;
    private final Location opLoc;
    @Child private SlotExpr left;
    @Child private SlotExpr right;

    BinaryNode(TokenKind op, Location opLoc, SlotExpr left, SlotExpr right) {
      this.op = op;
      this.opLoc = opLoc;
      this.left = left;
      this.right = right;
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object x = left.exec(vf);
      switch (op) {
        case AND:
          return Starlark.truth(x) ? right.exec(vf) : x;
        case OR:
          return Starlark.truth(x) ? x : right.exec(vf);
        default:
          Object y = right.exec(vf);
          try {
            if (x instanceof StarlarkInt xi && y instanceof StarlarkInt yi) {
              StarlarkInt fast = fastIntArith(op, xi, yi);
              if (fast != null) {
                return fast;
              }
            }
            return binaryOpBoundary(op, x, y, frameOf(vf).thread);
          } catch (EvalException ex) {
            frameOf(vf).setErrorLocation(opLoc);
            throw ex;
          }
      }
    }
  }

  private static final class UnaryNode extends SlotExpr {
    private final TokenKind op;
    private final Location loc;
    @Child private SlotExpr operand;

    UnaryNode(TokenKind op, Location loc, SlotExpr operand) {
      this.op = op;
      this.loc = loc;
      this.operand = operand;
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object x = operand.exec(vf);
      try {
        return unaryOpBoundary(op, x);
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(loc);
        throw ex;
      }
    }
  }

  private static final class CondNode extends SlotExpr {
    @Child private SlotExpr cond;
    @Child private SlotExpr thenCase;
    @Child private SlotExpr elseCase;

    CondNode(SlotExpr cond, SlotExpr thenCase, SlotExpr elseCase) {
      this.cond = cond;
      this.thenCase = thenCase;
      this.elseCase = elseCase;
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      return Starlark.truth(cond.exec(vf)) ? thenCase.exec(vf) : elseCase.exec(vf);
    }
  }

  private static final class IndexNode extends SlotExpr {
    @Child private SlotExpr object;
    @Child private SlotExpr key;
    private final Location loc;

    IndexNode(SlotExpr object, SlotExpr key, Location loc) {
      this.object = object;
      this.key = key;
      this.loc = loc;
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object o = object.exec(vf);
      Object k = key.exec(vf);
      try {
        return indexBoundary(frameOf(vf).thread, o, k);
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(loc);
        throw ex;
      }
    }
  }

  private static final class DotNode extends SlotExpr {
    @Child private SlotExpr object;
    private final String field;
    private final Location loc;

    DotNode(SlotExpr object, String field, Location loc) {
      this.object = object;
      this.field = field;
      this.loc = loc;
    }

    @Override
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object o = object.exec(vf);
      try {
        return getattrBoundary(frameOf(vf).thread, o, field);
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(loc);
        throw ex;
      }
    }
  }

  private static final class ListNode extends SlotExpr {
    @Children private final SlotExpr[] elements;
    private final boolean tuple;

    ListNode(SlotExpr[] elements, boolean tuple) {
      this.elements = elements;
      this.tuple = tuple;
    }

    @Override
    @ExplodeLoop
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object[] array = new Object[elements.length];
      for (int i = 0; i < elements.length; i++) {
        array[i] = elements[i].exec(vf);
      }
      return tuple ? Tuple.wrap(array) : StarlarkList.wrap(frameOf(vf).thread.mutability(), array);
    }
  }

  private static final class DictNode extends SlotExpr {
    @Children private final SlotExpr[] keys;
    @Children private final SlotExpr[] values;
    private final Location[] colons;

    DictNode(SlotExpr[] keys, SlotExpr[] values, Location[] colons) {
      this.keys = keys;
      this.values = values;
      this.colons = colons;
    }

    @Override
    @ExplodeLoop
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread.Frame fr = frameOf(vf);
      LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
      for (int i = 0; i < keys.length; i++) {
        Object k = keys[i].exec(vf);
        Object v = values[i].exec(vf);
        try {
          Starlark.checkHashable(k);
        } catch (EvalException ex) {
          fr.setErrorLocation(colons[i]);
          throw ex;
        }
        if (map.put(k, v) != null) {
          fr.setErrorLocation(colons[i]);
          throw Starlark.errorf(
              "dictionary expression has duplicate key: %s",
              Starlark.repr(k, fr.thread.getSemantics()));
        }
      }
      Mutability mu = fr.thread.mutability();
      return mu.isFrozen() ? CompactImmutableDict.copyOf(map) : Dict.wrap(mu, map);
    }
  }

  private static final class CallNode extends SlotExpr {
    @Child private SlotExpr fn;
    @Children private final SlotExpr[] args;

    CallNode(SlotExpr fn, SlotExpr[] args) {
      this.fn = fn;
      this.args = args;
    }

    @Override
    @ExplodeLoop
    Object exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread thread = frameOf(vf).thread;
      StarlarkCallable callable = asCallable(thread, fn.exec(vf));
      Object[] values = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].exec(vf);
      }
      return call(thread, callable, values);
    }
  }

  private static final class ReturnNode extends SlotStmt {
    @Child private SlotExpr result;

    ReturnNode(SlotExpr result) {
      this.result = result;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      throw new SlotReturnException(result == null ? Starlark.NONE : result.exec(vf));
    }
  }

  private static final class ExprStmtNode extends SlotStmt {
    @Child private SlotExpr expr;

    ExprStmtNode(SlotExpr expr) {
      this.expr = expr;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      expr.exec(vf);
      return TokenKind.PASS;
    }
  }

  private static final class FlowNode extends SlotStmt {
    private final TokenKind kind;

    FlowNode(TokenKind kind) {
      this.kind = kind;
    }

    @Override
    TokenKind exec(VirtualFrame vf) {
      return kind;
    }
  }

  private static final class IfNode extends SlotStmt {
    @Child private SlotExpr cond;
    @Children private final SlotStmt[] thenBlock;
    @Children private final SlotStmt[] elseBlock; // may be null

    IfNode(SlotExpr cond, SlotStmt[] thenBlock, SlotStmt[] elseBlock) {
      this.cond = cond;
      this.thenBlock = thenBlock;
      this.elseBlock = elseBlock;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      if (Starlark.truth(cond.exec(vf))) {
        return execBlock(thenBlock, vf);
      } else if (elseBlock != null) {
        return execBlock(elseBlock, vf);
      }
      return TokenKind.PASS;
    }
  }

  /** {@code for} loop over a Truffle {@link LoopNode} so the frame stays virtual under PE. */
  private static final class ForNode extends SlotStmt {
    private final int iterSlot;
    @Child private SlotExpr collection;
    private final Location collectionLoc;
    @Child private LoopNode loop;

    ForNode(
        int loopVarSlot,
        int iterSlot,
        SlotExpr collection,
        Location collectionLoc,
        Location forLoc,
        SlotStmt[] body) {
      this.iterSlot = iterSlot;
      this.collection = collection;
      this.collectionLoc = collectionLoc;
      this.loop =
          Truffle.getRuntime().createLoopNode(new ForRepeatingNode(loopVarSlot, iterSlot, forLoc, body));
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread.Frame fr = frameOf(vf);
      Iterable<?> seq = toIterableBoundary(collection.exec(vf), fr, collectionLoc);
      vf.setObject(iterSlot, beginIteration(seq));
      try {
        loop.execute(vf);
      } catch (HostException e) {
        throw e.rethrow();
      } finally {
        endIteration(seq);
      }
      return TokenKind.PASS;
    }
  }

  private static final class ForRepeatingNode extends Node implements RepeatingNode {
    private final int loopVarSlot;
    private final int iterSlot;
    private final Location forLoc;
    @Children private final SlotStmt[] body;

    ForRepeatingNode(int loopVarSlot, int iterSlot, Location forLoc, SlotStmt[] body) {
      this.loopVarSlot = loopVarSlot;
      this.iterSlot = iterSlot;
      this.forLoc = forLoc;
      this.body = body;
    }

    @Override
    public boolean executeRepeating(VirtualFrame vf) {
      Iterator<?> it = (Iterator<?>) vf.getObject(iterSlot);
      if (!iterHasNext(it)) {
        return false;
      }
      vf.setObject(loopVarSlot, iterNext(it));
      try {
        // SlotReturnException (a ControlFlowException) propagates straight out to the RootNode.
        if (execBlock(body, vf) == TokenKind.BREAK) {
          return false;
        }
        frameOf(vf).thread.checkInterrupt();
        return true;
      } catch (EvalException e) {
        frameOf(vf).setErrorLocation(forLoc);
        throw new HostException(e);
      } catch (InterruptedException e) {
        throw new HostException(e);
      }
    }
  }

  private static final class AssignIdentNode extends SlotStmt {
    private final Resolver.Binding bind;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AssignIdentNode(Resolver.Binding bind, SlotExpr rhs, Location opLoc) {
      this.bind = bind;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      try {
        assignName(vf, bind, rhs.exec(vf));
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AssignIndexNode extends SlotStmt {
    @Child private SlotExpr object;
    @Child private SlotExpr key;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AssignIndexNode(SlotExpr object, SlotExpr key, SlotExpr rhs, Location opLoc) {
      this.object = object;
      this.key = key;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      try {
        Object value = rhs.exec(vf);
        setIndexBoundary(object.exec(vf), key.exec(vf), value);
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AssignDotNode extends SlotStmt {
    @Child private SlotExpr object;
    private final String field;
    private final Location dotLoc;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AssignDotNode(SlotExpr object, String field, Location dotLoc, SlotExpr rhs, Location opLoc) {
      this.object = object;
      this.field = field;
      this.dotLoc = dotLoc;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread.Frame fr = frameOf(vf);
      try {
        Object value = rhs.exec(vf);
        Object o = object.exec(vf);
        try {
          setFieldBoundary(o, field, value);
        } catch (EvalException ex) {
          fr.setErrorLocation(dotLoc);
          throw ex;
        }
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AugAssignIdentNode extends SlotStmt {
    private final Resolver.Binding bind;
    @Child private IdentifierNode read;
    private final TokenKind op;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AugAssignIdentNode(
        Resolver.Binding bind, IdentifierNode read, TokenKind op, SlotExpr rhs, Location opLoc) {
      this.bind = bind;
      this.read = read;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      Object x = read.exec(vf);
      Object y = rhs.exec(vf);
      Object z;
      try {
        z = inplaceBoundary(frameOf(vf), op, x, y);
      } catch (EvalException ex) {
        frameOf(vf).setErrorLocation(opLoc);
        throw ex;
      }
      assignName(vf, bind, z);
      return TokenKind.PASS;
    }
  }

  private static final class AugAssignIndexNode extends SlotStmt {
    @Child private SlotExpr object;
    @Child private SlotExpr key;
    private final TokenKind op;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AugAssignIndexNode(SlotExpr object, SlotExpr key, TokenKind op, SlotExpr rhs, Location opLoc) {
      this.object = object;
      this.key = key;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread.Frame fr = frameOf(vf);
      Object o = object.exec(vf);
      Object k = key.exec(vf);
      Object x = indexBoundary(fr.thread, o, k);
      Object y = rhs.exec(vf);
      Object z;
      try {
        z = inplaceBoundary(fr, op, x, y);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      try {
        setIndexBoundary(o, k, z);
      } catch (EvalException ex) {
        fr.setErrorLocation(opLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class AugAssignDotNode extends SlotStmt {
    @Child private SlotExpr object;
    private final String field;
    private final Location dotLoc;
    private final TokenKind op;
    @Child private SlotExpr rhs;
    private final Location opLoc;

    AugAssignDotNode(
        SlotExpr object, String field, Location dotLoc, TokenKind op, SlotExpr rhs, Location opLoc) {
      this.object = object;
      this.field = field;
      this.dotLoc = dotLoc;
      this.op = op;
      this.rhs = rhs;
      this.opLoc = opLoc;
    }

    @Override
    TokenKind exec(VirtualFrame vf) throws EvalException, InterruptedException {
      StarlarkThread.Frame fr = frameOf(vf);
      Object o = object.exec(vf);
      try {
        Object x = getattrBoundary(fr.thread, o, field);
        Object y = rhs.exec(vf);
        Object z;
        try {
          z = inplaceBoundary(fr, op, x, y);
        } catch (EvalException ex) {
          fr.setErrorLocation(opLoc);
          throw ex;
        }
        setFieldBoundary(o, field, z);
      } catch (EvalException ex) {
        fr.setErrorLocation(dotLoc);
        throw ex;
      }
      return TokenKind.PASS;
    }
  }

  private static final class BodyRootNode extends RootNode {
    private final int nLocals;
    @Children private final SlotStmt[] body;

    BodyRootNode(FrameDescriptor frameDescriptor, int nLocals, SlotStmt[] body) {
      super(null, frameDescriptor);
      this.nLocals = nLocals;
      this.body = body;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
      StarlarkThread.Frame fr = (StarlarkThread.Frame) frame.getArguments()[0];
      NATIVE_CALLS.incrementAndGet();
      // Copy the bound arguments (and the null body locals) from the Bazel frame into virtual slots.
      Object[] incoming = fr.locals;
      for (int i = 0; i < nLocals; i++) {
        frame.setObject(i, incoming[i]);
      }
      try {
        execBlock(body, frame);
        return Starlark.NONE; // reached the end with no return statement
      } catch (SlotReturnException r) {
        return r.value;
      } catch (EvalException | InterruptedException e) {
        throw new HostException(e);
      }
    }
  }

  /** Non-local exit for {@code return}; lets the value unwind through Truffle {@link LoopNode}s. */
  private static final class SlotReturnException extends ControlFlowException {
    final Object value;

    SlotReturnException(Object value) {
      this.value = value;
    }
  }

  /** Carries a checked Starlark exception across a Truffle boundary that can't declare it. */
  private static final class HostException extends RuntimeException {
    HostException(Throwable cause) {
      super(cause);
    }

    RuntimeException rethrow() throws EvalException, InterruptedException {
      Throwable cause = getCause();
      if (cause instanceof EvalException e) {
        throw e;
      }
      if (cause instanceof InterruptedException e) {
        throw e;
      }
      throw new IllegalStateException(cause);
    }
  }
}
