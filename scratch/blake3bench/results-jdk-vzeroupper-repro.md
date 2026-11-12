# JDK critical downcall vzeroupper reproducer results

Reproducer: `scratch/jdk-vzeroupper` at commit 24a1ef6ef64 (branch `blake3-x86-profile`), unmodified.

## Environment

- `uname -a`: Linux vm 6.18.44-fc-v378 #1 SMP PREEMPT_DYNAMIC @0 x86_64 x86_64 x86_64 GNU/Linux
- Compiler (`cc --version`): cc (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0
- JDK: installed with `apt-get install -y openjdk-25-jdk-headless` (available after `apt-get update`), `/usr/lib/jvm/java-25-openjdk-amd64/bin/java`

`java -version`:
```
openjdk version "25.0.5.0.3" 2026-10-24
OpenJDK Runtime Environment (build 25.0.5.0.3+1-1-24.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 25.0.5.0.3+1-1-24.04.1-Ubuntu, mixed mode, sharing)
```

`lscpu`:
```
Architecture:                            x86_64
CPU op-mode(s):                          32-bit, 64-bit
Address sizes:                           52 bits physical, 57 bits virtual
Byte Order:                              Little Endian
CPU(s):                                  4
On-line CPU(s) list:                     0-3
Vendor ID:                               GenuineIntel
Model name:                              Intel(R) Xeon(R) Processor @ 2.10GHz
CPU family:                              6
Model:                                   207
Thread(s) per core:                      1
Core(s) per socket:                      4
Socket(s):                               1
Stepping:                                2
BogoMIPS:                                4200.00
Flags:                                   fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ss ht syscall nx pdpe1gb rdtscp lm constant_tsc rep_good nopl xtopology nonstop_tsc cpuid tsc_known_freq pni pclmulqdq ssse3 fma cx16 pcid sse4_1 sse4_2 x2apic movbe popcnt tsc_deadline_timer aes xsave avx f16c rdrand hypervisor lahf_lm abm 3dnowprefetch cpuid_fault ssbd ibrs ibpb stibp ibrs_enhanced fsgsbase tsc_adjust bmi1 hle avx2 smep bmi2 erms invpcid rtm avx512f avx512dq rdseed adx smap avx512ifma clflushopt clwb avx512cd sha_ni avx512bw avx512vl xsaveopt xsavec xgetbv1 xsaves avx_vnni avx512_bf16 wbnoinvd arat avx512vbmi umip avx512_vbmi2 gfni vaes vpclmulqdq avx512_vnni avx512_bitalg avx512_vpopcntdq rdpid cldemote movdiri movdir64b fsrm md_clear serialize tsxldtrk amx_bf16 avx512_fp16 amx_tile amx_int8 arch_capabilities
Hypervisor vendor:                       KVM
Virtualization type:                     full
L1d cache:                               192 KiB (4 instances)
L1i cache:                               128 KiB (4 instances)
L2 cache:                                8 MiB (4 instances)
L3 cache:                                260 MiB (1 instance)
NUMA node(s):                            1
NUMA node0 CPU(s):                       0-3
Vulnerability Gather data sampling:      Not affected
Vulnerability Ghostwrite:                Not affected
Vulnerability Indirect target selection: Not affected
Vulnerability Itlb multihit:             Not affected
Vulnerability L1tf:                      Not affected
Vulnerability Mds:                       Not affected
Vulnerability Meltdown:                  Not affected
Vulnerability Mmio stale data:           Not affected
Vulnerability Old microcode:             Not affected
Vulnerability Reg file data sampling:    Not affected
Vulnerability Retbleed:                  Not affected
Vulnerability Spec rstack overflow:      Not affected
Vulnerability Spec store bypass:         Mitigation; Speculative Store Bypass disabled via prctl
Vulnerability Spectre v1:                Mitigation; usercopy/swapgs barriers and __user pointer sanitization
Vulnerability Spectre v2:                Mitigation; Enhanced / Automatic IBRS; IBPB conditional; PBRSB-eIBRS SW sequence; BHI Vulnerable
Vulnerability Srbds:                     Not affected
Vulnerability Tsa:                       Not affected
Vulnerability Tsx async abort:           Not affected
Vulnerability Vmscape:                   Not affected
```

## Disassembly check

The printed `sse_work` disassembly (identical in both runs) contains only legacy SSE instructions (pxor, movdqa, paddd, movups, plus rep movsq and scalar instructions). It contains no v-prefixed VEX instructions, so the check holds.

## Summary: median over the 4 printed rounds (ns/op)

Run 1:

| flags | critical: new byte[32], sse_work | critical: new byte[32], vzeroupper, sse_work | critical: noop | critical: vzeroupper, noop | regular: noop |
|---|---|---|---|---|---|
| (default flags) | 203.25 | 71.03 | 2.70 | 2.70 | 9.10 |
| -XX:UseAVX=2 | 34.84 | 39.09 | 2.54 | 2.61 | 8.21 |
| -XX:MaxVectorSize=16 | 50.11 | 37.30 | 2.75 | 2.95 | 8.96 |
| -XX:UseAVX=0 | 54.45 | 64.84 | 2.76 | 2.72 | 8.96 |

Run 2:

| flags | critical: new byte[32], sse_work | critical: new byte[32], vzeroupper, sse_work | critical: noop | critical: vzeroupper, noop | regular: noop |
|---|---|---|---|---|---|
| (default flags) | 216.27 | 40.01 | 3.04 | 2.83 | 9.61 |
| -XX:UseAVX=2 | 41.81 | 45.12 | 2.83 | 2.62 | 8.64 |
| -XX:MaxVectorSize=16 | 37.61 | 34.59 | 2.63 | 2.50 | 8.25 |
| -XX:UseAVX=0 | 40.46 | 35.98 | 2.55 | 2.65 | 8.30 |

## run.sh output, run 1 (verbatim)

```
Native code (sse_work must not contain VEX-encoded instructions):
0000000000001140 <sse_work>:
    1140:	endbr64
    1144:	sub    $0x418,%rsp
    114b:	mov    %rdi,%r8
    114e:	pxor   %xmm2,%xmm2
    1152:	mov    $0x80,%ecx
    1157:	mov    %fs:0x28,%rax
    1160:	mov    %rax,0x408(%rsp)
    1168:	xor    %eax,%eax
    116a:	mov    %rsp,%rdi
    116d:	mov    %rsp,%rax
    1170:	movdqa %xmm2,%xmm0
    1174:	rep movsq %ds:(%rsi),%es:(%rdi)
    1177:	lea    0x400(%rsp),%rdx
    117f:	nop
    1180:	movdqa 0x10(%rax),%xmm1
    1185:	pxor   (%rax),%xmm0
    1189:	add    $0x20,%rax
    118d:	paddd  %xmm2,%xmm1
    1191:	movdqa %xmm1,%xmm2
    1195:	cmp    %rax,%rdx
    1198:	jne    1180 <sse_work+0x40>
    119a:	movups %xmm0,(%r8)
    119e:	movups %xmm1,0x10(%r8)
    11a3:	mov    0x408(%rsp),%rax
    11ab:	sub    %fs:0x28,%rax
    11b4:	jne    11be <sse_work+0x7e>
    11b6:	add    $0x418,%rsp
    11bd:	ret
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
openjdk version "25.0.5.0.3" 2026-10-24
OpenJDK Runtime Environment (build 25.0.5.0.3+1-1-24.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 25.0.5.0.3+1-1-24.04.1-Ubuntu, mixed mode, sharing)

== java (default flags)
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                   203.53 ns/op
critical: new byte[32], vzeroupper, sse_work        37.55 ns/op
critical: noop                                       2.67 ns/op
critical: vzeroupper, noop                           3.70 ns/op
regular: noop                                       10.33 ns/op
-- round 2
critical: new byte[32], sse_work                   257.49 ns/op
critical: new byte[32], vzeroupper, sse_work       116.61 ns/op
critical: noop                                       2.54 ns/op
critical: vzeroupper, noop                           2.50 ns/op
regular: noop                                        8.52 ns/op
-- round 3
critical: new byte[32], sse_work                   202.96 ns/op
critical: new byte[32], vzeroupper, sse_work       104.52 ns/op
critical: noop                                       2.73 ns/op
critical: vzeroupper, noop                           2.57 ns/op
regular: noop                                        8.51 ns/op
-- round 4
critical: new byte[32], sse_work                   199.30 ns/op
critical: new byte[32], vzeroupper, sse_work        36.86 ns/op
critical: noop                                       3.08 ns/op
critical: vzeroupper, noop                           2.83 ns/op
regular: noop                                        9.68 ns/op
(sink 0)

== java -XX:UseAVX=2
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    44.62 ns/op
critical: new byte[32], vzeroupper, sse_work        45.55 ns/op
critical: noop                                       2.56 ns/op
critical: vzeroupper, noop                           3.46 ns/op
regular: noop                                        8.96 ns/op
-- round 2
critical: new byte[32], sse_work                    32.94 ns/op
critical: new byte[32], vzeroupper, sse_work        31.76 ns/op
critical: noop                                       2.50 ns/op
critical: vzeroupper, noop                           2.50 ns/op
regular: noop                                        8.19 ns/op
-- round 3
critical: new byte[32], sse_work                    32.89 ns/op
critical: new byte[32], vzeroupper, sse_work        45.21 ns/op
critical: noop                                       2.53 ns/op
critical: vzeroupper, noop                           2.53 ns/op
regular: noop                                        8.22 ns/op
-- round 4
critical: new byte[32], sse_work                    36.75 ns/op
critical: new byte[32], vzeroupper, sse_work        32.97 ns/op
critical: noop                                       2.55 ns/op
critical: vzeroupper, noop                           2.69 ns/op
regular: noop                                        8.20 ns/op
(sink 0)

== java -XX:MaxVectorSize=16
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    50.42 ns/op
critical: new byte[32], vzeroupper, sse_work        53.02 ns/op
critical: noop                                       2.86 ns/op
critical: vzeroupper, noop                           4.44 ns/op
regular: noop                                       10.11 ns/op
-- round 2
critical: new byte[32], sse_work                    49.80 ns/op
critical: new byte[32], vzeroupper, sse_work        34.68 ns/op
critical: noop                                       2.49 ns/op
critical: vzeroupper, noop                           3.13 ns/op
regular: noop                                        8.15 ns/op
-- round 3
critical: new byte[32], sse_work                    63.74 ns/op
critical: new byte[32], vzeroupper, sse_work        37.68 ns/op
critical: noop                                       2.86 ns/op
critical: vzeroupper, noop                           2.77 ns/op
regular: noop                                        8.95 ns/op
-- round 4
critical: new byte[32], sse_work                    38.69 ns/op
critical: new byte[32], vzeroupper, sse_work        36.93 ns/op
critical: noop                                       2.63 ns/op
critical: vzeroupper, noop                           2.67 ns/op
regular: noop                                        8.96 ns/op
(sink 0)

== java -XX:UseAVX=0
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    73.23 ns/op
critical: new byte[32], vzeroupper, sse_work        90.37 ns/op
critical: noop                                       3.98 ns/op
critical: vzeroupper, noop                           3.02 ns/op
regular: noop                                        9.61 ns/op
-- round 2
critical: new byte[32], sse_work                    40.49 ns/op
critical: new byte[32], vzeroupper, sse_work        39.31 ns/op
critical: noop                                       2.94 ns/op
critical: vzeroupper, noop                           2.87 ns/op
regular: noop                                        9.72 ns/op
-- round 3
critical: new byte[32], sse_work                    68.41 ns/op
critical: new byte[32], vzeroupper, sse_work        97.37 ns/op
critical: noop                                       2.58 ns/op
critical: vzeroupper, noop                           2.52 ns/op
regular: noop                                        8.25 ns/op
-- round 4
critical: new byte[32], sse_work                    34.67 ns/op
critical: new byte[32], vzeroupper, sse_work        35.47 ns/op
critical: noop                                       2.54 ns/op
critical: vzeroupper, noop                           2.57 ns/op
regular: noop                                        8.32 ns/op
(sink 0)
```

## run.sh output, run 2 (verbatim)

```
Native code (sse_work must not contain VEX-encoded instructions):
0000000000001140 <sse_work>:
    1140:	endbr64
    1144:	sub    $0x418,%rsp
    114b:	mov    %rdi,%r8
    114e:	pxor   %xmm2,%xmm2
    1152:	mov    $0x80,%ecx
    1157:	mov    %fs:0x28,%rax
    1160:	mov    %rax,0x408(%rsp)
    1168:	xor    %eax,%eax
    116a:	mov    %rsp,%rdi
    116d:	mov    %rsp,%rax
    1170:	movdqa %xmm2,%xmm0
    1174:	rep movsq %ds:(%rsi),%es:(%rdi)
    1177:	lea    0x400(%rsp),%rdx
    117f:	nop
    1180:	movdqa 0x10(%rax),%xmm1
    1185:	pxor   (%rax),%xmm0
    1189:	add    $0x20,%rax
    118d:	paddd  %xmm2,%xmm1
    1191:	movdqa %xmm1,%xmm2
    1195:	cmp    %rax,%rdx
    1198:	jne    1180 <sse_work+0x40>
    119a:	movups %xmm0,(%r8)
    119e:	movups %xmm1,0x10(%r8)
    11a3:	mov    0x408(%rsp),%rax
    11ab:	sub    %fs:0x28,%rax
    11b4:	jne    11be <sse_work+0x7e>
    11b6:	add    $0x418,%rsp
    11bd:	ret
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
openjdk version "25.0.5.0.3" 2026-10-24
OpenJDK Runtime Environment (build 25.0.5.0.3+1-1-24.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 25.0.5.0.3+1-1-24.04.1-Ubuntu, mixed mode, sharing)

== java (default flags)
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                   214.80 ns/op
critical: new byte[32], vzeroupper, sse_work        52.92 ns/op
critical: noop                                       3.90 ns/op
critical: vzeroupper, noop                           2.83 ns/op
regular: noop                                       10.47 ns/op
-- round 2
critical: new byte[32], sse_work                   346.89 ns/op
critical: new byte[32], vzeroupper, sse_work        37.67 ns/op
critical: noop                                       2.99 ns/op
critical: vzeroupper, noop                           2.84 ns/op
regular: noop                                        9.23 ns/op
-- round 3
critical: new byte[32], sse_work                   217.74 ns/op
critical: new byte[32], vzeroupper, sse_work        42.35 ns/op
critical: noop                                       2.91 ns/op
critical: vzeroupper, noop                           2.89 ns/op
regular: noop                                        9.98 ns/op
-- round 4
critical: new byte[32], sse_work                   213.79 ns/op
critical: new byte[32], vzeroupper, sse_work        35.51 ns/op
critical: noop                                       3.09 ns/op
critical: vzeroupper, noop                           2.49 ns/op
regular: noop                                        8.46 ns/op
(sink 0)

== java -XX:UseAVX=2
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    45.59 ns/op
critical: new byte[32], vzeroupper, sse_work        52.55 ns/op
critical: noop                                       3.94 ns/op
critical: vzeroupper, noop                           2.49 ns/op
regular: noop                                        8.21 ns/op
-- round 2
critical: new byte[32], sse_work                    33.45 ns/op
critical: new byte[32], vzeroupper, sse_work        36.79 ns/op
critical: noop                                       2.55 ns/op
critical: vzeroupper, noop                           2.60 ns/op
regular: noop                                        8.14 ns/op
-- round 3
critical: new byte[32], sse_work                    51.38 ns/op
critical: new byte[32], vzeroupper, sse_work        61.90 ns/op
critical: noop                                       2.91 ns/op
critical: vzeroupper, noop                           2.64 ns/op
regular: noop                                        9.08 ns/op
-- round 4
critical: new byte[32], sse_work                    38.04 ns/op
critical: new byte[32], vzeroupper, sse_work        37.69 ns/op
critical: noop                                       2.74 ns/op
critical: vzeroupper, noop                           2.98 ns/op
regular: noop                                        9.32 ns/op
(sink 0)

== java -XX:MaxVectorSize=16
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    50.21 ns/op
critical: new byte[32], vzeroupper, sse_work        34.26 ns/op
critical: noop                                       2.83 ns/op
critical: vzeroupper, noop                           2.52 ns/op
regular: noop                                        8.32 ns/op
-- round 2
critical: new byte[32], sse_work                    32.87 ns/op
critical: new byte[32], vzeroupper, sse_work        34.93 ns/op
critical: noop                                       2.48 ns/op
critical: vzeroupper, noop                           2.48 ns/op
regular: noop                                        8.12 ns/op
-- round 3
critical: new byte[32], sse_work                    42.35 ns/op
critical: new byte[32], vzeroupper, sse_work        33.44 ns/op
critical: noop                                       2.59 ns/op
critical: vzeroupper, noop                           2.49 ns/op
regular: noop                                        8.18 ns/op
-- round 4
critical: new byte[32], sse_work                    32.87 ns/op
critical: new byte[32], vzeroupper, sse_work        40.91 ns/op
critical: noop                                       2.67 ns/op
critical: vzeroupper, noop                           2.51 ns/op
regular: noop                                        8.34 ns/op
(sink 0)

== java -XX:UseAVX=0
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=JKS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=32861 -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1|127.*|0.*|::|169.254.*|api.anthropic.com|api-staging.anthropic.com|api-pr-preview.anthropic.com|mcp-proxy.anthropic.com|mcp-proxy-staging.anthropic.com|registry.npmjs.org|jsr.io|npm.jsr.io|pypi.org|files.pythonhosted.org|index.crates.io|proxy.golang.org|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*|100.64.0.0/10|*.svc.cluster.local|*.svc.cluster.local -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=
-- round 1
critical: new byte[32], sse_work                    47.38 ns/op
critical: new byte[32], vzeroupper, sse_work        44.14 ns/op
critical: noop                                       3.72 ns/op
critical: vzeroupper, noop                           3.67 ns/op
regular: noop                                        8.27 ns/op
-- round 2
critical: new byte[32], sse_work                    34.52 ns/op
critical: new byte[32], vzeroupper, sse_work        39.22 ns/op
critical: noop                                       2.58 ns/op
critical: vzeroupper, noop                           2.67 ns/op
regular: noop                                        8.37 ns/op
-- round 3
critical: new byte[32], sse_work                    46.40 ns/op
critical: new byte[32], vzeroupper, sse_work        32.66 ns/op
critical: noop                                       2.51 ns/op
critical: vzeroupper, noop                           2.50 ns/op
regular: noop                                        8.15 ns/op
-- round 4
critical: new byte[32], sse_work                    33.13 ns/op
critical: new byte[32], vzeroupper, sse_work        32.74 ns/op
critical: noop                                       2.53 ns/op
critical: vzeroupper, noop                           2.63 ns/op
regular: noop                                        8.34 ns/op
(sink 0)
```

