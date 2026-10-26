#!/bin/bash
# Builds the fallback distribution: a private minimal Java runtime plus the fat jar, behind a launcher
# script. It is larger than the native executable (about 35 MB) but is the same Java code on a real JVM.
#
# Usage:  packaging/build-jlink.sh [fat-jar] [out-dir]
# Needs:  JDK 25 (JAVA_HOME, or java and jlink on PATH) and the fat jar (sbt cli/assembly).
# Output: <out-dir>/ (default dist/jlink) holding bin/sketchprobe, runtime/, lib/sketchprobe.jar,
#         lib/sketchprobe.jsa (the class-data-sharing archive, to close the start-up gap), LICENSE, NOTICE.
#         Run <out-dir>/bin/sketchprobe; the directory can be moved.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
JAR="${1:-$ROOT/cli/target/scala-3.9.0/sketchprobe.jar}"
OUT="${2:-$ROOT/dist/jlink}"

JLINK="${JAVA_HOME:+$JAVA_HOME/bin/}jlink"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JLINK" >/dev/null 2>&1 || { echo "jlink not found: set JAVA_HOME to a JDK 25" >&2; exit 1; }
[ -f "$JAR" ] || { echo "fat jar not found: $JAR (run: sbt cli/assembly)" >&2; exit 1; }
MAJOR="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[ "$MAJOR" -ge 25 ] || { echo "JDK 25 or newer is required (found $MAJOR)" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/lib"
# The library needs nothing beyond java.base (research, Spike A). --generate-cds-archive gives the
# runtime the JDK's own class-data archive, which an application archive can then build on.
"$JLINK" --add-modules java.base --strip-debug --no-man-pages --no-header-files \
  --compress zip-6 --generate-cds-archive --output "$OUT/runtime"
cp "$JAR" "$OUT/lib/sketchprobe.jar"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$OUT/"

cat > "$OUT/bin/sketchprobe" <<'LAUNCHER'
#!/bin/sh
# Launcher for the jlink distribution. Follows symlinks, so it can be linked into a bin directory.
self="$0"
while [ -h "$self" ]; do
  dir="$(cd "$(dirname "$self")" && pwd)"
  self="$(readlink "$self")"
  case "$self" in /*) ;; *) self="$dir/$self" ;; esac
done
here="$(cd "$(dirname "$self")/.." && pwd)"
# The application archive speeds up start-up. If it does not match this location it is ignored, and
# recreated when the directory is writable.
# -Xlog:disable keeps the JVM's own warnings (a stale archive after the directory was moved or its files
# touched) off the standard error: a successful run must print nothing there.
exec "$here/runtime/bin/java" -Xlog:disable -Xshare:auto -XX:+AutoCreateSharedArchive \
  -XX:SharedArchiveFile="$here/lib/sketchprobe.jsa" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC \
  -Xmx512m -jar "$here/lib/sketchprobe.jar" "$@"
LAUNCHER
chmod +x "$OUT/bin/sketchprobe"

# Train the archive with one decode per family, if the corpus is at hand (it is in a checkout).
for f in java-9.0.0/hll/hll_HLL_8_k12_n1000_compact java-9.0.0/theta/theta_compact_ordered_n5000 \
         java-9.0.0/frequency/freq_string_m64_exact7; do
  if [ -f "$ROOT/fixtures/corpus/$f.bin" ]; then "$OUT/bin/sketchprobe" "$ROOT/fixtures/corpus/$f.bin" >/dev/null 2>&1 || true; fi
done
if [ -f "$OUT/lib/sketchprobe.jsa" ]; then echo "class-data archive: $(du -h "$OUT/lib/sketchprobe.jsa" | cut -f1)"
else echo "note: no class-data archive was created (the launcher still works, a little slower to start)"; fi
echo "built $OUT ($(du -sh "$OUT" | cut -f1)); run $OUT/bin/sketchprobe"
