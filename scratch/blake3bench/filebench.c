#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <stdint.h>
#include "blake3.h"

static double now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static uint8_t buf[1 << 20];

static void hash_read(const char *p, size_t bufsize, uint8_t *out) {
  int fd = open(p, O_RDONLY);
  blake3_hasher h;
  blake3_hasher_init(&h);
  ssize_t n;
  while ((n = read(fd, buf, bufsize)) > 0) blake3_hasher_update(&h, buf, n);
  close(fd);
  blake3_hasher_finalize(&h, out, 32);
}

static void hash_mmap(const char *p, uint8_t *out) {
  int fd = open(p, O_RDONLY);
  struct stat st;
  fstat(fd, &st);
  blake3_hasher h;
  blake3_hasher_init(&h);
  if (st.st_size > 0) {
    void *m = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    blake3_hasher_update(&h, m, st.st_size);
    munmap(m, st.st_size);
  }
  close(fd);
  blake3_hasher_finalize(&h, out, 32);
}

int main(int argc, char **argv) {
  const char *dir = argv[1];
  size_t sizes[] = {100, 4096, 65536, 1 << 20, 64 << 20};
  uint8_t out[32];
  volatile uint8_t sink = 0;
  for (int s = 0; s < 5; s++) {
    char p[512];
    snprintf(p, sizeof p, "%s/f%zu", dir, sizes[s]);
    size_t n = sizes[s];
    const char *names[] = {"read8K", "read64K", "read256K", "read1M", "mmap"};
    size_t bs[] = {8192, 65536, 262144, 1 << 20, 0};
    for (int m = 0; m < 5; m++) {
      double best = 1e9;
      for (int rep = 0; rep < 5; rep++) {
        size_t iters = (size_t)(1e8 / (n + 20000)) + 1;
        double t0 = now();
        for (size_t it = 0; it < iters; it++) {
          if (bs[m]) hash_read(p, bs[m], out); else hash_mmap(p, out);
          sink ^= out[0];
        }
        double dt = (now() - t0) / iters;
        if (dt < best) best = dt;
      }
      printf("%-9zu %-9s %12.1f ns/op %6.2f GB/s\n", n, names[m], best * 1e9, n / best / 1e9);
    }
  }
  return sink == 42;
}
