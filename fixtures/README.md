# Reference corpus

Real sketch blobs written by real libraries, each with a manifest of the values that library reported for that exact blob. This corpus defines correct behavior for `sketchprobe` (spec FR-018, SC-002). The tool is tested against it. Running those tests needs JDK 25 (for the sketch library the tool uses), but not Python or any other library version.

## Layout

```
fixtures/corpus/<writer>/<family>/<name>.bin     the blob
fixtures/corpus/<writer>/<family>/<name>.json    its manifest
```

`<family>` is `hll`, `theta` or `kll`. `<writer>` says which implementation produced the blob:

| Writer directory | Library | Run on | What is committed |
|---|---|---|---|
| `java-6.2.0` | datasketches-java 6.2.0 | JDK 21, no flags | everything (112 blobs) |
| `java-9.0.0` | datasketches-java 9.0.0 | JDK 25, no flags | everything (140 blobs), including compressed Theta and the KLL updatable layout |
| `cpp-5.2.0` | datasketches-cpp, through the `datasketches` Python package 5.2.0 | Python 3.13 | everything (96 blobs) |
| `java-8.0.0` | datasketches-java 8.0.0 | JDK 21, `--enable-preview` | KLL only (21 blobs) |
| `java-5.0.2`, `java-3.3.0` | older datasketches-java lines | JDK 21, no flags | KLL only (6 blobs each) |

Total: 381 blobs, about 4.4 MB.

### Why some writers have only KLL

HLL and Theta are deterministic. On 2026-10-25 the blobs from datasketches-java **3.3.0, 4.2.0, 5.0.2, 6.0.0, 6.1.1 and 8.0.0 were byte-identical to 6.2.0's for all 91 HLL and Theta cases** (same names, same inputs). Committing copies would test nothing new, so only 6.2.0 is kept. KLL is randomized (below), so every run is a new sample, and the KLL blobs from the other lines are kept as evidence that the KLL layout did not change either (the header shapes are identical across every line).

`java-9.0.0` and `cpp-5.2.0` are kept in full because they really do differ from 6.2.0 (see "Writer differences").

## Manifest format

```json
{
  "name": "hll_HLL_8_k12_n1000_compact",
  "family": "hll",
  "generator": { "library": "datasketches-java", "version": "6.2.0", "jdk": "21.0.9", "script": "fixturegen/jvm/gen6" },
  "inputs":   { "kind": "distinct longs", "range": "0..999", "count": 1000 },
  "params":   { "lgK": 12, "targetType": "HLL_8" },
  "header":   { "lengthBytes": 3113, "byte0": 10, "serVer": 1, "familyId": 7, "firstBytesHex": "..." },
  "expected": { "estimate": 996.18, "lowerBound1": 0, "...": 0 }
}
```

- `expected` holds what the writer library reported, **read back from the written bytes** where the library allows it. It is never computed from `inputs`. KLL compaction chooses random offsets, so the same input gives a different blob and different quantiles on every run (the median of 1..10,000 came out 4,998 once and 5,012 another time). Always compare against `expected`, never against the input.
- `header` is read from the bytes by the generator. It is informational.
- **64-bit integers are strings** (`theta` is a double, but `seed`, and the min, max and quantiles of KLL long sketches, are decimal strings) so no value loses precision.
- `NaN` and infinity are written as `null`.
- Estimates differ in the last bit between implementations (Java gave 5018.888379388582 and C++ gave 5018.888379388581 for the same Theta blob). Compare with a relative tolerance, not exact equality. See `specs/001-sketch-inspector/research.md` (R12).

### Expected fields by family

- **HLL**: `estimate`, `lowerBound1..3`, `upperBound1..3`, `lgK`, `targetType`, `empty`, `compact`. Each case is written in both layouts (`_compact` and `_updatable`). `_union_` cases are merged sketches (out-of-order, needing the composite estimator).
- **Theta**: `estimate`, `lowerBound1..3`, `upperBound1..3`, `theta`, `retainedValid`, `retainedAll` (Java only), `empty`, `ordered`, `compact`, `estimationMode`, `seed`, `seedHash`.
- **KLL**: `n`, `k`, `itemType` (`float`, `double`, `long`), `empty`, `retained`, `min`, `max` and `quantiles` (pairs of rank and value, inclusive search, ranks 0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1). The blob does not record `itemType`, so the manifest does.

## What each family covers

- **HLL**: HLL_4, HLL_6 and HLL_8; LIST, SET and HLL modes; lgK 8, 12 and 16; empty; merged (out-of-order) sketches; compact and updatable layouts.
- **Theta**: empty, single item, exact, estimating; ordered and unordered; non-default seed (12345); sampling probability 0.5; nominal size 1024 and 4096; Java also covers Alpha and QuickSelect update sketches (families 1 and 2), Union state (4) and Intersection state (5), and 9.0.0 adds the compressed format (serVer 4). AnotB (family 6) has no serializer in any library, so there is no fixture for it.
- **KLL**: float, double and long (Java only; C++ has no 64-bit item type, and its 32-bit int sketch is not one of our item types); empty, single item, `n` up to 200,000; `k` 200 and 100; 9.0.0 also writes the updatable layout (serVer 3), which the C++ library cannot read.

