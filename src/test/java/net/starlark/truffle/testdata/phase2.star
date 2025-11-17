# Phase 2 tests: control flow and logical operators

# Logical operators - and
x = True and True
y = True and False
z = False and True

---

# Logical operators - or
a = True or False
b = False or True
c = False or False

---

# Logical operators - not
p = not True
q = not False

---

# Short-circuit evaluation - and returns first falsy or last value
r = 5 and 10
s = 0 and 10
t = 5 and 0

---

# Short-circuit evaluation - or returns first truthy or last value
u = 5 or 10
v = 0 or 10
w = 0 or False

---

# Simple if statement
if True:
  result = 1

---

# If with else
if False:
  result = 1
else:
  result = 2

---

# If with elif
if False:
  result = 1
elif True:
  result = 2
else:
  result = 3

---

# If with comparison
num = 10
if num > 5:
  status = "big"

---

# If with logical operators
flag1 = True
flag2 = False
if flag1 and not flag2:
  combined = 1

---

# Nested expressions in condition
val = 15
if val > 10 and val < 20:
  range_check = "in range"
