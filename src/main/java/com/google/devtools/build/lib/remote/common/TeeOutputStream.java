// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common;

import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * An output stream that writes to a primary stream and mirrors every byte into a lazily created
 * file.
 *
 * <p>The mirror is best-effort: the first failure to write to it is recorded and stops any further
 * writes to it, but is never reported through the write methods, so that a failing mirror cannot
 * affect the primary stream. {@link #closeMirror} reports the failure instead.
 *
 * <p>Not thread-safe.
 */
public final class TeeOutputStream extends OutputStream {
  private final OutputStream primary;
  private final LazyFileOutputStream mirror;
  @Nullable private IOException mirrorFailure;
  private boolean mirrorClosed;

  public TeeOutputStream(OutputStream primary, LazyFileOutputStream mirror) {
    this.primary = primary;
    this.mirror = mirror;
  }

  @Override
  public void write(int b) throws IOException {
    primary.write(b);
    if (mirrorFailure == null) {
      try {
        mirror.write(b);
      } catch (IOException e) {
        recordMirrorFailure(e);
      }
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    primary.write(b, off, len);
    if (mirrorFailure == null) {
      try {
        mirror.write(b, off, len);
      } catch (IOException e) {
        recordMirrorFailure(e);
      }
    }
  }

  @Override
  public void flush() throws IOException {
    primary.flush();
    if (mirrorFailure == null) {
      try {
        mirror.flush();
      } catch (IOException e) {
        recordMirrorFailure(e);
      }
    }
  }

  /** Closes the primary stream and the mirror. A failure of the mirror is not reported. */
  @Override
  public void close() throws IOException {
    try {
      primary.close();
    } finally {
      var unused = closeMirror();
    }
  }

  /**
   * Closes the mirror, creating its file if no data has been written so far, and returns the
   * failure that prevented it from receiving every byte written to this stream, if any.
   *
   * <p>Only the first call has an effect; later calls return the same result.
   */
  @Nullable
  public IOException closeMirror() {
    if (mirrorClosed) {
      return mirrorFailure;
    }
    mirrorClosed = true;
    try {
      mirror.ensureOpen();
      mirror.close();
    } catch (IOException e) {
      mirrorFailure = e;
    }
    return mirrorFailure;
  }

  private void recordMirrorFailure(IOException e) {
    mirrorFailure = e;
    mirrorClosed = true;
    try {
      mirror.close();
    } catch (IOException closeFailure) {
      e.addSuppressed(closeFailure);
    }
  }
}
