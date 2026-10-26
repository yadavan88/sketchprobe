#!/usr/bin/env bash
# One-off generator for older datasketches-java lines on JDK 21, no flags.
# Usage: run.sh <java-lib-version> <out-dir>      e.g. run.sh 5.0.2 /tmp/out-5.0.2
set -euo pipefail
LIB_VER="${1:?usage: run.sh <java-lib-version> <out-dir>}"; OUT="${2:?usage: run.sh <java-lib-version> <out-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MAVEN="https://repo1.maven.org/maven2/org/apache/datasketches"
LIB="$HERE/lib/$LIB_VER"; BUILD="$HERE/out/$LIB_VER"
mkdir -p "$LIB" "$BUILD"
MEM_VER="$(curl -fsS "$MAVEN/datasketches-java/$LIB_VER/datasketches-java-$LIB_VER.pom" | grep -E '<datasketches-memory.version>' | sed 's/<[^>]*>//g' | tr -d ' ' | head -1)"
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
fetch datasketches-java "$LIB_VER"; fetch datasketches-memory "$MEM_VER"
CP="$LIB/datasketches-java-$LIB_VER.jar:$LIB/datasketches-memory-$MEM_VER.jar"
MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "$MAJOR" != "21" ]; then echo "needs JDK 21 (found $MAJOR)" >&2; exit 1; fi
SRC="$HERE/GenOld.java"
# The 3.x line keeps Family and Util in a different package: rewrite the imports for it at build time.
case "$LIB_VER" in
  3.*) mkdir -p "$BUILD/src"; sed 's/org\.apache\.datasketches\.common\.Family/org.apache.datasketches.Family/' "$HERE/GenOld.java" > "$BUILD/src/GenOld.java"; SRC="$BUILD/src/GenOld.java";;
esac
javac --release 21 -Xlint:-options -cp "$CP" -d "$BUILD" "$SRC"
java -cp "$CP:$BUILD" GenOld "$OUT" "fixturegen/jvm/genold" "$LIB_VER"
