// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.jni.JniLoader;
import com.google.devtools.build.lib.profiler.SystemNetworkStatsService.NetIoCounter;
import com.google.devtools.build.lib.skybridge.ScOnly;
import com.google.devtools.build.lib.util.OS;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Utility class for query system network stats. */
@ScOnly
public class SystemNetworkStatsServiceImpl implements SystemNetworkStatsService {
  static {
    JniLoader.loadJni();
  }

  public SystemNetworkStatsServiceImpl() {}

  @Override
  public Map<String, NetIoCounter> getNetIoCounters() throws IOException {
    HashMap<String, NetIoCounter> countersMap = new HashMap<>();
    switch (OS.getCurrent()) {
      case LINUX -> SystemNetworkStatsServiceImpl.getNetIoCountersLinux(countersMap);
      default -> SystemNetworkStatsServiceImpl.getNetIoCountersNative(countersMap);
    }
    return countersMap;
  }
  private static void getNetIoCountersLinux(Map<String, NetIoCounter> countersMap)
      throws IOException {
    parseProcNetDev(Files.readString(Paths.get("/proc/net/dev"), UTF_8), countersMap);
  }

  @VisibleForTesting
  static void parseProcNetDev(String content, Map<String, NetIoCounter> countersMap) {
    // Skip the table header (first 2 lines).
    int lineStart = 0;
    for (int i = 0; i < 2 && lineStart < content.length(); i++) {
      int lineEnd = content.indexOf('\n', lineStart);
      lineStart = lineEnd < 0 ? content.length() : lineEnd + 1;
    }
    long[] fields = new long[10];
    while (lineStart < content.length()) {
      int lineEnd = content.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = content.length();
      }
      int colonAt = content.indexOf(':', lineStart);
      if (colonAt >= 0 && colonAt < lineEnd) {
        int fieldCount = 0;
        int fieldStart = colonAt + 1;
        while (true) {
          while (fieldStart < lineEnd && Character.isWhitespace(content.charAt(fieldStart))) {
            fieldStart++;
          }
          if (fieldStart == lineEnd) {
            break;
          }
          int fieldEnd = fieldStart;
          while (fieldEnd < lineEnd && !Character.isWhitespace(content.charAt(fieldEnd))) {
            fieldEnd++;
          }
          long value = Long.parseUnsignedLong(content, fieldStart, fieldEnd, 10);
          if (fieldCount < fields.length) {
            fields[fieldCount] = value;
          }
          fieldCount++;
          fieldStart = fieldEnd;
        }
        if (fieldCount > 9) {
          long bytesRecv = fields[0];
          long packetsRecv = fields[1];
          long bytesSent = fields[8];
          long packetsSent = fields[9];
          countersMap.put(
              content.substring(lineStart, colonAt).strip(),
              NetIoCounterImpl.create(bytesSent, bytesRecv, packetsSent, packetsRecv));
        }
      }
      lineStart = lineEnd + 1;
    }
  }

  private static native void getNetIoCountersNative(Map<String, NetIoCounter> countersMap)
      throws IOException;

  /** Concrete implementation of {@link SystemNetworkStatsService.NetIoCounter} as a record. */
  public static record NetIoCounterImpl(
      long bytesSent, long bytesRecv, long packetsSent, long packetsRecv)
      implements SystemNetworkStatsService.NetIoCounter {
    public static SystemNetworkStatsService.NetIoCounter create(
        long bytesSent, long bytesRecv, long packetsSent, long packetsRecv) {
      return new NetIoCounterImpl(bytesSent, bytesRecv, packetsSent, packetsRecv);
    }
  }
}
