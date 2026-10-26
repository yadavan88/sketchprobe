#!/usr/bin/env bash
# Rebuild fixtures/corpus from scratch and apply the commit policy from fixtures/README.md.
#
# Needs:  JDK 21 on PATH (the 6.2.0, 8.0.0 and older-line generators),
#         JAVA25_HOME pointing to a JDK 25 (the 9.0.0 generator),
#         PYTHON313 pointing to a Python 3.9 to 3.13 with the `datasketches` package (the C++ generator).
# KLL is randomized, so running this replaces every KLL blob together with its expected values.
set -euo pipefail
: "${JAVA25_HOME:?set JAVA25_HOME to a JDK 25}"
: "${PYTHON313:?set PYTHON313 to a Python with the datasketches package}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORPUS="${CORPUS:-$ROOT/fixtures/corpus}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

rm -rf "$CORPUS"; mkdir -p "$CORPUS"

echo "== java 6.2.0 (everything)";  "$ROOT/fixturegen/jvm/gen6/run.sh" "$CORPUS/java-6.2.0"
echo "== java 9.0.0 (everything)";  JAVA_HOME="$JAVA25_HOME" "$ROOT/fixturegen/jvm/gen9/run.sh" "$CORPUS/java-9.0.0"
echo "== java 9.0.0 (Frequency)";   JAVA_HOME="$JAVA25_HOME" "$ROOT/fixturegen/jvm/genfreq/run.sh" "$CORPUS/java-9.0.0"
echo "== cpp 5.2.0 (everything)";   "$PYTHON313" -I "$ROOT/fixturegen/python/gen_cpp.py" "$CORPUS/cpp-5.2.0"

# These lines write HLL and Theta byte-identically to 6.2.0, so only their KLL blobs are kept.
echo "== java 8.0.0 (KLL only)";    "$ROOT/fixturegen/jvm/gen8/run.sh" "$WORK/java-8.0.0"
mkdir -p "$CORPUS/java-8.0.0"; cp -R "$WORK/java-8.0.0/kll" "$CORPUS/java-8.0.0/kll"
for v in 3.3.0 5.0.2; do
  echo "== java $v (KLL only)";     "$ROOT/fixturegen/jvm/genold/run.sh" "$v" "$WORK/java-$v"
  mkdir -p "$CORPUS/java-$v"; cp -R "$WORK/java-$v/kll" "$CORPUS/java-$v/kll"
done

# Re-verify the claim behind the policy: for HLL and Theta, the other lines match 6.2.0 byte for byte.
for v in 3.3.0 4.2.0 5.0.2 6.0.0 6.1.1; do
  [ -d "$WORK/java-$v" ] || "$ROOT/fixturegen/jvm/genold/run.sh" "$v" "$WORK/java-$v" >/dev/null
done
for dir in "$WORK"/java-*; do
  for fam in hll theta; do
    [ -d "$dir/$fam" ] || continue
    for f in "$dir/$fam"/*.bin; do
      cmp -s "$f" "$CORPUS/java-6.2.0/$fam/$(basename "$f")" || { echo "NOT IDENTICAL to 6.2.0: $f" >&2; exit 1; }
    done
  done
done
echo "done: $(ls "$CORPUS"/*/*/*.bin | wc -l | tr -d ' ') blobs in $CORPUS"
