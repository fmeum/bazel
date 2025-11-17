# Phase 6 tests: Dictionaries

# Empty dictionary
d = {}

---

# Dictionary with literals
d = {"a": 1, "b": 2}

---

# Dictionary access
d = {"x": 10, "y": 20}
value = d["x"]

---

# Dictionary assignment
d = {"a": 1}
d["b"] = 2

---

# Dictionary with mixed types
d = {"name": "Alice", "age": 30, "active": True}

---

# Nested dictionary access
d = {"outer": {"inner": 42}}
value = d["outer"]["inner"]

---

# Dictionary update
d = {"x": 1}
d["x"] = 10

---

# Dictionary with computed keys
key = "dynamic"
d = {key: 100}
value = d["dynamic"]

---

# Multiple operations
d = {}
d["a"] = 1
d["b"] = 2
d["c"] = 3
total = d["a"] + d["b"] + d["c"]
