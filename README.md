# sketchprobe

inspect and decode Apache DataSketches binaries

Paste a serialized sketch copied from Mongo, Parquet, Delta or Databricks and get a readable report:

```bash
pbpaste | sketchprobe
```

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

**Status:** the first release is not published yet; the install commands below work once it is. Everything else here works from a checkout (see Building).

- **Families:** HLL, Theta (Alpha, QuickSelect, Compact including the compressed layout, Union, Intersection), KLL (float, double or long items, compact and working layouts) and Frequency (long or string items). Every other family ID is recognized and named, and not decoded.
- **Input:** base64 (standard or URL-safe, wrapped or not), hex (`0x…`, `\x…`), Mongo Extended JSON (current and legacy, also nested in a document and with a trailing comma), a quoted JSON string, or the raw bytes. The format is detected and shown; `--input-format` forces one.
- **Safe on hostile input:** it reads sketches with the Apache DataSketches Java library (9.0.0), after its own checks, because the library trusts the sizes in a header: a forged blob can make it run out of memory. Truncated, bit-flipped, forged and random input all end in a clear message and a distinct exit status, never a crash.
- **Self-contained:** the released executable needs no Java. License: Apache-2.0.

## Install

```bash
# Linux (x86-64) and macOS (Apple silicon): downloads the release, checks its checksum, installs one file
curl -fsSL https://raw.githubusercontent.com/yadavan88/sketchprobe/main/packaging/install.sh | sh

# macOS with Homebrew (no "unidentified developer" dialog)
brew tap yadavan88/sketchprobe https://github.com/yadavan88/homebrew-sketchprobe
brew install sketchprobe
```

`install.sh` puts `sketchprobe` in `~/.local/bin` (`--dir` changes it, `--version` picks a release, `--jlink` installs the bundled-runtime build instead). Each release also has the fat jar (`java -jar sketchprobe.jar`, needs JDK 25) and a `SHA256SUMS` file. Intel Macs and Windows are not supported.

## Using it

Copy the value, run `pbpaste | sketchprobe` (on Linux, `xclip -o | sketchprobe`, or give a file: `sketchprobe value.b64`). Where the value comes from, and what it looks like:

```bash
# MongoDB: a binary field printed by mongosh or Compass, as it is. The braces, quotes and a trailing
# comma are fine; it can be a whole document with one binary field. (This one is an empty sketch.)
echo '{ "$binary" : "AQEKAwMFAAA=", "$type" : "00" },' | sketchprobe

# Databricks / Spark SQL: ask for the sketch column as text, then copy the cell
#   SELECT base64(sketch_col) FROM t LIMIT 1        -- or hex(sketch_col)
pbpaste | sketchprobe

# Parquet / Delta: write the value out as base64 and give the file
python3 -c "import pyarrow.parquet as pq,base64,sys; t=pq.read_table('f.parquet'); print(base64.b64encode(t['sketch'][0].as_py()).decode())" > value.b64
sketchprobe value.b64

# A KLL sketch does not say what its items are; tell it. (A Frequency sketch works out long or string
# from the blob's size, and says so; --type long or --type string overrides.)
pbpaste | sketchprobe --type double
```

(The input forms were tested against corpus blobs and encoded variants of them; the Databricks and Parquet recipes are examples of where such a value comes from, not tested against those systems.)

Useful options (`sketchprobe --help` has them all):

| Option | Effect |
|---|---|
| `--json` | one line of JSON instead of text ([schema](docs/report.schema.json)); failures print a JSON object too |
| `--type float\|double\|long\|string` | the item type: KLL takes float, double or long; Frequency takes long or string |
| `--show-hashes`, `--show-items` | list the retained Theta hashes, KLL items, or all Frequency items (the report lists the top 20 by default) |
| `--ranks 0.5,0.99` | the quantiles to report for KLL |
| `--seed N` | the Theta seed, to check the stored seed hash (a mismatch is a warning; the values do not depend on it) |
| `--header-only` | identify the sketch and stop |
| `--input-format F` | `auto` (default), `mongo`, `hex`, `base64`, `base64url`, `raw` |

