#!/usr/bin/env python3
"""Runs every blob in the corpus, the forged set and the encoded variants through two builds of sketchprobe
and requires identical output.

Usage: python3 -I packaging/compare-corpus.py "<reference command>" "<candidate command>"
   e.g. python3 -I packaging/compare-corpus.py "java -jar cli/target/scala-3.9.0/sketchprobe.jar" dist/native/sketchprobe

Each blob is fed as base64 on standard input, as a user would paste it, in the human form and with --json.
Standard output, standard error and the exit status must all be equal. This is what shows that the native
image behaves like the JVM (same library, same results, same messages, including on hostile blobs).
"""
import base64
import json
import os
import shlex
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS = os.path.join(ROOT, "fixtures", "corpus")
FORGED = os.path.join(ROOT, "fixtures", "forged")
VARIANTS = os.path.join(ROOT, "fixtures", "variants")
TYPES = ("float", "double", "long", "string")


def corpus_jobs():
    for writer in sorted(os.listdir(CORPUS)):
        for family in sorted(os.listdir(os.path.join(CORPUS, writer))):
            d = os.path.join(CORPUS, writer, family)
            for f in sorted(os.listdir(d)):
                if f.endswith(".json"):
                    m = json.load(open(os.path.join(d, f)))
                    t = m.get("params", {}).get("itemType")
                    yield (f"{writer}/{family}/{f[:-5]}", open(os.path.join(d, f[:-5] + ".bin"), "rb").read(),
                           ["--type", t] if t in TYPES else [], True)


def forged_jobs():
    manifest = json.load(open(os.path.join(FORGED, "manifest.json")))["cases"]
    for c in manifest:
        t = c["itemType"]
        yield (f"forged/{c['name']}", open(os.path.join(FORGED, c["file"]), "rb").read(),
               ["--type", t] if t in TYPES else [], True)


def variant_jobs():
    # The variants are text, fed as they are (no base64 wrapping).
    manifest = json.load(open(os.path.join(VARIANTS, "manifest.json")))
    for base in manifest:
        for v in base["variants"]:
            yield (f"variants/{base['name']}/{v['file']}",
                   open(os.path.join(VARIANTS, base["name"], v["file"]), "rb").read(), base["args"], False)


def run(cmd, args, data):
    p = subprocess.run(cmd + args, input=data, capture_output=True, timeout=120)
    return (p.returncode, p.stdout, p.stderr)


def check(job, ref, cand):
    label, blob, args, wrap = job
    data = base64.b64encode(blob) if wrap else blob
    diffs = []
    for extra in ([], ["--json"]):
        a, b = run(ref, extra + args, data), run(cand, extra + args, data)
        if a != b:
            what = "status" if a[0] != b[0] else "stdout" if a[1] != b[1] else "stderr"
            diffs.append(f"{label} {' '.join(extra) or 'human'}: {what} differs (status {a[0]} vs {b[0]})")
    return diffs


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    ref, cand = shlex.split(sys.argv[1]), shlex.split(sys.argv[2])
    jobs = list(corpus_jobs()) + list(forged_jobs()) + list(variant_jobs())
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda j: check(j, ref, cand), jobs))
    bad = [d for r in results for d in r]
    print(f"{len(jobs)} inputs, each human and --json: {len(jobs) * 2 - len(bad)} of {len(jobs) * 2} identical")
    for d in bad[:20]:
        print("  DIFFERENT: " + d)
    sys.exit(1 if bad else 0)


main()
