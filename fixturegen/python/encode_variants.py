#!/usr/bin/env python3
"""Writes fixtures/variants/: one corpus blob per family in every text form people paste, plus manifest.json.

Usage: python3 encode_variants.py [out-dir]        (default: fixtures/variants)

Every variant of a blob must give the same report as the plain base64 one, apart from the "Input" line
(EncodingEquivalenceSpec). manifest.json records, for each base blob, its source in fixtures/corpus, the extra
command-line arguments it needs (a KLL sketch needs --type), and for each variant the input format the tool is
expected to report. The legacy Mongo form with a trailing comma is what a value copied from a JSON array looks like.
Deterministic: the same corpus gives the same files.
"""
import base64
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORPUS = os.path.join(ROOT, "fixtures", "corpus")
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "fixtures", "variants")

BASES = [
    ("hll", "java-9.0.0/hll/hll_HLL_8_k12_n50_compact", []),
    ("hll-hll4", "java-9.0.0/hll/hll_HLL_4_k12_n100000_compact", []),
    ("theta", "java-9.0.0/theta/theta_compact_ordered_n5000", []),
    ("theta-compressed", "java-9.0.0/theta/theta_compressed_ordered_n1000", []),
    ("kll", "java-9.0.0/kll/kll_double_k100_n10000_updatable", ["--type", "double"]),
]


def variants(blob):
    """(file name, text, expected input format label) for every form."""
    b64 = base64.b64encode(blob).decode()
    nopad = b64.rstrip("=")
    url = base64.urlsafe_b64encode(blob).decode()
    url_nopad = url.rstrip("=")
    has_url_chars = ("-" in url) or ("_" in url)
    h = blob.hex()
    wrapped = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76)) + "\n"
    return [
        ("hex.txt", h + "\n", "hex"),
        ("hex-upper.txt", h.upper() + "\n", "hex"),
        ("hex-0x.txt", "0x" + h + "\n", "hex"),
        ("hex-backslash-x.txt", "".join("\\x%02x" % c for c in blob) + "\n", "hex"),
        ("hex-spaced.txt", " ".join(h[i:i + 2] for i in range(0, len(h), 2)) + "\n", "hex"),
        ("base64.txt", b64 + "\n", "base64"),
        ("base64-nopad.txt", nopad + "\n", "base64"),
        ("base64-multiline.txt", wrapped, "base64"),
        ("base64url.txt", url_nopad + "\n", "base64url" if has_url_chars else "base64"),
        ("quoted-double.txt", '"' + b64 + '"\n', "json-string"),
        ("quoted-single.txt", "'" + b64 + "'\n", "base64"),
        ("mongo-current.json", '{"$binary": {"base64": "%s", "subType": "00"}}\n' % b64, "mongo-json"),
        ("mongo-legacy.json", '{ "$binary" : "%s", "$type" : "00" }\n' % b64, "mongo-json"),
        ("mongo-legacy-comma.txt", '  { "$binary" : "%s", "$type" : "00" },\n' % b64, "mongo-json"),
        ("mongo-nested.json",
         '{"_id": {"$oid": "65a1b2c3d4e5f60718293a4b"}, "name": "daily", "sketch": {"$binary": {"base64": "%s", "subType": "00"}}, "n": 3}\n' % b64,
         "mongo-json"),
    ]


def main():
    manifest = []
    for name, rel, args in BASES:
        blob = open(os.path.join(CORPUS, rel + ".bin"), "rb").read()
        d = os.path.join(out, name)
        os.makedirs(d, exist_ok=True)
        files = []
        with open(os.path.join(d, "raw.bin"), "wb") as f:
            f.write(blob)
        files.append({"file": "raw.bin", "format": "raw"})
        for fname, text, fmt in variants(blob):
            with open(os.path.join(d, fname), "w", newline="") as f:
                f.write(text)
            files.append({"file": fname, "format": fmt})
        manifest.append({"name": name, "source": rel + ".bin", "args": args, "variants": files})
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("wrote %d bases, %d files" % (len(manifest), sum(len(m["variants"]) for m in manifest)))


main()
