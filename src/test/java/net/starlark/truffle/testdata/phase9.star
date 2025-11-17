# Phase 9 tests: List Comprehensions

# Basic list comprehension
numbers = [1, 2, 3, 4, 5]
squared = [x * x for x in numbers]

---

# List comprehension with filter
numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
evens = [x for x in numbers if x % 2 == 0]

---

# List comprehension with expression
items = [1, 2, 3]
doubled = [x * 2 for x in items]

---

# List comprehension with range
result = [x for x in range(5)]

---

# List comprehension with range and filter
result = [x for x in range(10) if x > 5]

---

# List comprehension with transformation and filter
numbers = [1, 2, 3, 4, 5]
result = [x * 2 for x in numbers if x % 2 == 1]

---

# Nested list comprehension (simple)
result = [x * y for x in [1, 2, 3] for y in [10, 20]]

---

# List comprehension with string
text = "hello"
chars = [c for c in text]

---

# List comprehension with multiple conditions
numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
result = [x for x in numbers if x > 3 if x < 8]

---

# List comprehension creating new list from dict keys
d = {"a": 1, "b": 2, "c": 3}
keys = [k for k in d]
