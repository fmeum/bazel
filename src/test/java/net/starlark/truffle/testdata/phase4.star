# Phase 4 tests: lists and indexing

# Empty list
empty = []

---

# List literal
nums = [1, 2, 3, 4, 5]

---

# List indexing
lst = [10, 20, 30]
first = lst[0]
second = lst[1]
third = lst[2]

---

# Negative indexing
items = [1, 2, 3]
last = items[-1]

---

# List assignment
values = [0, 0, 0]
values[0] = 10
values[1] = 20
values[2] = 30

---

# len() on list
data = [1, 2, 3, 4, 5]
size = len(data)

---

# len() on string
text = "hello"
length = len(text)

---

# List with different types
mixed = [1, "two", 3]

---

# Nested lists
nested = [[1, 2], [3, 4]]
inner = nested[0]
elem = nested[0][1]

---

# List in for loop
total = 0
for x in [1, 2, 3, 4, 5]:
  total = total + x

---

# Building a list
result = []
for i in range(5):
  result[i] = i * 2
