#!/usr/bin/env bash
# Shared runner for the 6.2.0 and 8.0.0 fixture generators (JDK 21).
# Usage: build-and-run.sh <java-lib-version> <memory-lib-version> <preview:yes|no> <script-label> <out-dir>
set -euo pipefail
LIB_VER="$1"; MEM_VER="$2"; PREVIEW="$3"; LABEL="$4"; OUT="$5"
HERE="$(cd "$(dirname "$0")" && pwd)"
MAVEN="https://repo1.maven.org/maven2/org/apache/datasketches"
LIB="$HERE/lib/$LIB_VER"; BUILD="$HERE/out/$LIB_VER"
mkdir -p "$LIB" "$BUILD"

fetch() { # <artifact> <version>
  local art="$1" ver="$2" jar="$LIB/$1-$2.jar"
  if [ ! -f "$jar" ]; then
    curl -fsS -o "$jar" "$MAVEN/$art/$ver/$art-$ver.jar"
    local want got
    want="$(curl -fsS "$MAVEN/$art/$ver/$art-$ver.jar.sha1" | awk '{print $1}')"
    got="$(shasum -a 1 "$jar" | awk '{print $1}')"
    [ "$want" = "$got" ] || { echo "checksum mismatch for $jar" >&2; rm -f "$jar"; exit 1; }
  fi
}
fetch datasketches-java "$LIB_VER"
fetch datasketches-memory "$MEM_VER"
CP="$LIB/datasketches-java-$LIB_VER.jar:$LIB/datasketches-memory-$MEM_VER.jar"

FLAGS=()
if [ "$PREVIEW" = "yes" ]; then FLAGS=(--enable-preview); fi

JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "$JAVA_MAJOR" != "21" ]; then echo "these generators need JDK 21 (found $JAVA_MAJOR)" >&2; exit 1; fi

javac --release 21 ${FLAGS[@]+"${FLAGS[@]}"} -Xlint:-options -Xlint:-preview -cp "$CP" -d "$BUILD" "$HERE/Gen68.java"
java ${FLAGS[@]+"${FLAGS[@]}"} -cp "$CP:$BUILD" Gen68 "$OUT" "$LABEL" "$LIB_VER"
