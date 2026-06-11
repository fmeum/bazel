# Tests of int(x, [base])

# from number
assert_eq(int(0), 0)
assert_eq(int(42), 42)
assert_eq(int(123.456), 123)  # rounds toward zero
assert_eq(int(-1), -1)
assert_eq(int(2147483647), 2147483647)
assert_eq(int(-2147483647 - 1), -2147483648)
assert_eq(int(-2147483649), -2147483649)
assert_eq(int(1 << 100), 1 << 100)

# from bool
assert_eq(int(True), 1)
assert_eq(int(False), 0)

# from other
assert_fails(lambda: int(None), "got value of type 'NoneType', want 'string, bool, int, or float'")

# from string
assert_fails(lambda: int(""), "empty string")

# no base
assert_eq(int("0"), 0)
assert_eq(int("1"), 1)
assert_eq(int("42"), 42)
assert_eq(int("-1"), -1)
assert_eq(int("-1234"), -1234)
assert_eq(int("2147483647"), 2147483647)
assert_eq(int("-2147483648"), -2147483647 - 1)
assert_eq(int("123456789012345678901234567891234567890"), 123456789012345678901234567891234567890)
assert_eq(int("-123456789012345678901234567891234567890"), -123456789012345678901234567891234567890)
assert_eq(int("-0xabcdefabcdefabcdefabcdefabcdef", 0), -0xabcdefabcdefabcdefabcdefabcdef)
assert_eq(int("1111111111111", 2), 8191)
assert_eq(int("1111111111111", 5), 305175781)
assert_eq(int("1111111111111", 8), 78536544841)
assert_eq(int("1111111111111", 10), 1111111111111)
assert_eq(int("1111111111111", 16), 300239975158033)
assert_eq(int("1111111111111", 36), 4873763662273663093)
assert_eq(int("016"), 16)  # zero ok when base != 0.
assert_eq(int("+42"), 42)  # '+' prefix ok

# with base, no prefix
assert_eq(int("11", 2), 3)
assert_eq(int("11", 9), 10)
assert_eq(int("AF", 16), 175)
assert_eq(int("11", 36), 37)
assert_eq(int("az", 36), 395)
assert_eq(int("11", 10), 11)
assert_eq(int("11", 0), 11)

# base and prefix
assert_eq(int("0b11", 0), 3)
assert_eq(int("0B11", 2), 3)
assert_eq(int("0o11", 0), 9)
assert_eq(int("0O11", 8), 9)
assert_eq(int("0XFF", 0), 255)
assert_eq(int("0xFF", 16), 255)
assert_eq(int("0b11", 0), 3)
assert_eq(int("-0b11", 0), -3)
assert_eq(int("+0b11", 0), 3)
assert_eq(int("0B11", 2), 3)
assert_eq(int("0o11", 0), 9)
assert_eq(int("0O11", 8), 9)
assert_eq(int("-11", 2), -3)
assert_eq(int("016", 8), 14)
assert_eq(int("016", 16), 22)
assert_eq(int("0", 0), 0)
assert_eq(int("0x0b10", 16), 0x0b10)
assert_fails(lambda: int("0xFF", 8), 'invalid base-8 literal: "0xFF"')
assert_fails(lambda: int("016", 0), 'cannot infer base when string begins with a 0: "016"')
assert_fails(lambda: int("123", 3), 'invalid base-3 literal: "123"')
assert_fails(lambda: int("FF", 15), 'invalid base-15 literal: "FF"')
assert_fails(lambda: int("123", -1), "invalid base -1 .want 2 <= base <= 36")
assert_fails(lambda: int("123", 1), "invalid base 1 .want 2 <= base <= 36")
assert_fails(lambda: int("123", 37), "invalid base 37 .want 2 <= base <= 36")
assert_fails(lambda: int("123", "x"), "parameter 'base' got value of type 'string', want 'int'")
assert_fails(lambda: int(True, 2), "can't convert non-string with explicit base")
assert_fails(lambda: int(True, 10), "can't convert non-string with explicit base")
assert_fails(lambda: int(1, 2), "can't convert non-string with explicit base")

