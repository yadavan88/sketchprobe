#!/usr/bin/env python3
"""Writes the hostile-blob test set: fixtures/forged/*.bin and fixtures/forged/manifest.json.

Usage: python3 gen_forged.py [out-dir]        (default: fixtures/forged)

Each forged blob is a real corpus blob with one or two header fields overwritten, and each truncated blob is a valid
blob cut short. The manifest records what the tool must do with every one of them, by two rules:
  * a field with an impossible value (a lgK that is out of range, a theta of zero, a mode byte that is not a mode)
    means the blob is not a sketch: `not-a-sketch`;
  * a size the blob cannot back (a count or an array size larger than the bytes present, a truncation) means the
    blob is cut off or forged: `incomplete`.
`accepted` marks the one case that is only a soft inconsistency (KLL minK above k), which must still decode, with a
warning. The set comes from Spike B (specs/001-sketch-inspector/research.md); in that spike the library ran out of
memory on five of these blobs and accepted nonsense for three, which is why the tool checks them first.
Deterministic: the same corpus blobs always give the same files.
"""
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORPUS = os.path.join(ROOT, "fixtures", "corpus")
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "fixtures", "forged")

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

# Frequency (family 10). Layout, from the corpus (research R16, BF1): 8-byte empty sketch (preLongs 1, flags 5), or a
# 32-byte preamble (preLongs 4: lgMax byte 3, lgCur byte 4, flags byte 5, numActive int at 8, stream length long at 16,
# offset long at 24), then numActive counts (8 bytes each), then the items: 8 bytes each for longs, a 4-byte length plus
# UTF-8 for strings. The library trusts numActive and every string length.
FL = rd("java-9.0.0/frequency/freq_long_m64_exact7")      # 144 bytes: 7 long items
FS = rd("java-9.0.0/frequency/freq_string_m64_exact7")    # 469 bytes: 7 string items, the first at byte 88
FE = rd("java-9.0.0/frequency/freq_long_m64_empty")       # 8 bytes
add("freq_long_numActive_2^31-1", "frequency", put(bytearray(FL), 8, u32(0x7fffffff)), typ="long")
add("freq_string_numActive_2^31-1", "frequency", put(bytearray(FS), 8, u32(0x7fffffff)), typ="string")
add("freq_long_numActive_48_on_144_bytes", "frequency", put(bytearray(FL), 8, u32(48)), typ="long")
add("freq_string_numActive_48_on_469_bytes", "frequency", put(bytearray(FS), 8, u32(48)), typ="string")
add("freq_long_numActive_negative", "frequency", put(bytearray(FL), 8, u32(-1)), typ="long")
add("freq_long_numActive_above_capacity", "frequency", put(bytearray(FL), 8, u32(1000)), typ="long")
add("freq_long_lgMax_31", "frequency", put(bytearray(FL), 3, bytes([31])), typ="long")
add("freq_long_lgMax_2", "frequency", put(bytearray(FL), 3, bytes([2])), typ="long")
add("freq_long_lgCur_above_lgMax", "frequency", put(bytearray(FL), 4, bytes([8])), typ="long")
add("freq_long_preLongs_2", "frequency", put(bytearray(FL), 0, bytes([2])), typ="long")
add("freq_long_empty_flag_with_data", "frequency", put(bytearray(FL), 5, bytes([5])), typ="long")
add("freq_empty_without_empty_flag", "frequency", put(bytearray(FE), 5, bytes([0])), typ="long")
add("freq_long_streamLength_negative", "frequency", put(bytearray(FL), 16, u64(-1)), typ="long")
add("freq_long_streamLength_below_counts", "frequency", put(bytearray(FL), 16, u64(5)), typ="long")
add("freq_long_offset_negative", "frequency", put(bytearray(FL), 24, u64(-3)), typ="long")
add("freq_long_count_negative", "frequency", put(bytearray(FL), 32, u64(-5)), typ="long")
add("freq_long_count_zero", "frequency", put(bytearray(FL), 32, u64(0)), typ="long")
add("freq_string_first_length_2^31-1", "frequency", put(bytearray(FS), 88, u32(0x7fffffff)), typ="string")
add("freq_string_first_length_negative", "frequency", put(bytearray(FS), 88, u32(-4)), typ="string")
add("freq_string_first_length_400", "frequency", put(bytearray(FS), 88, u32(400)), typ="string")

