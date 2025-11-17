# Phase 7 tests: Builtin Functions

# len() with list
items = [1, 2, 3, 4, 5]
count = len(items)

---

# len() with string
text = "hello"
size = len(text)

---

# len() with dict
d = {"a": 1, "b": 2, "c": 3}
num_keys = len(d)

---

# len() with empty collections
empty_list = []
empty_string = ""
empty_dict = {}
l1 = len(empty_list)
l2 = len(empty_string)
l3 = len(empty_dict)

---

# str() conversion
x = 42
s = str(x)

---

# str() with boolean
b = True
text = str(b)

---

# int() conversion from string
s = "123"
n = int(s)

---

# int() with float
f = 3.14
n = int(f)

---

# type() function
x = 42
y = "hello"
z = [1, 2, 3]
d = {"a": 1}
t1 = type(x)
t2 = type(y)
t3 = type(z)
t4 = type(d)

---

# bool() conversion
x = bool(1)
y = bool(0)
z = bool("")
w = bool("text")
