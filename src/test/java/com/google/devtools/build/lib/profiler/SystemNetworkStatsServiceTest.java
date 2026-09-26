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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.profiler.SystemNetworkStatsService.NetIoCounter;
import com.google.devtools.build.lib.profiler.SystemNetworkStatsServiceImpl.NetIoCounterImpl;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SystemNetworkStatsServiceImpl}. */
@RunWith(JUnit4.class)
public class SystemNetworkStatsServiceTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void getNetIoCounters_doesNotCrash() throws IOException {
    new SystemNetworkStatsServiceImpl().getNetIoCounters();
  }

  @Test
  public void parseProcNetDev() {
    String content =
        """
        Inter-|   Receive                               |  Transmit
         face |bytes packets errs drop fifo frame compressed multicast|bytes packets ...
            lo: 6671441 5439 0 0 0 0 0 0 6671441 5439 0 0 0 0 0 0
          eth0: 98765432101 123456 1 2 3 4 5 6 1234567890 98765 7 8 9 10 11 12
        broken: 1 2 3
          eth1: 18446744073709551615 1 0 0 0 0 0 0 2 3 0 0 0 0 0 0
        """;
    Map<String, NetIoCounter> counters = new HashMap<>();

    SystemNetworkStatsServiceImpl.parseProcNetDev(content, counters);

    assertThat(counters)
        .containsExactly(
            "lo", NetIoCounterImpl.create(6671441, 6671441, 5439, 5439),
            "eth0", NetIoCounterImpl.create(1234567890, 98765432101L, 98765, 123456),
            "eth1", NetIoCounterImpl.create(2, -1, 3, 1));
  }
}