# Quantiles (family 8). Layout, from the corpus (java-9.0.0/quantiles): byte 0 preamble longs (1 empty, 2 otherwise), byte 1
# serVer 3, byte 3 flags (4 empty, 8 compact), bytes 4-5 k (a power of two), bytes 8-15 n, then min, max, and the items from
# byte 32. The library trusts n and k to size its arrays.
Q = rd("java-9.0.0/quantiles/quantiles_k128_n10000_compact")        # 4256 bytes
QU = rd("java-9.0.0/quantiles/quantiles_k128_n10000_updatable")     # 8224 bytes
QE = rd("java-9.0.0/quantiles/quantiles_k128_empty_compact")        # 8 bytes
add("quantiles_k_not_power_of_two", "quantiles", put(bytearray(Q), 4, u16(100)))
add("quantiles_k_zero", "quantiles", put(bytearray(Q), 4, u16(0)))
add("quantiles_k_65535", "quantiles", put(bytearray(Q), 4, u16(65535)))
add("quantiles_k_32768_on_small_blob", "quantiles", put(bytearray(Q), 4, u16(32768)))
add("quantiles_updatable_k32768_n_Long.MAX", "quantiles", put(put(bytearray(QU), 4, u16(32768)), 8, u64(2**63 - 1)))
add("quantiles_compact_n_Long.MAX", "quantiles", put(bytearray(Q), 8, u64(2**63 - 1)))
add("quantiles_updatable_n_Long.MAX", "quantiles", put(bytearray(QU), 8, u64(2**63 - 1)))
add("quantiles_n_negative", "quantiles", put(bytearray(Q), 8, u64(-1)))
add("quantiles_n_zero_not_empty", "quantiles", put(bytearray(Q), 8, u64(0)))
add("quantiles_preLongs_1_not_empty", "quantiles", put(bytearray(Q), 0, bytes([1])))
add("quantiles_preLongs_2_empty", "quantiles", put(bytearray(QE), 0, bytes([2])))
add("quantiles_empty_flag_with_data", "quantiles", put(bytearray(Q), 3, bytes([0x1e])))
add("quantiles_not_empty_flag_on_8_bytes", "quantiles", put(bytearray(QE), 3, bytes([0x1a])))

# truncations of valid blobs at 13 evenly spaced points
for nm, fam, b, typ in [("hll_HLL8", "hll", H8, ""), ("theta_est", "theta", T3, ""),
                        ("kll_full", "kll", K, "float"), ("theta_compressed", "theta", TC, ""),
                        ("freq_long", "frequency", FL, "long"), ("freq_string", "frequency", FS, "string"),
                        ("quantiles_compact", "quantiles", Q, ""), ("quantiles_updatable", "quantiles", QU, "")]:
    for cut in sorted({int(len(b) * f / 14) for f in range(1, 14)}):
        add(f"trunc_{nm}_at_{cut}_of_{len(b)}", fam, bytes(b[:cut]), "9001", typ)