### Exit status

| Status | Meaning |
|---|---|
| 0 | decoded (including a warning for medium confidence, a Theta seed mismatch, an ignored option) |
| 1 | unreadable input: empty, not any known text format, several blobs, ambiguous (`--input-format` decides), over the size limit, an unreadable file |
| 2 | not a recognizable sketch: fewer than 8 bytes, an unknown family ID, an invalid field. The raw header is shown |
| 3 | recognized but not decoded: an unsupported family, a truncated or forged-size blob, the big-endian layout, a missing item type |
| 64 | usage error |

A failure prints a plain reason and, where one exists, a next step on standard error, and with `--json` the same as an object on standard output.

## Building

You need **JDK 25**: the Apache DataSketches library the tool uses is compiled for Java 25. Point `JAVA_HOME` at it before running sbt:

```bash
export JAVA_HOME=/path/to/jdk-25     # for example a Temurin 25
sbt test
```

On an older JDK the build compiles, but the tests that load the library fail with `UnsupportedClassVersionError`.

### Running it from a checkout

```bash
sbt cli/assembly                                   # the fat jar: cli/target/scala-3.9.0/sketchprobe.jar
pbpaste | java -jar cli/target/scala-3.9.0/sketchprobe.jar
```

### The two distributions (`packaging/`)

Both start from the fat jar, and `packaging/smoke-test.sh` checks either one: a decode per family compared with the reports the tests hold as golden output, the version, the failure statuses, and all the hostile blobs in `fixtures/forged/` (none may crash or hang).

| | Native executable | `jlink` runtime |
|---|---|---|
| Needs to run | nothing | nothing (it carries its own minimal Java) |
| Size | about 19 MB, one file | about 78 MB directory (35 MB of it the runtime) |
| Start-up (one decode, Apple silicon) | about 8 ms | about 100 ms (170 ms for a plain `java -jar`) |
| Build needs | GraalVM 25 | JDK 25 |

```bash
sbt cli/assembly

# native executable, in dist/native/ (point GRAALVM_HOME at a GraalVM 25, for example GraalVM CE 25.0.2)
GRAALVM_HOME=/path/to/graalvm-25 ./packaging/build-native.sh
./packaging/smoke-test.sh dist/native/sketchprobe

# jlink runtime, in dist/jlink/ (run dist/jlink/bin/sketchprobe; the directory can be moved)
JAVA_HOME=/path/to/jdk-25 ./packaging/build-jlink.sh
./packaging/smoke-test.sh dist/jlink/bin/sketchprobe
```

Notes:
- Run the scripts as `./packaging/build-native.sh`, not as `bash packaging/build-native.sh`. They use the system `/bin/bash`; on an Apple silicon Mac an Intel Homebrew `bash` that comes first on `PATH` makes every child process run as x86_64, and the arm64 GraalVM's toolchain check then fails ("missing compatible architecture"). The script says so and stops.
- The native image is built with `-H:-InstallExitHandlers` (no SIGINT or SIGTERM handler: the tool has nothing to shut down). Without it, the image needs a named POSIX semaphore at start-up, which some sandboxes forbid, and it crashes in them with `CSunMiscSignal.open() failed`. With it, the same executable runs everywhere we tried, including such a sandbox.
- The heap is capped at 512 MB in both (`-R:MaxHeapSize` and `-Xmx512m`), so a hostile input cannot take the machine's memory.
- `-march=compatibility` makes the native executable run on any CPU of its architecture, not only the one that built it.
- The jlink launcher uses a class-data archive (`lib/sketchprobe.jsa`) to start faster; if the directory is moved it is ignored and rebuilt when the directory is writable.

Design documents live in [`specs/001-sketch-inspector/`](specs/001-sketch-inspector/), and the background is in [`CONTEXT.md`](CONTEXT.md) and [`PLAN.md`](PLAN.md).

Apache DataSketches is a trademark of the Apache Software Foundation. This project is not affiliated with or endorsed by it.
