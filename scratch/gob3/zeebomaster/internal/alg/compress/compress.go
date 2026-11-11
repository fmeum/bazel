package compress

import (
	"gob3/zeebomaster/internal/alg/compress/compress_avx512"
	"gob3/zeebomaster/internal/alg/compress/compress_pure"
	"gob3/zeebomaster/internal/alg/compress/compress_sse41"
	"gob3/zeebomaster/internal/consts"
)

func Compress(chain *[8]uint32, block *[16]uint32, counter uint64, blen uint32, flags uint32, out *[16]uint32) {
	if consts.HasAVX512 {
		compress_avx512.Compress(chain, block, counter, blen, flags, out)
	} else if consts.HasSSE41 {
		compress_sse41.Compress(chain, block, counter, blen, flags, out)
	} else {
		compress_pure.Compress(chain, block, counter, blen, flags, out)
	}
}
