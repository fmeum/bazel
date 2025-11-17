package net.starlark.truffle.values;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.ArrayList;
import java.util.List;

/** Starlark list object. */
@ExportLibrary(InteropLibrary.class)
public final class StarlarkList implements TruffleObject {
  private final List<Object> elements;

  public StarlarkList() {
    this.elements = new ArrayList<>();
  }

  public StarlarkList(List<Object> elements) {
    this.elements = new ArrayList<>(elements);
  }

  public void append(Object value) {
    elements.add(value);
  }

  public Object get(int index) {
    if (index < 0) {
      index = elements.size() + index;  // Negative indexing
    }
    if (index < 0 || index >= elements.size()) {
      throw new IndexOutOfBoundsException("list index out of range: " + index);
    }
    return elements.get(index);
  }

  public void set(int index, Object value) {
    if (index < 0) {
      index = elements.size() + index;  // Negative indexing
    }
    if (index < 0 || index >= elements.size()) {
      throw new IndexOutOfBoundsException("list index out of range: " + index);
    }
    elements.set(index, value);
  }

  public int size() {
    return elements.size();
  }

  @ExportMessage
  boolean hasArrayElements() {
    return true;
  }

  @ExportMessage
  long getArraySize() {
    return elements.size();
  }

  @ExportMessage
  boolean isArrayElementReadable(long index) {
    return index >= 0 && index < elements.size();
  }

  @ExportMessage
  Object readArrayElement(long index) throws InvalidArrayIndexException {
    if (!isArrayElementReadable(index)) {
      throw InvalidArrayIndexException.create(index);
    }
    return elements.get((int) index);
  }

  @ExportMessage
  boolean isArrayElementModifiable(long index) {
    return index >= 0 && index < elements.size();
  }

  @ExportMessage
  boolean isArrayElementInsertable(long index) {
    return index == elements.size();
  }

  @ExportMessage
  void writeArrayElement(long index, Object value) throws InvalidArrayIndexException {
    if (index < 0 || index > elements.size()) {
      throw InvalidArrayIndexException.create(index);
    }
    if (index == elements.size()) {
      elements.add(value);
    } else {
      elements.set((int) index, value);
    }
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < elements.size(); i++) {
      if (i > 0) sb.append(", ");
      Object elem = elements.get(i);
      if (elem instanceof String) {
        sb.append('"').append(elem).append('"');
      } else {
        sb.append(elem);
      }
    }
    sb.append("]");
    return sb.toString();
  }

  @Override
  public String toString() {
    return toDisplayString(false).toString();
  }
}
