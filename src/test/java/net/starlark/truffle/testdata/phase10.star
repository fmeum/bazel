# Phase 10 tests: elif chains and while loops

# elif chain - first condition
x = 5
if x < 3:
  result = "small"
elif x < 7:
  result = "medium"
else:
  result = "large"

---

# elif chain - second condition
x = 8
if x < 3:
  result = "small"
elif x < 7:
  result = "medium"
else:
  result = "large"

---

# elif chain - else condition
x = 10
if x < 3:
  result = "small"
elif x < 7:
  result = "medium"
else:
  result = "large"

---

# Multiple elif
x = 15
if x < 5:
  result = "a"
elif x < 10:
  result = "b"
elif x < 15:
  result = "c"
elif x < 20:
  result = "d"
else:
  result = "e"

---

# while loop - basic
count = 0
total = 0
while count < 5:
  total = total + count
  count = count + 1

---

# while loop with break
count = 0
while count < 100:
  if count == 5:
    break
  count = count + 1

---

# while loop with continue
total = 0
count = 0
while count < 10:
  count = count + 1
  if count % 2 == 0:
    continue
  total = total + count

---

# Nested while loops
i = 0
total = 0
while i < 3:
  j = 0
  while j < 3:
    total = total + 1
    j = j + 1
  i = i + 1

---

# while with complex condition
x = 10
y = 5
while x > 0 and y > 0:
  x = x - 1
  y = y - 1

---

# while True with break
count = 0
while True:
  count = count + 1
  if count >= 3:
    break
