// Scratch program: measures how dirty upper vector register state affects the
// cost of hashing one byte with the BLAKE3 C library. x86_64 only.
#include <stdint.h>
#include <stdio.h>
#include <time.h>

#include "c/blake3.h"

#if defined(__x86_64__)

static double now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1e9 + ts.tv_nsec;
}

static uint8_t input[1] = {42};
static uint8_t output[32];

__attribute__((noinline)) static void hash_one_byte(void) {
  blake3_hasher hasher;
  blake3_hasher_init(&hasher);
  blake3_hasher_update(&hasher, input, 1);
  blake3_hasher_finalize(&hasher, output, sizeof(output));
}

// Each prelude runs right before every hash. No clobber lists, so that the
// compiler doesn't insert vzeroupper on its own; verify with objdump.
__attribute__((noinline)) static void nothing(void) {}
__attribute__((noinline)) static void dirty_ymm0(void) {
  __asm__ volatile("vpcmpeqd %ymm0, %ymm0, %ymm0");
}
__attribute__((noinline)) static void dirty_zmm0(void) {
  __asm__ volatile("vpternlogd $0xff, %zmm0, %zmm0, %zmm0");
}
__attribute__((noinline)) static void dirty_zmm16(void) {
  __asm__ volatile("vpternlogd $0xff, %zmm16, %zmm16, %zmm16");
}
__attribute__((noinline)) static void dirty_ymm0_vzeroupper(void) {
  __asm__ volatile("vpcmpeqd %ymm0, %ymm0, %ymm0\n\tvzeroupper");
}
__attribute__((noinline)) static void dirty_zmm0_vzeroupper(void) {
  __asm__ volatile("vpternlogd $0xff, %zmm0, %zmm0, %zmm0\n\tvzeroupper");
}
__attribute__((noinline)) static void dirty_zmm16_vzeroupper(void) {
  __asm__ volatile("vpternlogd $0xff, %zmm16, %zmm16, %zmm16\n\tvzeroupper");
}

static void run(const char *name, void (*prelude)(void)) {
  const int n = 5000000;
  for (int round = 0; round < 3; round++) {
    double start = now_ns();
    for (int i = 0; i < n; i++) {
      prelude();
      hash_one_byte();
    }
    double per_op = (now_ns() - start) / n;
    if (round > 0) {
      printf("%-26s round %d: %7.1f ns/op\n", name, round, per_op);
    }
  }
}

int main(void) {
  run("clean", nothing);
  run("dirty ymm0", dirty_ymm0);
  run("dirty zmm0", dirty_zmm0);
  run("dirty zmm16", dirty_zmm16);
  run("dirty ymm0 + vzeroupper", dirty_ymm0_vzeroupper);
  run("dirty zmm0 + vzeroupper", dirty_zmm0_vzeroupper);
  run("dirty zmm16 + vzeroupper", dirty_zmm16_vzeroupper);
  run("clean (again)", nothing);
  return 0;
}

#else

int main(void) {
  printf("x86_64 only\n");
  return 0;
}

#endif
