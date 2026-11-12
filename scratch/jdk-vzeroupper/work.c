// Native side of the reproducer. Compile for baseline x86-64, without -mavx,
// so that the SSE2 intrinsics below use legacy SSE encoding.
#include <emmintrin.h>
#include <stdint.h>

// Copies 1 KiB with SSE2 loads and stores, then folds it into 32 bytes.
void sse_work(uint8_t *out, const uint8_t *in) {
  uint8_t buf[1024];
  for (int i = 0; i < 1024; i += 16) {
    _mm_storeu_si128((__m128i *)(buf + i),
                     _mm_loadu_si128((const __m128i *)(in + i)));
  }
  __m128i a = _mm_setzero_si128();
  __m128i b = _mm_setzero_si128();
  for (int i = 0; i < 1024; i += 32) {
    a = _mm_xor_si128(a, _mm_loadu_si128((const __m128i *)(buf + i)));
    b = _mm_add_epi32(b, _mm_loadu_si128((const __m128i *)(buf + i + 16)));
  }
  _mm_storeu_si128((__m128i *)out, a);
  _mm_storeu_si128((__m128i *)(out + 16), b);
}

// Same, but clears the upper vector state first, emulating a downcall stub
// that executes vzeroupper before the call.
void sse_work_vzeroupper(uint8_t *out, const uint8_t *in) {
  __asm__ volatile("vzeroupper" ::: "memory");
  sse_work(out, in);
}

void noop(void) {}

void noop_vzeroupper(void) { __asm__ volatile("vzeroupper" ::: "memory"); }
