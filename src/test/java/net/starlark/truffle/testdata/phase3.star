# Phase 3 tests: loops and iteration

# Simple for loop with range(n)
total = 0
for i in range(5):
  total = total + i

---

# For loop with range(start, stop)
sum = 0
for i in range(2, 6):
  sum = sum + i

---

# For loop with range(start, stop, step)
result = 0
for i in range(0, 10, 2):
  result = result + i

---

# For loop with break
count = 0
for i in range(10):
  if i == 5:
    break
  count = count + 1

---

# For loop with continue
skip_count = 0
for i in range(10):
  if i == 3:
    continue
  skip_count = skip_count + 1

---

# Nested for loops
nested_sum = 0
for i in range(3):
  for j in range(3):
    nested_sum = nested_sum + 1

---

# Using loop variable after loop
last = 0
for x in range(5):
  last = x
