// Package cb3 wraps a prebuilt BLAKE3 C library.
//
// Build with CGO_CFLAGS=-I<dir with blake3.h> and CGO_LDFLAGS=-L<dir with libblake3.a>.
package cb3

/*
#cgo CFLAGS: -O3
#cgo LDFLAGS: -lblake3
#include "blake3.h"

static void cb3_hash(const void *in, size_t len, uint8_t *out) {
  blake3_hasher h;
  blake3_hasher_init(&h);
  blake3_hasher_update(&h, in, len);
  blake3_hasher_finalize(&h, out, 32);
}
*/
import "C"
import "unsafe"

func Sum(b []byte) (out [32]byte) {
	C.cb3_hash(unsafe.Pointer(unsafe.SliceData(b)), C.size_t(len(b)), (*C.uint8_t)(&out[0]))
	return
}

// Hasher keeps the C hasher state in Go memory, which holds no Go pointers.
type Hasher struct{ state []uint64 }

func New() *Hasher {
	h := &Hasher{state: make([]uint64, (C.sizeof_blake3_hasher+7)/8)}
	h.Reset()
	return h
}

func (h *Hasher) p() *C.blake3_hasher { return (*C.blake3_hasher)(unsafe.Pointer(&h.state[0])) }

func (h *Hasher) Reset() { C.blake3_hasher_init(h.p()) }

func (h *Hasher) Write(b []byte) (int, error) {
	C.blake3_hasher_update(h.p(), unsafe.Pointer(unsafe.SliceData(b)), C.size_t(len(b)))
	return len(b), nil
}

func (h *Hasher) Sum() (out [32]byte) {
	C.blake3_hasher_finalize(h.p(), (*C.uint8_t)(&out[0]), 32)
	return
}
