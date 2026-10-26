# sketchprobe

[![CI](https://github.com/yadavan88/sketchprobe/actions/workflows/ci.yml/badge.svg)](https://github.com/yadavan88/sketchprobe/actions/workflows/ci.yml)
[![Native](https://github.com/yadavan88/sketchprobe/actions/workflows/native.yml/badge.svg)](https://github.com/yadavan88/sketchprobe/actions/workflows/native.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**Inspect and decode [Apache DataSketches](https://datasketches.apache.org/) binaries.** Copy a serialized sketch out of MongoDB, Parquet, Delta or Databricks, and get a readable report.

```bash
pbpaste | sketchprobe
```

![sketchprobe demo](docs/demo/demo.gif)

The report for a Frequency sketch looks like this:

```
Frequency sketch (string items)                                 confidence: high

Input             base64, 620 bytes
Stream length     16,924,897
Items tracked     29 (the table holds up to 48)
Maximum error     0 (every count is exact)
Item type         string (chosen from the blob's size)

Items, largest first (top 20 of 29)
  Item               Count
  iPhone        12,866,315
  MacIntel       3,361,170
  ...
```

> **Status:** the first release is not published yet. The install commands below work once it is. Everything else works from a checkout (see [Building](docs/building.md)).

## Features

- **Families:** HLL, Theta (Alpha, QuickSelect, Compact including the compressed layout, Union, Intersection), KLL (float, double or long items, compact and working layouts) and Frequency (long or string items). Any other family is recognized and named, not decoded.
- **Any text form of the value:** base64 (standard or URL-safe), hex (`0x…`, `\x…`), Mongo Extended JSON (current and legacy, also nested in a document), a quoted string, or raw bytes. The format is detected and shown.
- **Safe on hostile input:** sketches are read with the Apache DataSketches Java library (9.0.0), but only after the tool's own checks, because the library trusts the sizes in a header and a forged blob can make it run out of memory. Truncated, bit-flipped, forged and random input all end in a clear message and a distinct exit status, never a crash.
- **Read-only and offline:** it never writes a file or opens a connection. A build check fails the compile if production code uses such an API.
- **Self-contained:** the released executable needs no Java.

## Install

```bash
# Linux (x86-64) and macOS (Apple silicon): downloads the release, verifies its checksum, installs one file
curl -fsSL https://raw.githubusercontent.com/yadavan88/sketchprobe/main/packaging/install.sh | sh

# macOS with Homebrew
brew tap yadavan88/sketchprobe https://github.com/yadavan88/homebrew-sketchprobe
brew install sketchprobe
```

`install.sh` puts `sketchprobe` in `~/.local/bin` (`--dir` changes it, `--version` picks a release, `--jlink` installs the bundled-runtime build). Each release also has the fat jar (`java -jar sketchprobe.jar`, needs JDK 25) and a `SHA256SUMS` file. Intel Macs and Windows are not supported.

## Usage

Give it the sketch in whichever way is convenient:

```bash
sketchprobe 'CgEHDAAICAKn...'       # the value as text (quote it; Mongo JSON needs quotes)
pbpaste | sketchprobe               # from the clipboard (Linux: xclip -o | sketchprobe)
sketchprobe --file value.b64        # from a file: raw sketch bytes, or text in any format
sketchprobe                         # at a terminal: asks you to paste; a blank line ends it
```

A value that starts with `-` goes after `--` (`sketchprobe -- -abc...`). Pasting at the prompt needs no quoting and leaves nothing in your shell history.

Where the value comes from:

```bash
# MongoDB: a binary field as mongosh or Compass prints it. Braces, quotes and a trailing comma are fine.
echo '{ "$binary" : "AQEKAwMFAAA=", "$type" : "00" },' | sketchprobe

# Databricks / Spark SQL: select the column as text, then copy the cell
#   SELECT base64(sketch_col) FROM t LIMIT 1        -- or hex(sketch_col)

# Parquet / Delta: write the value out as base64 and give the file
python3 -c "import pyarrow.parquet as pq,base64; t=pq.read_table('f.parquet'); print(base64.b64encode(t['sketch'][0].as_py()).decode())" > value.b64
sketchprobe --file value.b64

# A KLL sketch does not record its item type, so say it. (Frequency works it out from the size.)
pbpaste | sketchprobe --type double
```

The input forms are tested against corpus blobs and encoded variants of them. The Databricks and Parquet lines show where such a value comes from, and are not tested against those systems.

### Options

| Option | Effect |
|---|---|
| `--json` | one line of JSON instead of text ([schema](docs/report.schema.json)); failures print a JSON object too |
| `--file FILE` | read the sketch from a file (`-` is standard input) |
| `--type float\|double\|long\|string` | the item type: KLL takes float, double or long; Frequency takes long or string |
| `--show-hashes`, `--show-items` | list retained Theta hashes, KLL items, or all Frequency items (the report shows the top 20 by default) |
| `--ranks 0.5,0.99` | the quantiles to report for KLL |
| `--seed N` | the Theta seed, to check the stored seed hash (a mismatch is a warning; the values do not depend on it) |
| `--header-only` | identify the sketch and stop |
| `--input-format F` | `auto` (default), `mongo`, `hex`, `base64`, `base64url`, `raw` |

`sketchprobe --help` lists everything.

### Exit status

| Status | Meaning |
|---|---|
| 0 | decoded (possibly with a warning: medium confidence, a Theta seed mismatch, an ignored option) |
| 1 | unreadable input: empty, not a known text format, several blobs, ambiguous format, over the size limit, unreadable file |
| 2 | not a recognizable sketch: fewer than 8 bytes, an unknown family ID, an invalid field (the raw header is shown) |
| 3 | recognized but not decoded: unsupported family, truncated or forged-size blob, big-endian layout, missing item type |
| 64 | usage error |

A failure prints a plain reason and, where there is one, a next step on standard error. With `--json` the same is also an object on standard output.

## Building

You need **JDK 25**. The DataSketches library is compiled for Java 25.

```bash
export JAVA_HOME=/path/to/jdk-25
sbt test                    # the tests
sbt cli/assembly            # the fat jar: cli/target/scala-3.9.0/sketchprobe.jar
```

The native executable and `jlink` builds, with sizes, start-up times and notes, are in [docs/building.md](docs/building.md).

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Apache DataSketches is a trademark of the Apache Software Foundation. This project is not affiliated with or endorsed by it.
