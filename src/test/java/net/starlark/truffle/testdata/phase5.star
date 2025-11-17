# Phase 5 tests: Basic functions

# Simple function with no parameters
def greet():
  return "hello"

result = greet()

---

# Function with parameters
def add(a, b):
  return a + b

sum = add(3, 5)

---

# Function that just returns a constant
def simple():
  return 42

result = simple()

---

# Function with expression
def compute(x):
  return (x * 2) + 10

value = compute(5)

---

# Function returning without value (implicit None)
def no_return(x):
  y = x * 2

result = no_return(5)
