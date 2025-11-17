package net.starlark.truffle.values;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Starlark range object returned by range() builtin. */
@ExportLibrary(InteropLibrary.class)
public final class StarlarkRange implements TruffleObject {
  private final long start;
  private final long stop;
  private final long step;

  public StarlarkRange(long stop) {
    this(0, stop, 1);
  }

  public StarlarkRange(long start, long stop) {
    this(start, stop, 1);
  }

  public StarlarkRange(long start, long stop, long step) {
    if (step == 0) {
      throw new IllegalArgumentException("range() step argument must not be zero");
    }
    this.start = start;
    this.stop = stop;
    this.step = step;
  }

  public long getStart() {
    return start;
  }

  public long getStop() {
    return stop;
  }

  public long getStep() {
    return step;
  }

  public long size() {
    if (step > 0) {
      return Math.max(0, (stop - start + step - 1) / step);
    } else {
      return Math.max(0, (start - stop - step - 1) / (-step));
    }
  }

  public long get(long index) {
    return start + index * step;
  }

  @ExportMessage
  boolean hasArrayElements() {
    return true;
  }

  @ExportMessage
  long getArraySize() {
    return size();
  }

  @ExportMessage
  boolean isArrayElementReadable(long index) {
    return index >= 0 && index < size();
  }

  @ExportMessage
  Object readArrayElement(long index) {
    if (!isArrayElementReadable(index)) {
      throw new ArrayIndexOutOfBoundsException((int) index);
    }
    return get(index);
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    return "range(" + start + ", " + stop + ", " + step + ")";
  }
}