## Writer differences found by this corpus

These are real divergences between writers of the same format. The decoders must handle each:

1. **Theta `p` field (bytes 12 to 15) in compact sketches.** 6.2.0 (and every older line) writes `1.0f`, 9.0.0 writes zeros. The field is unused by compact sketches. Ignore it.
2. **Theta single-item flag (flags bit 32).** Java sets it. C++ does not, though the blob is the same 16-byte shape (preamble longs = 1, one hash). Detect a single item from the preamble size and length, not from the flag.
3. **Theta seed hash of an empty sketch.** Java writes 0 in bytes 6 and 7. C++ writes the real seed hash. Never raise a seed-mismatch warning for an empty sketch.
4. **HLL compact flag (flags bit 8).** Java leaves it unset for HLL_6 and HLL_8 compact blobs (only HLL_4 has a real compact form), C++ always sets it. Do not use that flag to compute the expected length.
5. **Theta preamble byte 0 carries the resize factor in its top two bits** (update sketches show 195 and 196, not 3 and 4). Mask with `0x3F`.
6. **HLL bounds in datasketches-cpp 5.2.0 and older** use truncated constants (`0.8325546`, `1.03896`) for lgK above 12, so they differ from Java by up to 1.3e-10 relative. Java matches the decoder bit for bit.
7. **KLL quantile at rank 0 and 1.** The current Java lines return the stream minimum and maximum. Java 5.0.2 and datasketches-cpp 5.2.0 return the smallest and largest *retained* item instead, which for a large sketch is not the minimum. Interior ranks agree everywhere.
8. **The Python package's `get_quantile(rank)` is exclusive by default** (`inclusive=False`), while Java's default is inclusive. The C++ generator therefore asks for `inclusive=True` explicitly, so manifests use the same rule for every writer.
9. **KLL updatable layout (serVer 3) is Java-only.** The C++ library rejects it ("serial version mismatch: expected 1 or 2, got 3").

## The forged set (`fixtures/forged/`)

75 hostile blobs for the safety checks, separate from the corpus because they are not real sketches: 23 forged headers (a real blob with one or two header fields overwritten) and 52 truncations of valid blobs. `manifest.json` records what the tool must do with each: `incomplete` (a claimed size the blob cannot back, or a truncation), `not-a-sketch` (a field with an impossible value) or `accepted` (a soft inconsistency that must still decode). Written by `fixturegen/python/gen_forged.py`, deterministic, 428 KB. They come from Spike B (`specs/001-sketch-inspector/research.md`): the library ran out of memory on five of them and accepted nonsense for three, which is why the tool checks every blob before the library sees it.

## Regenerating

`regenerate.sh` rebuilds the whole corpus from scratch and applies the commit policy above. It needs JDK 21 on `PATH`, `JAVA25_HOME` pointing to a JDK 25, and `PYTHON313` pointing to a Python 3.9 to 3.13 that has `datasketches` installed. Generator sources are under [`fixturegen/`](../fixturegen/). Regenerating produces new KLL blobs (KLL is randomized), so a regeneration is a deliberate corpus change, and all KLL expectations are replaced together with their blobs.


## Frequency sketches (family 10), added with BF1

`corpus/java-9.0.0/frequency/`: 24 blobs written by `fixturegen/jvm/genfreq` (datasketches-java 9.0.0, JDK 25), as `FrequentLongsSketch`, `FrequentItemsSketch<Long>` (`ArrayOfLongsSerDe`) and `FrequentItemsSketch<String>` (`ArrayOfStringsSerDe`), for table sizes 8, 64 and 1024: empty, one item, 7 and 40 distinct items (exact, maximum error 0) and purged streams (maximum error above 0). Unlike KLL they are deterministic. `corpus/field/frequency/frequency_strings_platforms.*` is the owner's own string sketch (29 browser platform names, 16,924,897 events), reconstructed from a chat paste and checked: its counts add up to the stream length. The manifest lists every active row with estimate and bounds.

Layout facts (research R16, R18): a 32-byte preamble (preLongs 4), `numActive` 8-byte counts, then the items (8 bytes each for longs; a 4-byte length plus UTF-8 for strings). An empty sketch is 8 bytes with preLongs 1 and flags 5. `FrequentLongsSketch` and `FrequentItemsSketch<Long>` write the same layout (only the order of the items inside the table can differ), so one reader serves both. The item type is not recorded; the size formulas differ, so the bytes decide it for every non-empty blob here (shown in BF2).
`forged/` gained 20 forged and 26 truncated Frequency cases (`freq_*` and `trunc_freq_*`).
