#!/bin/bash
# Builds the browser version: sketchprobe compiled to WebAssembly, plus the page that uses it.
#
# Usage:  packaging/build-web.sh [out-dir]
# Needs:  Oracle GraalVM 25.1 or newer (Web Image is not in GraalVM Community; set GRAALVM_HOME to it),
#         Binaryen 119 or newer (wasm-as on PATH; macOS: brew install binaryen), and sbt on JDK 25.
# Output: <out-dir> (default dist/web): index.html, sketchprobe.js and sketchprobe.js.wasm. Serve it over
#         HTTP (python3 -m http.server -d dist/web); a file:// URL does not work.
#
# Web Image is experimental. Known limit: a byte read from a heap MemorySegment comes out signed, so the
# compressed Theta layout is refused before it reaches the library (Playground.scala, docs/web.md).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${1:-$ROOT/dist/web}"

[ -n "${GRAALVM_HOME:-}" ] || { echo "set GRAALVM_HOME to an Oracle GraalVM 25.1 or newer" >&2; exit 1; }
NI="$GRAALVM_HOME/bin/native-image"
[ -x "$NI" ] || { echo "native-image not found in $GRAALVM_HOME/bin" >&2; exit 1; }
command -v wasm-as >/dev/null 2>&1 || { echo "wasm-as (Binaryen) not found on PATH; macOS: brew install binaryen" >&2; exit 1; }
# The Web Image API is a module of Oracle GraalVM, so this also stops a GraalVM Community install.
[ -f "$GRAALVM_HOME/jmods/org.graalvm.webimage.api.jmod" ] || { echo "$GRAALVM_HOME has no Web Image API: use Oracle GraalVM 25.1 or newer" >&2; exit 1; }

if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = x86_64 ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then
  echo "this shell runs as x86_64 on an Apple silicon Mac; run it as ./packaging/build-web.sh from an arm64 shell" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. The Scala code and its libraries: the class path sbt itself would run with.
cd "$ROOT"
sbt -batch web/compile >&2
# The class path is one line of sbt's output among its own "[info] ..." / "[success] ..." lines. On the CI runner it was
# not on standard output as a bare line, so read both streams, drop a leading "[info] " if there is one, and pick the
# line by being an absolute path. Colour is off so no escape code hides the prefix. On failure show what sbt printed.
SBT_OUT="$(sbt -batch -Dsbt.color=false 'export web/Runtime/fullClasspath' 2>&1 || true)"
CP="$(printf '%s\n' "$SBT_OUT" | sed 's/^\[info\] //' | grep '^/' | tail -1 || true)"
case "$CP" in
  *web/target/*) ;;
  *) echo "could not read the class path from sbt; its output was:" >&2; printf '%s\n' "$SBT_OUT" >&2; exit 1 ;;
esac

# 2. The one Java file, against the Web Image API from the GraalVM install.
mkdir -p "$WORK/api" "$WORK/classes"
"$GRAALVM_HOME/bin/jmod" extract --dir "$WORK/api" "$GRAALVM_HOME/jmods/org.graalvm.webimage.api.jmod"
"$GRAALVM_HOME/bin/javac" -cp "$CP:$WORK/api/classes" -d "$WORK/classes" "$ROOT/web/js/sketchprobe/web/Bridge.java"

# 3. WebAssembly. --tool:svm-wasm has to be the first argument.
mkdir -p "$OUT"
rm -f "$OUT/sketchprobe.js" "$OUT/sketchprobe.js.wasm" "$OUT/sketchprobe.js.wat"
(cd "$OUT" && "$NI" --tool:svm-wasm -cp "$CP:$WORK/classes:$WORK/api/classes" sketchprobe.web.Bridge -o sketchprobe)

# The build also writes a text dump of the module for debugging; it is about 30 times the size of the module.
rm -f "$OUT/sketchprobe.js.wat"

cp "$ROOT/web/page/index.html" "$OUT/index.html"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$OUT/"
echo "built $OUT ($(du -h "$OUT/sketchprobe.js.wasm" | cut -f1) of WebAssembly)"