# Base prefix is honored only if base=0, or if the prefix matches the explicit base.
# See https://github.com/google/starlark-go/issues/337
assert_fails(lambda: int("0b0"), "invalid base-10 literal")
assert_eq(int("0b0", 0), 0)
assert_eq(int("0b0", 2), 0)
assert_eq(int("0b0", 16), 0xb0)
assert_eq(int("0x0b0", 16), 0xb0)
assert_eq(int("0x0b0", 0), 0xb0)
assert_eq(int("0x0b0101", 16), 0x0b0101)
assert_eq(int("0b0101", 2), 5)  # prefix is redundant with explicit base

# This case is allowed in Python but not Starlark
assert_fails(lambda: int(), "missing 1 required positional argument: x")

# Unlike Python, leading and trailing whitespace is not allowed. Use int(s.strip()).
assert_fails(lambda: int(" 1"), 'invalid base-10 literal: " 1"')
assert_fails(lambda: int("1 "), 'invalid base-10 literal: "1 "')
assert_fails(lambda: int("-"), 'invalid base-10 literal: "-"')
assert_fails(lambda: int("+"), 'invalid base-10 literal: "[+]"')
assert_fails(lambda: int("0x"), 'invalid base-10 literal: "0x"')
assert_fails(lambda: int("1.5"), 'invalid base-10 literal: "1.5"')
assert_fails(lambda: int("123.456"), 'invalid base-10 literal: "123.456"')
assert_fails(lambda: int("ab"), 'invalid base-10 literal: "ab"')
assert_fails(lambda: int("--1"), 'invalid base-10 literal: "--1"')
assert_fails(lambda: int("-0x-10", 16), 'invalid base-16 literal: "-0x-10"')

# PEP 515: a single '_' may separate digits or follow a base prefix; it is ignored.
assert_eq(int("1_000"), 1000)
assert_eq(int("-1_000_000"), -1000000)
assert_eq(int("+4_2"), 42)
assert_eq(int("0x_FF", 16), 255)
assert_eq(int("0xFF_FF", 0), 65535)
assert_eq(int("F_F", 16), 255)
assert_eq(int("7_7", 8), 63)
assert_eq(int("1_0", 0), 10)
assert_eq(int("1_2345_6789_0123_4567_8901_2345_6789_1234_5678_90"), 123456789012345678901234567891234567890)
assert_fails(lambda: int("_"), 'invalid underscore placement in int literal: "_"')
assert_fails(lambda: int("_1"), 'invalid underscore placement in int literal: "_1"')
assert_fails(lambda: int("1_"), 'invalid underscore placement in int literal: "1_"')
assert_fails(lambda: int("1__0"), 'invalid underscore placement in int literal: "1__0"')
assert_fails(lambda: int("-_1"), 'invalid underscore placement in int literal: "-_1"')
assert_fails(lambda: int("+_1"), 'invalid underscore placement in int literal: "[+]_1"')
assert_fails(lambda: int("1_000.5"), 'invalid base-10 literal: "1_000.5"')
assert_fails(lambda: int("0x_FF"), 'invalid base-10 literal: "0x_FF"')  # 'x' is not a base-10 digit

# A '_' may not split a base prefix...
assert_fails(lambda: int("0_x1", 0), 'invalid underscore placement in int literal: "0_x1"')
assert_fails(lambda: int("0_x1", 16), 'invalid underscore placement in int literal: "0_x1"')
assert_fails(lambda: int("0_b1", 0), 'invalid underscore placement in int literal: "0_b1"')
assert_fails(lambda: int("-0_o7", 8), 'invalid underscore placement in int literal: "-0_o7"')

# ... but the same '_' is fine if the would-be prefix letter is an ordinary digit in the base.
assert_eq(int("0_b1", 16), 0xb1)
assert_eq(int("0_x1", 36), 33 * 36 + 1)
