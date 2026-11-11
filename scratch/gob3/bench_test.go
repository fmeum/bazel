package gob3

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"testing"

	"gob3/cb3"

	zeebo "github.com/zeebo/blake3"
	luke "lukechampine.com/blake3"
)

var sizes = []int{1 << 10, 8 << 10, 64 << 10, 1 << 20, 16 << 20}

func data(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i % 251)
	}
	return b
}

// chunked mimics BuildBuddy's digest.Compute, which io.Copies files in 32 KiB writes.
func chunked(w interface{ Write([]byte) (int, error) }, d []byte) {
	for len(d) > 0 {
		n := min(len(d), 32<<10)
		w.Write(d[:n])
		d = d[n:]
	}
}

func TestAgree(t *testing.T) {
	for _, n := range append(sizes, 0, 1, 1025, 8193, 100000) {
		d := data(n)
		z := zeebo.Sum256(d)
		l := luke.Sum256(d)
		c := cb3.Sum(d)
		ch := cb3.New()
		chunked(ch, d)
		cc := ch.Sum()
		if z != l || z != c || z != cc {
			t.Fatalf("n=%d zeebo=%s luke=%s c=%s c-chunked=%s", n, hex.EncodeToString(z[:]),
				hex.EncodeToString(l[:]), hex.EncodeToString(c[:]), hex.EncodeToString(cc[:]))
		}
	}
}

func run(b *testing.B, name string, f func([]byte)) {
	for _, n := range sizes {
		d := data(n)
		b.Run(fmt.Sprintf("%s/%d", name, n), func(b *testing.B) {
			b.SetBytes(int64(n))
			for i := 0; i < b.N; i++ {
				f(d)
			}
		})
	}
}

func BenchmarkHash(b *testing.B) {
	zh := zeebo.New()
	lh := luke.New(32, nil)
	ch := cb3.New()
	run(b, "sha256", func(d []byte) { h := sha256.New(); chunked(h, d); h.Sum(nil) })
	run(b, "zeebo", func(d []byte) { zh.Reset(); chunked(zh, d); zh.Sum(nil) })
	run(b, "luke", func(d []byte) { lh.Reset(); chunked(lh, d); lh.Sum(nil) })
	run(b, "c", func(d []byte) { cb3.Sum(d) })
	run(b, "c32k", func(d []byte) { ch.Reset(); chunked(ch, d); ch.Sum() })
}
