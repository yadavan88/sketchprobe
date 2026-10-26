#!/usr/bin/env python3
"""Fixture generator for the C++ implementation, through the `datasketches` Python package.

Usage: python gen_cpp.py <out-dir>

Writes <out-dir>/{hll,theta,kll}/<name>.bin and <name>.json, in the same manifest format as the Java
generators (fixturegen/jvm/gen68 and gen9), so one test loader reads both.

What the package can write, and what this script therefore skips:
  * No serialization for update sketches or set-operation state: only compact Theta sketches (and the
    compact results of a union or an intersection).
  * No compressed Theta (serVer 4) writer.
  * KLL items are float, double or 32-bit int. 32-bit ints are not one of our item types, so they are skipped.

As in the Java generators, expected values are read back from the written bytes, never derived from the
input data, because KLL compaction is randomized.

Needs Python 3.9 to 3.13 with a prebuilt `datasketches` wheel (there is none for Python 3.14).
"""
import importlib.metadata
import json
import math
import os
import platform
import sys

import datasketches as ds

RANKS = [0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1]
DEFAULT_SEED = 9001
SCRIPT = "fixturegen/python/gen_cpp.py"
OUT = None
WRITTEN = 0


def num(x):
    """JSON cannot carry NaN or infinity."""
    if isinstance(x, float) and (math.isnan(x) or math.isinf(x)):
        return None
    return x


def header(b):
    return {
        "lengthBytes": len(b),
        "byte0": b[0],
        "serVer": b[1],
        "familyId": b[2],
        "firstBytesHex": b[:24].hex(),
    }