EXPECT = {
    "hll_lgK21_on_4KB_blob": "incomplete", "hll_lgK21_cut_to_40_bytes": "incomplete", "hll_lgK26_invalid": "not-a-sketch",
    "hll_SET_count_2^31-1": "incomplete", "hll_LIST_count_255_on_8_bytes": "incomplete",
    "hll_HLL4_auxCount_2^31-1": "incomplete", "hll_mode_byte_0xff": "not-a-sketch",
    "theta_compact_exact_count_2^31-1": "incomplete", "theta_compact_estimating_count_2^31-1": "incomplete",
    "theta_quickselect_lgArr26_on_small_blob": "incomplete", "theta_quickselect_lgNom26_lgArr26": "incomplete",
    "theta_union_lgArr26_count_2^31-1": "incomplete", "theta_intersection_lgArr26_count_1000": "incomplete",
    "theta_compressed_count_2^31-1_bits64": "incomplete", "theta_theta_zero": "not-a-sketch",
    "theta_theta_negative": "not-a-sketch", "kll_n_Long.MAX_numLevels61": "not-a-sketch",
    "kll_k65535_numLevels20": "not-a-sketch", "kll_numLevels255": "not-a-sketch",
    "kll_updatable_k65535_numLevels61": "incomplete", "kll_minK_above_k": "accepted",
    "kll_levels_decreasing": "not-a-sketch", "kll_single_item_cut_to_8": "incomplete",
    "freq_long_numActive_2^31-1": "not-a-sketch", "freq_string_numActive_2^31-1": "not-a-sketch",
    "freq_long_numActive_48_on_144_bytes": "incomplete", "freq_string_numActive_48_on_469_bytes": "incomplete",
    "freq_long_numActive_negative": "not-a-sketch", "freq_long_numActive_above_capacity": "not-a-sketch",
    "freq_long_lgMax_31": "not-a-sketch", "freq_long_lgMax_2": "not-a-sketch",
    "freq_long_lgCur_above_lgMax": "not-a-sketch", "freq_long_preLongs_2": "not-a-sketch",
    "freq_long_empty_flag_with_data": "not-a-sketch", "freq_empty_without_empty_flag": "not-a-sketch",
    "freq_long_streamLength_negative": "not-a-sketch", "freq_long_streamLength_below_counts": "not-a-sketch",
    "freq_long_offset_negative": "not-a-sketch", "freq_long_count_negative": "not-a-sketch",
    "freq_long_count_zero": "not-a-sketch", "freq_string_first_length_2^31-1": "incomplete",
    "freq_string_first_length_negative": "not-a-sketch", "freq_string_first_length_400": "incomplete",
    "quantiles_k_not_power_of_two": "not-a-sketch", "quantiles_k_zero": "not-a-sketch",
    "quantiles_k_65535": "not-a-sketch", "quantiles_k_32768_on_small_blob": "incomplete",
    "quantiles_updatable_k32768_n_Long.MAX": "incomplete", "quantiles_compact_n_Long.MAX": "incomplete",
    "quantiles_updatable_n_Long.MAX": "incomplete", "quantiles_n_negative": "not-a-sketch",
    "quantiles_n_zero_not_empty": "not-a-sketch", "quantiles_preLongs_1_not_empty": "not-a-sketch",
    "quantiles_preLongs_2_empty": "not-a-sketch", "quantiles_empty_flag_with_data": "not-a-sketch",
    "quantiles_not_empty_flag_on_8_bytes": "not-a-sketch",
}
WHAT = {
    "hll_lgK21_on_4KB_blob": "lgK byte set to 21 on a 4 KB sketch (claims a 2 MB register array)",
    "hll_lgK21_cut_to_40_bytes": "lgK 21, blob cut to the 40-byte preamble",
    "hll_lgK26_invalid": "lgK 26, outside the valid range 4 to 21",
    "hll_SET_count_2^31-1": "coupon count 2^31-1 in a SET-mode sketch",
    "hll_LIST_count_255_on_8_bytes": "LIST count 255 on an 8-byte blob",
    "hll_HLL4_auxCount_2^31-1": "HLL_4 aux count 2^31-1",
    "hll_mode_byte_0xff": "mode byte 0xff (not a mode)",
    "theta_compact_exact_count_2^31-1": "compact exact sketch with entry count 2^31-1",
    "theta_compact_estimating_count_2^31-1": "compact estimating sketch with entry count 2^31-1",
    "theta_quickselect_lgArr26_on_small_blob": "QuickSelect sketch with lgArrLongs 26 (claims a 512 MB table)",
    "theta_quickselect_lgNom26_lgArr26": "QuickSelect sketch with lgNomLongs and lgArrLongs 26",
    "theta_union_lgArr26_count_2^31-1": "Union state with lgArrLongs 26 and count 2^31-1",
    "theta_intersection_lgArr26_count_1000": "Intersection state with lgArrLongs 26 and count 1000",
    "theta_compressed_count_2^31-1_bits64": "compressed Theta with count 2^31-1 at 64 bits per entry",
    "theta_theta_zero": "estimating sketch with theta 0",
    "theta_theta_negative": "estimating sketch with a negative theta",
    "kll_n_Long.MAX_numLevels61": "KLL n = Long.MAX_VALUE and 61 levels",
    "kll_k65535_numLevels20": "KLL k 65535 with 20 levels",
    "kll_numLevels255": "KLL with 255 levels",
    "kll_updatable_k65535_numLevels61": "KLL updatable layout with k 65535 and 61 levels (claims gigabytes of capacity)",
    "kll_minK_above_k": "KLL minK above k (a soft inconsistency)",
    "kll_levels_decreasing": "KLL level array that decreases",
    "kll_single_item_cut_to_8": "KLL single-item sketch cut to its 8-byte header",
    "freq_long_numActive_2^31-1": "Frequency (long items) numActive 2^31-1 on a 144-byte blob (claims 32 GB, and is far above any table's capacity)",
    "freq_string_numActive_2^31-1": "Frequency (string items) numActive 2^31-1 on a 469-byte blob",
    "freq_long_numActive_48_on_144_bytes": "Frequency (long items) numActive 48, a full table's worth, on a 144-byte blob that holds 7 (needs 800 bytes)",
    "freq_string_numActive_48_on_469_bytes": "Frequency (string items) numActive 48 on a 469-byte blob that holds 7 (the strings run past the end)",
    "freq_long_numActive_negative": "Frequency numActive -1",
    "freq_long_numActive_above_capacity": "Frequency numActive 1000 in a table of at most 64 slots (48 usable)",
    "freq_long_lgMax_31": "Frequency lgMaxMapSize 31, above the largest table the library allows",
    "freq_long_lgMax_2": "Frequency lgMaxMapSize 2, below the smallest table (8 slots)",
    "freq_long_lgCur_above_lgMax": "Frequency current table size larger than its maximum",
    "freq_long_preLongs_2": "Frequency preamble size 2 (must be 1 for empty or 4)",
    "freq_long_empty_flag_with_data": "Frequency empty flag set on a sketch that has 7 items",
    "freq_empty_without_empty_flag": "Frequency 8-byte sketch without the empty flag (it would have a 32-byte preamble)",
    "freq_long_streamLength_negative": "Frequency stream length -1",
    "freq_long_streamLength_below_counts": "Frequency stream length 5, below the sum of the counts (1,125)",
    "freq_long_offset_negative": "Frequency offset -3",
    "freq_long_count_negative": "Frequency first count -5",
    "freq_long_count_zero": "Frequency first count 0 (an active item has a positive count)",
    "freq_string_first_length_2^31-1": "Frequency string item with length 2^31-1 (claims 2 GB)",
    "freq_string_first_length_negative": "Frequency string item with length -4",
    "freq_string_first_length_400": "Frequency string item with length 400, past the end of the blob",
    "quantiles_k_not_power_of_two": "Quantiles k 100, not a power of two",
    "quantiles_k_zero": "Quantiles k 0",
    "quantiles_k_65535": "Quantiles k 65535, above the largest k (32768) and not a power of two",
    "quantiles_k_32768_on_small_blob": "Quantiles k 32768 on a 4 KB blob (claims 80 KB of items)",
    "quantiles_updatable_k32768_n_Long.MAX": "Quantiles working layout with k 32768 and n Long.MAX_VALUE (claims 15 MB)",
    "quantiles_compact_n_Long.MAX": "Quantiles compact layout with n Long.MAX_VALUE",
    "quantiles_updatable_n_Long.MAX": "Quantiles working layout with n Long.MAX_VALUE (claims more levels than the blob holds)",
    "quantiles_n_negative": "Quantiles n -1",
    "quantiles_n_zero_not_empty": "Quantiles n 0 on a sketch without the empty flag",
    "quantiles_preLongs_1_not_empty": "Quantiles preamble size 1 on a non-empty sketch (must be 2)",
    "quantiles_preLongs_2_empty": "Quantiles preamble size 2 on an empty sketch (must be 1)",
    "quantiles_empty_flag_with_data": "Quantiles empty flag set on a sketch that has 10,000 items",
    "quantiles_not_empty_flag_on_8_bytes": "Quantiles 8-byte sketch without the empty flag (it would have a 32-byte preamble)",
}

def file_name(name):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", name) + ".bin"

os.makedirs(out, exist_ok=True)
for f in os.listdir(out):
    if f.endswith(".bin") or f == "manifest.json":
        os.remove(os.path.join(out, f))
entries = []
for name, fam, b, seed, typ in cases:
    truncated = name.startswith("trunc_")
    fn = file_name(name)
    open(os.path.join(out, fn), "wb").write(b)
    entries.append({
        "name": name, "file": fn, "family": fam, "itemType": typ, "seed": seed,
        "group": "truncated" if truncated else "forged",
        "expect": "incomplete" if truncated else EXPECT[name],
        "what": ("valid blob cut to %d bytes" % len(b)) if truncated else WHAT[name],
        "lengthBytes": len(b),
    })
json.dump({"cases": entries}, open(os.path.join(out, "manifest.json"), "w"), indent=2)
forged = [e for e in entries if e["group"] == "forged"]
print(f"wrote {len(entries)} blobs ({len(forged)} forged, {len(entries) - len(forged)} truncated) and manifest.json to {out}")
