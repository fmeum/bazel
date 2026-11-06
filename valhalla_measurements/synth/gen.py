#!/usr/bin/env python3
"""Generates a synthetic workspace with 150k source files and 15k genrules.

Usage: gen.py <out_dir>; then `bazel build --spawn_strategy=local //...` in it.
More files than --cache_computed_file_digests (default 50k) are digested, so
most RegularFileArtifactValue digests are *not* shared with DigestUtils' cache.
"""
import os
import sys

P, F, G = 300, 500, 50  # packages, files per package, genrules per package
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "MODULE.bazel"), "w") as fh:
    fh.write('module(name = "synth")\n')
per = F // G
for p in range(P):
    d = os.path.join(out, f"pkg{p:03d}")
    os.makedirs(d, exist_ok=True)
    for f in range(F):
        with open(os.path.join(d, f"f{f:03d}.txt"), "w") as fh:
            fh.write(f"{p} {f}\n")
    rules = []
    for g in range(G):
        srcs = ", ".join(f'"f{f:03d}.txt"' for f in range(g * per, (g + 1) * per))
        rules.append(
            f'genrule(name = "g{g:02d}", srcs = [{srcs}], outs = ["g{g:02d}.out"], '
            'cmd = "cat $(SRCS) > $@")\n'
        )
    with open(os.path.join(d, "BUILD"), "w") as fh:
        fh.write("".join(rules))
