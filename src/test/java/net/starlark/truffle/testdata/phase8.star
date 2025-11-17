# Phase 8 tests: String Operations & Slicing

# String slicing - basic
text = "hello"
sub = text[1:4]

---

# String slicing - from start
text = "hello"
sub = text[:3]

---

# String slicing - to end
text = "hello"
sub = text[2:]

---

# String slicing - full string
text = "hello"
sub = text[:]

---

# String slicing - negative indices
text = "hello"
sub = text[-3:]

---

# String slicing - negative start and end
text = "hello"
sub = text[-4:-1]

---

# List slicing - basic
items = [1, 2, 3, 4, 5]
sub = items[1:4]

---

# List slicing - from start
items = [1, 2, 3, 4, 5]
sub = items[:3]

---

# List slicing - to end
items = [1, 2, 3, 4, 5]
sub = items[2:]

---

# List slicing - negative indices
items = [1, 2, 3, 4, 5]
sub = items[-3:]

---

# String concatenation
s1 = "hello"
s2 = "world"
result = s1 + " " + s2

---

# String repetition
text = "abc"
repeated = text * 3
