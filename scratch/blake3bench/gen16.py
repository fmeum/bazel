import re, sys
p = sys.argv[1] + '/blake3_neon.c'
s = open(p).read()
start = s.index('INLINE void round_fn4(')
end = s.index('\ntypedef struct { uint32x4_t a, b; } u32x8;')
rf16 = s[start:end].replace('round_fn4', 'round_fn16').replace('uint32x4_t', 'u32x16')
rf16 = rf16.replace('add_128', 'add16').replace('xor_128', 'xor16')
rf16 = re.sub(r'rot(\d+)_128', r'rot\1_16', rf16)
def op(name, expr):
    return f"INLINE u32x16 {name}(u32x16 x, u32x16 y) {{ u32x16 r = {{{{{', '.join(expr.format(i=i) for i in range(4))}}}}}; return r; }}\n"
helpers = 'typedef struct { uint32x4_t v[4]; } u32x16;\n'
helpers += op('add16', 'vaddq_u32(x.v[{i}], y.v[{i}])') + op('xor16', 'veorq_u32(x.v[{i}], y.v[{i}])')
for r in (16, 12, 8, 7):
    helpers += f"INLINE u32x16 rot{r}_16(u32x16 x) {{ u32x16 r = {{{{{', '.join(f'rot{r}_128(x.v[{i}])' for i in range(4))}}}}}; return r; }}\n"
helpers += "INLINE u32x16 set1_16(uint32_t x) { u32x16 r = {{set1_128(x), set1_128(x), set1_128(x), set1_128(x)}}; return r; }\n"
ins = s.index('INLINE void transpose_vecs_128(')
s = s[:ins] + helpers + rf16 + s[ins:]
hash16 = open(sys.argv[2]).read()
marker = '/*\n * ----------------------------------------------------------------------------\n * hash_many_neon'
s = s.replace(marker, hash16 + marker, 1)
s = s.replace('''#ifndef NEON4_ONLY
  while (num_inputs >= 8) {''', '''#ifndef NEON4_ONLY
  while (num_inputs >= 16) {
    blake3_hash16_neon(inputs, blocks, key, counter, increment_counter, flags,
                       flags_start, flags_end, out);
    if (increment_counter) {
      counter += 16;
    }
    inputs += 16;
    num_inputs -= 16;
    out = &out[16 * BLAKE3_OUT_LEN];
  }
  while (num_inputs >= 8) {''')
open(p, 'w').write(s)
p = sys.argv[1] + '/blake3_impl.h'
s = open(p).read()
s = s.replace('#define MAX_SIMD_DEGREE 8', '#define MAX_SIMD_DEGREE 16', 1)
open(p, 'w').write(s)
