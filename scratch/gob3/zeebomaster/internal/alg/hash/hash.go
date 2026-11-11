package hash

import (
	"gob3/zeebomaster/internal/alg/hash/hash_avx2"
	"gob3/zeebomaster/internal/alg/hash/hash_avx512"
	"gob3/zeebomaster/internal/alg/hash/hash_neon"
	"gob3/zeebomaster/internal/alg/hash/hash_pure"
	"gob3/zeebomaster/internal/alg/hash/hash_sve2"
	"gob3/zeebomaster/internal/consts"
)

func HashF(input *[8192]byte, length, counter uint64, flags uint32, key *[8]uint32, out *[64]uint32, chain *[8]uint32) {
	if consts.HasAVX512 && length > consts.ChunkLen {
		hash_avx512.HashF(input, length, counter, flags, key, out, chain)
	} else if consts.HasAVX2 && length > consts.ChunkLen {
		hash_avx2.HashF(input, length, counter, flags, key, out, chain)
	} else if consts.HasSVE2 && length > consts.ChunkLen {
		hash_sve2.HashF(input, length, counter, flags, key, out, chain)
	} else if consts.HasNEON && length > 2*consts.ChunkLen {
		hash_neon.HashF(input, length, counter, flags, key, out, chain)
	} else {
		hash_pure.HashF(input, length, counter, flags, key, out, chain)
	}
}

func HashP(left, right *[64]uint32, flags uint32, key *[8]uint32, out *[64]uint32, n int) {
	if consts.HasAVX512 && n >= 2 {
		hash_avx512.HashP(left, right, flags, key, out, n)
	} else if consts.HasAVX2 && n >= 2 {
		hash_avx2.HashP(left, right, flags, key, out, n)
	} else if consts.HasSVE2 && n >= 2 {
		hash_sve2.HashP(left, right, flags, key, out, n)
	} else if consts.HasNEON && n >= 2 {
		hash_neon.HashP(left, right, flags, key, out, n)
	} else {
		hash_pure.HashP(left, right, flags, key, out, n)
	}
}
