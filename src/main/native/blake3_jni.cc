// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <cstdint>

#include "c/blake3.h"

namespace blaze_jni {

jbyte *get_byte_array(JNIEnv *env, jbyteArray java_array) {
  return (jbyte *)env->GetPrimitiveArrayCritical(java_array, nullptr);
}

void release_byte_array(JNIEnv *env, jbyteArray array, jbyte *addr) {
  env->ReleasePrimitiveArrayCritical(array, addr, 0);
}

extern "C" JNIEXPORT int JNICALL
Java_com_google_devtools_build_lib_vfs_bazel_Blake3MessageDigest_hasher_1size(
    JNIEnv *env, jobject obj) {
  return (int)sizeof(blake3_hasher);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_vfs_bazel_Blake3MessageDigest_initialize_1hasher(
    JNIEnv *env, jobject obj, jbyteArray jhasher) {
  blake3_hasher *hasher = (blake3_hasher *)get_byte_array(env, jhasher);
  if (hasher) {
    blake3_hasher_init(hasher);
    release_byte_array(env, jhasher, (jbyte *)hasher);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_vfs_bazel_Blake3MessageDigest_blake3_1hasher_1update(
    JNIEnv *env, jobject obj, jbyteArray jhasher, jbyteArray input, jint offset,
    jint input_len) {
  blake3_hasher *hasher = (blake3_hasher *)get_byte_array(env, jhasher);
  if (hasher) {
    jbyte *input_addr = get_byte_array(env, input);
    blake3_hasher_update(hasher, input_addr + offset, input_len);
    release_byte_array(env, input, input_addr);
    release_byte_array(env, jhasher, (jbyte *)hasher);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_vfs_bazel_Blake3MessageDigest_blake3_1hasher_1finalize(
    JNIEnv *env, jobject obj, jbyteArray jhasher, jbyteArray out,
    jint out_len) {
  blake3_hasher *hasher = (blake3_hasher *)get_byte_array(env, jhasher);
  if (hasher) {
    jbyte *out_addr = get_byte_array(env, out);
    blake3_hasher_finalize(hasher, (uint8_t *)out_addr, out_len);
    release_byte_array(env, out, out_addr);
    release_byte_array(env, jhasher, (jbyte *)hasher);
  }
}

}  // namespace blaze_jni

// ---------------------------------------------------------------------------
// Experimental entry points used by Blake3Experimental for benchmarking.
// ---------------------------------------------------------------------------

#ifndef _WIN32
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#endif

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_vfs_bazel_Blake3Experimental_hashOneShotJni(
    JNIEnv *env, jclass clazz, jbyteArray input, jint offset, jint len,
    jbyteArray out) {
  blake3_hasher hasher;
  blake3_hasher_init(&hasher);
  jbyte *in = (jbyte *)env->GetPrimitiveArrayCritical(input, nullptr);
  blake3_hasher_update(&hasher, in + offset, len);
  env->ReleasePrimitiveArrayCritical(input, in, JNI_ABORT);
  uint8_t digest[BLAKE3_OUT_LEN];
  blake3_hasher_finalize(&hasher, digest, BLAKE3_OUT_LEN);
  env->SetByteArrayRegion(out, 0, BLAKE3_OUT_LEN, (jbyte *)digest);
}

extern "C" {

JNIEXPORT int32_t blaze_blake3_hasher_size() {
  return static_cast<int32_t>(sizeof(blake3_hasher));
}

JNIEXPORT void blaze_blake3_hasher_init(void *hasher) {
  blake3_hasher_init(static_cast<blake3_hasher *>(hasher));
}

JNIEXPORT void blaze_blake3_hasher_update(void *hasher, const void *input,
                                          int64_t input_len) {
  blake3_hasher_update(static_cast<blake3_hasher *>(hasher), input,
                       static_cast<size_t>(input_len));
}

JNIEXPORT void blaze_blake3_hasher_finalize(void *hasher, void *out,
                                            int64_t out_len) {
  blake3_hasher_finalize(static_cast<blake3_hasher *>(hasher),
                         static_cast<uint8_t *>(out),
                         static_cast<size_t>(out_len));
}

JNIEXPORT void blaze_blake3_hash(const void *input, int64_t input_len,
                                 void *out) {
  blake3_hasher hasher;
  blake3_hasher_init(&hasher);
  blake3_hasher_update(&hasher, input, static_cast<size_t>(input_len));
  blake3_hasher_finalize(&hasher, static_cast<uint8_t *>(out), BLAKE3_OUT_LEN);
}

#ifndef _WIN32
// Returns 0 on success, errno otherwise.
JNIEXPORT int32_t blaze_blake3_hash_file(const char *path, void *out,
                                         int64_t buf_size) {
  int fd;
  do {
    fd = open(path, O_RDONLY | O_CLOEXEC);
  } while (fd < 0 && errno == EINTR);
  if (fd < 0) {
    return errno;
  }
  uint8_t *buf = static_cast<uint8_t *>(malloc(static_cast<size_t>(buf_size)));
  blake3_hasher hasher;
  blake3_hasher_init(&hasher);
  int32_t result = 0;
  for (;;) {
    ssize_t n = read(fd, buf, static_cast<size_t>(buf_size));
    if (n > 0) {
      blake3_hasher_update(&hasher, buf, static_cast<size_t>(n));
    } else if (n == 0) {
      break;
    } else if (errno != EINTR) {
      result = errno;
      break;
    }
  }
  free(buf);
  close(fd);
  if (result == 0) {
    blake3_hasher_finalize(&hasher, static_cast<uint8_t *>(out),
                           BLAKE3_OUT_LEN);
  }
  return result;
}
#endif

}  // extern "C"
