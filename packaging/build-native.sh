#!/bin/bash
# Builds the native executable: no JDK is needed to run it.
#
# Usage:  packaging/build-native.sh [fat-jar] [out-dir]
# Needs:  GraalVM 25 (set GRAALVM_HOME to it, or have native-image on PATH) and the fat jar
#         (sbt cli/assembly, which writes cli/target/scala-3.9.0/sketchprobe.jar).
# Output: <out-dir>/sketchprobe (default dist/native), plus LICENSE and NOTICE beside it.
#
# Flags, and why:
#   --no-fallback            never produce an image that needs a JDK at run time
#   -R:MaxHeapSize=512m      a run-time cap on the heap: a hostile input cannot take the machine's memory
#   --gc=serial              the small single-threaded collector; this is a short-lived command
#   -march=compatibility     runs on any CPU of the architecture, not only the one that built it
#   -H:-InstallExitHandlers  no SIGINT/SIGTERM handler: the tool has no shutdown work, and the handler
#                            needs a named POSIX semaphore, which some sandboxes forbid (research R13)
# Reflection and resource configuration, if the build ever needs any, goes in packaging/native-image/
# (the build picks up every file there).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
JAR="${1:-$ROOT/cli/target/scala-3.9.0/sketchprobe.jar}"
OUT="${2:-$ROOT/dist/native}"

NI="${GRAALVM_HOME:+$GRAALVM_HOME/bin/}native-image"
command -v "$NI" >/dev/null 2>&1 || { echo "native-image not found: set GRAALVM_HOME to a GraalVM 25 installation" >&2; exit 1; }
[ -f "$JAR" ] || { echo "fat jar not found: $JAR (run: sbt cli/assembly)" >&2; exit 1; }
"$NI" --version | head -1 | grep -q ' 25\.' || { echo "GraalVM 25 is required, found: $("$NI" --version | head -1)" >&2; exit 1; }

# On Apple silicon, an x86_64 bash (for example an Intel Homebrew bash that comes first on PATH and is
# picked by `bash packaging/build-native.sh` or by `#!/usr/bin/env bash`) makes every child process
# prefer x86_64, and the compiler-toolchain check of the arm64 GraalVM then fails with "missing compatible
# architecture". This script starts with `#!/bin/bash` (the system bash, which is native), so run it as
# ./packaging/build-native.sh. Stop with a clear message instead of that error.
if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = x86_64 ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then
  echo "this shell runs as x86_64 on an Apple silicon Mac, which breaks the native-image toolchain check." >&2
  echo "run it as ./packaging/build-native.sh (not 'bash packaging/build-native.sh'), or start an arm64 shell: arch -arm64 /bin/zsh" >&2
  exit 1
fi

mkdir -p "$OUT"
# NATIVE_IMAGE_ARGS adds flags, for example "--static --libc=musl" for a fully static Linux executable
# (needs a musl toolchain; the CI workflow sets it up).
# shellcheck disable=SC2086
"$NI" --no-fallback --gc=serial -march=compatibility \
  -R:MaxHeapSize=512m -H:-InstallExitHandlers ${NATIVE_IMAGE_ARGS:-} \
  -H:ConfigurationFileDirectories="$HERE/native-image" \
  -jar "$JAR" -o "$OUT/sketchprobe"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$OUT/"
echo "built $OUT/sketchprobe ($(du -h "$OUT/sketchprobe" | cut -f1))"
