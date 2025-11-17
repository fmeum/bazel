package net.starlark.truffle.values;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.HashMap;
import java.util.Map;

/** Starlark dictionary value. */
@ExportLibrary(InteropLibrary.class)
public final class DictValue implements TruffleObject {
  private final Map<Object, Object> map;

  public DictValue() {
    this.map = new HashMap<>();
  }

  public DictValue(Map<Object, Object> map) {
    this.map = new HashMap<>(map);
  }

  public Object get(Object key) {
    Object value = map.get(key);
    if (value == null && !map.containsKey(key)) {
      throw new RuntimeException("Key not found: " + key);
    }
    return value;
  }

  public void put(Object key, Object value) {
    map.put(key, value);
  }

  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }

  public int size() {
    return map.size();
  }

  public Map<Object, Object> getMap() {
    return map;
  }

  @ExportMessage
  boolean hasHashEntries() {
    return true;
  }

  @ExportMessage
  long getHashSize() {
    return map.size();
  }

  @ExportMessage
  Object readHashValue(Object key) {
    return get(key);
  }

  @ExportMessage
  void writeHashEntry(Object key, Object value) {
    put(key, value);
  }

  @ExportMessage
  boolean isHashEntryReadable(Object key) {
    return map.containsKey(key);
  }

  @ExportMessage
  boolean isHashEntryModifiable(Object key) {
    return map.containsKey(key);
  }

  @ExportMessage
  boolean isHashEntryInsertable(Object key) {
    return !map.containsKey(key);
  }

  @ExportMessage
  Object getHashEntriesIterator() {
    return map.keySet().iterator();
  }

  @ExportMessage
  Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
    return map.toString();
  }

  @Override
  public String toString() {
    return map.toString();
  }
}
