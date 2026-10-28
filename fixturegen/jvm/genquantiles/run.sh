#!/usr/bin/env bash
# Quantiles sketches (family 8) with datasketches-java 9.0.0 on JDK 25, no flags. Usage: run.sh <out-dir> [--field <file.bin> <name>]
# Writes only <out-dir>/frequency; shares the library jar download with gen9.
# Needs a JDK 25 on PATH (or JAVA_HOME pointing to one).
set -euo pipefail
OUT="${1:?usage: run.sh <out-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB_VER=9.0.0
MAVEN="https://repo1.maven.org/maven2/org/apache/datasketches"
LIB="$HERE/../gen9/lib/$LIB_VER"; BUILD="$HERE/out/$LIB_VER"
mkdir -p "$LIB" "$BUILD"
JAR="$LIB/datasketches-java-$LIB_VER.jar"
if [ ! -f "$JAR" ]; then
  curl -fsS -o "$JAR" "$MAVEN/datasketches-java/$LIB_VER/datasketches-java-$LIB_VER.jar"
  want="$(curl -fsS "$MAVEN/datasketches-java/$LIB_VER/datasketches-java-$LIB_VER.jar.sha1" | awk '{print $1}')"
  got="$(shasum -a 1 "$JAR" | awk '{print $1}')"
  [ "$want" = "$got" ] || { echo "checksum mismatch for $JAR" >&2; rm -f "$JAR"; exit 1; }
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"; JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
MAJOR="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "$MAJOR" -lt 25 ]; then echo "this generator needs JDK 25 or newer (found $MAJOR)" >&2; exit 1; fi
"$JAVAC" --release 25 -Xlint:-options -cp "$JAR" -d "$BUILD" "$HERE/GenQuantiles.java"
"$JAVA" -cp "$JAR:$BUILD" GenQuantiles "$OUT" fixturegen/jvm/genquantiles "$LIB_VER" "${@:2}"
