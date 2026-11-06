import glob, os, re, sys, statistics
d = os.path.dirname(os.path.abspath(__file__))
rows = {}
CLASSES = {
    "PathFragment": re.compile(r"^com\.google\.devtools\.build\.lib\.vfs\.PathFragment(\$(Relative|UnixStyleAbsolute|WindowsStyleAbsolute)PathFragment)?$"),
    "RFAV": re.compile(r"^com\.google\.devtools\.build\.lib\.actions\.FileArtifactValue\$RegularFileArtifactValue(256)?$"),
    "CachedDigest": re.compile(r"^com\.google\.devtools\.build\.lib\.vfs\.DigestUtils\$CachedDigest$"),
    "Digest256": re.compile(r"^com\.google\.devtools\.build\.lib\.vfs\.Digest256$"),
    "byte[]": re.compile(r"^\[B$"),
}
for h in sorted(glob.glob(os.path.join(d, "*.hist"))):
    name = os.path.basename(h)[:-5]
    w, v, hd, tag = name.split("-")
    stats = {k: [0, 0] for k in CLASSES}
    total = None
    for line in open(h):
        m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if m:
            n, b, cls = int(m.group(1)), int(m.group(2)), m.group(3)
            for k, rx in CLASSES.items():
                if rx.match(cls):
                    stats[k][0] += n
                    stats[k][1] += b
        m = re.match(r"Total\s+(\d+)\s+(\d+)", line)
        if m:
            total = int(m.group(2))
    log = open(os.path.join(d, name + ".log")).read()
    m = re.search(r"Elapsed time: ([0-9.]+)s", log)
    rows.setdefault((w, hd, v), []).append((total, float(m.group(1)) if m else None, stats))

print(f"{'workload':6} {'hdr':5} {'variant':7} {'live heap MB':>12} {'vs base':>9} {'wall s':>14} {'PathFragment (n / MB)':>24} {'RFAV (n / MB)':>20} {'CachedDigest MB':>15} {'byte[] MB':>9}")
for (w, hd, v) in sorted(rows, key=lambda k: (k[0], k[1], ["ga", "base", "digest", "pf", "both"].index(k[2]))):
    rs = rows[(w, hd, v)]
    tot = statistics.mean(r[0] for r in rs)
    base = rows.get((w, hd, "base"))
    btot = statistics.mean(r[0] for r in base) if base else None
    walls = [r[1] for r in rs if r[1] is not None]
    st = rs[0][2]
    delta = f"{(tot - btot) / 1e6:+.2f}MB {100 * (tot - btot) / btot:+.2f}%" if btot and v != "base" else ""
    print(f"{w:6} {hd:5} {v:7} {tot / 1e6:12.2f} {delta:>18} {'/'.join(f'{x:.1f}' for x in walls):>14}"
          f" {st['PathFragment'][0]:>10,} / {st['PathFragment'][1] / 1e6:6.2f}"
          f" {st['RFAV'][0]:>9,} / {st['RFAV'][1] / 1e6:6.2f} {st['CachedDigest'][1] / 1e6:15.2f} {st['byte[]'][1] / 1e6:9.2f}")
