#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include "blake3.h"
int main(void) {
  size_t max = 3 << 20;
  uint8_t *buf = malloc(max);
  for (size_t i = 0; i < max; i++) buf[i] = (uint8_t)((i * 2654435761u) >> 13);
  size_t lens[] = {0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 3072, 4096, 5000, 7168, 8192, 8193,
                   9216, 12288, 16384, 16385, 31744, 32768, 65536, 100000, 131072, 1 << 20,
                   (1 << 20) + 12345, 3 << 20};
  for (size_t i = 0; i < sizeof lens / sizeof lens[0]; i++) {
    for (size_t chunk = 0; chunk < 3; chunk++) {
      size_t step = chunk == 0 ? lens[i] + 1 : chunk == 1 ? 8192 : 1000;
      blake3_hasher h;
      blake3_hasher_init(&h);
      for (size_t off = 0; off < lens[i]; off += step)
        blake3_hasher_update(&h, buf + off, lens[i] - off < step ? lens[i] - off : step);
      uint8_t out[32];
      blake3_hasher_finalize(&h, out, 32);
      printf("%zu/%zu ", lens[i], chunk);
      for (int j = 0; j < 32; j++) printf("%02x", out[j]);
      printf("\n");
    }
  }
  return 0;
}
