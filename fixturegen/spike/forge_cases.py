#!/usr/bin/env python3
"""The hostile blobs used in Spike B (research.md): 23 forged headers and 52 truncations of valid blobs.

Usage: python3 forge_cases.py <out-dir>
Writes <out-dir>/caseNNN.bin and <out-dir>/cases.tsv (path, family, item type, seed, name), the input format of
Forge.java. Each forged blob is a real corpus blob with one or two header fields overwritten.

Task T007 turns this into fixturegen/python/gen_forged.py, which also writes fixtures/forged/manifest.json with
the diagnosis code each blob must produce.
"""
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORPUS = os.path.join(ROOT, "fixtures", "corpus")
out = sys.argv[1]
os.makedirs(out, exist_ok=True)


def rd(p):
    return bytearray(open(f"{CORPUS}/{p}.bin", "rb").read())


def put(b, off, data):
    b[off:off + len(data)] = data
    return b


u32 = lambda v: struct.pack("<i", v if v < 2**31 else v - 2**32)
u64 = lambda v: struct.pack("<q", v)
u16 = lambda v: struct.pack("<H", v)
cases = []


def add(name, fam, b, seed="9001", typ=""):
    cases.append((name, fam, bytes(b), seed, typ))


H8 = rd("java-6.2.0/hll/hll_HLL_8_k12_n1000_compact")
HL = rd("java-6.2.0/hll/hll_HLL_8_k12_n5_compact")
HS = rd("java-6.2.0/hll/hll_HLL_8_k12_n50_compact")
H4 = rd("java-6.2.0/hll/hll_HLL_4_k12_n100000_compact")
add("hll_lgK21_on_4KB_blob", "hll", put(bytearray(H8), 3, bytes([21])))
add("hll_lgK21_cut_to_40_bytes", "hll", put(bytearray(H8), 3, bytes([21]))[:40])
add("hll_lgK26_invalid", "hll", put(bytearray(H8), 3, bytes([26])))
add("hll_SET_count_2^31-1", "hll", put(bytearray(HS), 8, u32(0x7fffffff)))
add("hll_LIST_count_255_on_8_bytes", "hll", put(bytearray(HL), 6, bytes([255]))[:8])
add("hll_HLL4_auxCount_2^31-1", "hll", put(bytearray(H4), 36, u32(0x7fffffff)))
add("hll_mode_byte_0xff", "hll", put(bytearray(H8), 7, bytes([0xff])))

T2 = rd("java-6.2.0/theta/theta_compact_ordered_n100")
T3 = rd("java-6.2.0/theta/theta_compact_ordered_n5000")
TQ = rd("java-6.2.0/theta/theta_quickselect_n100")
TU = rd("java-6.2.0/theta/theta_union_state_n5000")
TI = rd("java-6.2.0/theta/theta_intersection_state")
TC = rd("java-9.0.0/theta/theta_compressed_ordered_n100")
add("theta_compact_exact_count_2^31-1", "theta", put(bytearray(T2), 8, u32(0x7fffffff)))
add("theta_compact_estimating_count_2^31-1", "theta", put(bytearray(T3), 8, u32(0x7fffffff)))
add("theta_quickselect_lgArr26_on_small_blob", "theta", put(bytearray(TQ), 4, bytes([26])))
add("theta_quickselect_lgNom26_lgArr26", "theta", put(put(bytearray(TQ), 3, bytes([26])), 4, bytes([26])))
add("theta_union_lgArr26_count_2^31-1", "theta", put(put(bytearray(TU), 4, bytes([26])), 8, u32(0x7fffffff)))
add("theta_intersection_lgArr26_count_1000", "theta", put(put(bytearray(TI), 4, bytes([26])), 8, u32(1000)))
add("theta_compressed_count_2^31-1_bits64", "theta", put(put(put(bytearray(TC), 3, bytes([64])), 4, bytes([4])), 8, u32(0x7fffffff)))
add("theta_theta_zero", "theta", put(bytearray(T3), 16, u64(0)))
add("theta_theta_negative", "theta", put(bytearray(T3), 16, u64(-5)))

K = rd("java-6.2.0/kll/kll_float_k200_n10000")
KS = rd("java-6.2.0/kll/kll_float_k200_n1")
KU = rd("java-9.0.0/kll/kll_float_k200_n10000_updatable")
add("kll_n_Long.MAX_numLevels61", "kll", put(put(bytearray(K), 8, u64(2**63 - 1)), 18, bytes([61])), typ="float")
add("kll_k65535_numLevels20", "kll", put(put(bytearray(K), 4, u16(65535)), 18, bytes([20])), typ="float")
add("kll_numLevels255", "kll", put(bytearray(K), 18, bytes([255])), typ="float")
add("kll_updatable_k65535_numLevels61", "kll", put(put(bytearray(KU), 4, u16(65535)), 18, bytes([61])), typ="float")
add("kll_minK_above_k", "kll", put(bytearray(K), 16, u16(65535)), typ="float")
add("kll_levels_decreasing", "kll", put(bytearray(K), 20, u32(500))[:len(K)], typ="float")
add("kll_single_item_cut_to_8", "kll", bytearray(KS)[:8], typ="float")

# truncations of valid blobs at 13 evenly spaced points
for nm, fam, b, typ in [("hll_HLL8", "hll", H8, ""), ("theta_est", "theta", T3, ""),
                        ("kll_full", "kll", K, "float"), ("theta_compressed", "theta", TC, "")]:
    for cut in sorted({int(len(b) * f / 14) for f in range(1, 14)}):
        add(f"trunc_{nm}_at_{cut}_of_{len(b)}", fam, bytes(b[:cut]), "9001", typ)

rows = []
for i, (name, fam, b, seed, typ) in enumerate(cases):
    path = os.path.join(out, f"case{i:03d}.bin")
    open(path, "wb").write(b)
    rows.append("\t".join([path, fam, typ, seed, name]))
open(os.path.join(out, "cases.tsv"), "w").write("\n".join(rows) + "\n")
forged = [c for c in cases if not c[0].startswith("trunc")]
print(f"{len(cases)} cases: {len(forged)} forged + {len(cases) - len(forged)} truncated, in {out}")
