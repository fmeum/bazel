#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdint.h>
#include "blake3.h"

static double now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec + ts.tv_nsec * 1e-9;
}

int main(int argc, char **argv) {
  size_t sizes[] = {1, 64, 1024, 8192, 65536, 1 << 20, 16 << 20};
  size_t max = 16 << 20;
  uint8_t *buf = malloc(max);
  for (size_t i = 0; i < max; i++) buf[i] = (uint8_t)(i * 31 + 7);
  uint8_t out[32];
  volatile uint8_t sink = 0;
  for (int s = 0; s < sizeof(sizes) / sizeof(sizes[0]); s++) {
    size_t n = sizes[s];
    for (int mode = 0; mode < 2; mode++) {
      size_t chunk = mode == 0 ? n : 8192;
      if (mode == 1 && n <= 8192) continue;
      double best = 1e9;
      for (int rep = 0; rep < 5; rep++) {
        size_t iters = (size_t)(2e8 / (n + 200)) + 1;
        double t0 = now();
        for (size_t it = 0; it < iters; it++) {
          blake3_hasher h;
          blake3_hasher_init(&h);
          for (size_t off = 0; off < n; off += chunk)
            blake3_hasher_update(&h, buf + off, n - off < chunk ? n - off : chunk);
          blake3_hasher_finalize(&h, out, 32);
          sink ^= out[0];
        }
        double dt = (now() - t0) / iters;
        if (dt < best) best = dt;
      }
      printf("%-8zu %-9s %10.1f ns/op %8.2f GB/s\n", n, mode ? "chunk8K" : "oneshot",
             best * 1e9, n / best / 1e9);
    }
  }
  return sink == 42;
}