def write(family, name, data, inputs, params, expected):
    global WRITTEN
    d = os.path.join(OUT, family)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name + ".bin"), "wb") as f:
        f.write(data)
    manifest = {
        "name": name,
        "family": family,
        "generator": {
            "library": "datasketches-cpp (Python package datasketches)",
            "version": importlib.metadata.version("datasketches"),
            "python": platform.python_version(),
            "script": SCRIPT,
        },
        "inputs": inputs,
        "params": params,
        "header": header(data),
        "expected": expected,
    }
    with open(os.path.join(d, name + ".json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    WRITTEN += 1


# ---------------------------------------------------------------- HLL
def hll_expected(h):
    e = {
        "estimate": h.get_estimate(),
        "lgK": h.lg_config_k,
        "targetType": str(h.tgt_type).split(".")[-1],
        "empty": h.is_empty(),
        "compact": h.is_compact(),
    }
    for sd in (1, 2, 3):
        e[f"lowerBound{sd}"] = h.get_lower_bound(sd)
        e[f"upperBound{sd}"] = h.get_upper_bound(sd)
    return e


def hll_both(base, h, inputs, params):
    compact = h.serialize_compact()
    updatable = h.serialize_updatable()
    write("hll", base + "_compact", compact, inputs, params, hll_expected(ds.hll_sketch.deserialize(compact)))
    write("hll", base + "_updatable", updatable, inputs, params, hll_expected(ds.hll_sketch.deserialize(updatable)))


def make_hll(lg_k, t, lo, hi):
    h = ds.hll_sketch(lg_k, t)
    for i in range(lo, hi):
        h.update(i)
    return h


def gen_hll():
    types = {"HLL_4": ds.tgt_hll_type.HLL_4, "HLL_6": ds.tgt_hll_type.HLL_6, "HLL_8": ds.tgt_hll_type.HLL_8}
    cases = [(8, 20), (8, 300), (12, 1), (12, 5), (12, 50), (12, 1000), (12, 100000), (16, 100000)]
    for tname, t in types.items():
        for lg_k, n in cases:
            hll_both(
                f"hll_{tname}_k{lg_k}_n{n}",
                make_hll(lg_k, t, 0, n),
                {"kind": "distinct longs", "range": f"0..{n - 1}", "count": n},
                {"lgK": lg_k, "targetType": tname},
            )
        hll_both(
            f"hll_{tname}_k12_empty", ds.hll_sketch(12, t),
            {"kind": "none", "count": 0}, {"lgK": 12, "targetType": tname},
        )
        # Merged sketches are marked out-of-order and need the composite estimator.
        for (a0, a1), (b0, b1) in (((0, 1000), (500, 2000)), ((0, 100000), (50000, 150000))):
            u = ds.hll_union(12)
            u.update(make_hll(12, t, a0, a1))
            u.update(make_hll(12, t, b0, b1))
            hll_both(
                f"hll_{tname}_k12_union_{a1}_{b1}",
                u.get_result(t),
                {"kind": "union of two distinct-long ranges", "ranges": [f"{a0}..{a1 - 1}", f"{b0}..{b1 - 1}"]},
                {"lgK": 12, "targetType": tname, "outOfOrderExpected": True},
            )


# ---------------------------------------------------------------- Theta
def theta_expected(s, seed):
    e = {
        "estimate": s.get_estimate(),
        "theta": s.theta,
        "retainedValid": s.num_retained,
        "empty": s.is_empty(),
        "ordered": s.is_ordered(),
        "compact": True,
        "estimationMode": s.is_estimation_mode(),
        "seed": str(seed),
        "seedHash": s.get_seed_hash(),
    }
    for sd in (1, 2, 3):
        e[f"lowerBound{sd}"] = s.get_lower_bound(sd)
        e[f"upperBound{sd}"] = s.get_upper_bound(sd)
    return e


def make_theta(lg_k, seed, p, lo, hi):
    u = ds.update_theta_sketch(lg_k, p, seed)
    for i in range(lo, hi):
        u.update(i)
    return u


def theta_in(lo, hi):
    return {"kind": "distinct longs", "range": f"{lo}..{hi - 1}", "count": hi - lo}


def theta_params(shape, nominal, seed, p):
    return {"shape": shape, "nominalEntries": nominal, "seed": str(seed), "p": p}


def gen_theta():
    for seed in (DEFAULT_SEED, 12345):
        sfx = "" if seed == DEFAULT_SEED else f"_seed{seed}"
        for n in (0, 1, 100, 5000):
            u = make_theta(10, seed, 1.0, 0, n)
            for ordered in (True, False):
                if not ordered and n < 2:
                    continue
                b = u.compact(ordered).serialize()
                write("theta", f"theta_compact_{'ordered' if ordered else 'unordered'}_n{n}{sfx}", b,
                      theta_in(0, n), theta_params("compact", 1024, seed, 1.0),
                      theta_expected(ds.compact_theta_sketch.deserialize(b, seed), seed))
    # Sampling probability below 1 puts theta under 1.0 even for few items.
    b = make_theta(10, DEFAULT_SEED, 0.5, 0, 1000).compact(True).serialize()
    write("theta", "theta_compact_ordered_p05_n1000", b, theta_in(0, 1000),
          theta_params("compact", 1024, DEFAULT_SEED, 0.5),
          theta_expected(ds.compact_theta_sketch.deserialize(b), DEFAULT_SEED))
    b = make_theta(12, DEFAULT_SEED, 1.0, 0, 100000).compact(True).serialize()
    write("theta", "theta_compact_ordered_k4096_n100000", b, theta_in(0, 100000),
          theta_params("compact", 4096, DEFAULT_SEED, 1.0),
          theta_expected(ds.compact_theta_sketch.deserialize(b), DEFAULT_SEED))
    # Set-operation results (compact sketches; the package cannot serialize operation state).
    a, bsk = make_theta(10, DEFAULT_SEED, 1.0, 0, 3000), make_theta(10, DEFAULT_SEED, 1.0, 2000, 5000)
    un = ds.theta_union(10, 1.0, DEFAULT_SEED)
    un.update(a)
    un.update(bsk)
    b = un.get_result().serialize()
    write("theta", "theta_union_result_n5000", b,
          {"kind": "union of two ranges", "ranges": ["0..2999", "2000..4999"]},
          theta_params("union result (compact)", 1024, DEFAULT_SEED, 1.0),
          theta_expected(ds.compact_theta_sketch.deserialize(b), DEFAULT_SEED))
    inter = ds.theta_intersection(DEFAULT_SEED)
    inter.update(a)
    inter.update(bsk)
    b = inter.get_result().serialize()
    write("theta", "theta_intersection_result", b,
          {"kind": "intersection of two ranges", "ranges": ["0..2999", "2000..4999"]},
          theta_params("intersection result (compact)", 1024, DEFAULT_SEED, 1.0),
          theta_expected(ds.compact_theta_sketch.deserialize(b), DEFAULT_SEED))


# ---------------------------------------------------------------- KLL
def kll_case(item_type, make, update_val, k, n):
    s = make(k)
    for i in range(1, n + 1):
        s.update(update_val(i))
    b = s.serialize()
    r = type(s).deserialize(b)
    e = {"n": r.n, "k": k, "itemType": item_type, "empty": r.is_empty(), "retained": r.num_retained}
    if n > 0:
        e["min"] = num(float(r.get_min_value()))
        e["max"] = num(float(r.get_max_value()))
        # The package defaults to the EXCLUSIVE search (inclusive=False); Java uses the inclusive one, so ask for it.
        e["quantiles"] = [[q, num(float(r.get_quantile(q, True)))] for q in RANKS]
    write("kll", f"kll_{item_type}_k{k}_n{n}", b,
          {"kind": "values i*1.5 (double), i (float), i=1..n", "count": n},
          {"k": k, "itemType": item_type}, e)


def gen_kll():
    for k in (200, 100):
        for n in (0, 1, 10, 500, 10000, 200000):
            if k == 100 and n != 10000:
                continue
            kll_case("float", ds.kll_floats_sketch, float, k, n)
            kll_case("double", ds.kll_doubles_sketch, lambda i: i * 1.5, k, n)


def main():
    global OUT
    OUT = sys.argv[1]
    gen_hll()
    gen_theta()
    gen_kll()
    print(f"wrote {WRITTEN} blobs to {OUT} with datasketches {importlib.metadata.version('datasketches')} "
          f"on Python {platform.python_version()}")


if __name__ == "__main__":
    main()
