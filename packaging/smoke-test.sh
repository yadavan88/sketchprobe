#!/bin/bash
# Checks a built sketchprobe executable: one decode per family compared with the reports the tests
# hold as golden output, the version, the failure statuses, and the hostile blobs (no crash).
#
# Usage:  packaging/smoke-test.sh <path-to-sketchprobe>
# Run from anywhere; it finds the repository's fixtures from its own location.
set -uo pipefail
BIN="${1:?usage: smoke-test.sh <path-to-sketchprobe>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GOLDEN="$ROOT/cli/src/test/resources/golden"
CORPUS="$ROOT/fixtures/corpus"
fail=0
ok() { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

# check <label> <fixture .bin> <golden name> [extra args]
report() {
  local label="$1" bin="$2" golden="$3"; shift 3
  local got
  got="$(base64 < "$bin" | tr -d '\n' | "$BIN" "$@" 2>&1)"
  if [ "$got" = "$(cat "$GOLDEN/$golden.txt")" ]; then ok "$label"; else bad "$label (output differs from golden/$golden.txt)"; fi
}

echo "smoke test: $BIN"
v="$("$BIN" --version 2>&1)"; case "$v" in sketchprobe\ *) ok "--version: $v" ;; *) bad "--version printed '$v'" ;; esac
report "HLL" "$CORPUS/java-6.2.0/hll/hll_HLL_8_k12_n1000_compact.bin" hll_HLL_8_k12_n1000_compact
report "Theta" "$CORPUS/java-6.2.0/theta/theta_compact_ordered_n5000.bin" theta_compact_ordered_n5000
report "KLL (--type float)" "$CORPUS/java-6.2.0/kll/kll_float_k200_n10000.bin" kll_float_k200_n10000 --type float
report "Frequency (string items)" "$CORPUS/field/frequency/frequency_strings_platforms.bin" frequency_strings_platforms

# statuses: 1 unreadable, 2 not a sketch, 3 recognized but not decoded, 64 usage
status() { local want="$1" label="$2"; shift 2; "$@" >/dev/null 2>&1; local got=$?; [ "$got" = "$want" ] && ok "$label: status $got" || bad "$label: status $got, wanted $want"; }
status 1 "text" bash -c "echo hello there | '$BIN'"
status 2 "unknown family" bash -c "printf '\\001\\001\\143\\000\\000\\000\\000\\000' | '$BIN'"
status 3 "truncated" bash -c "head -c 30 '$CORPUS/java-6.2.0/hll/hll_HLL_8_k12_n1000_compact.bin' | '$BIN'"
status 64 "usage error" "$BIN" --frobnicate
j="$("$BIN" --json "$CORPUS/java-6.2.0/hll/hll_HLL_8_k12_n1000_compact.bin" 2>&1)"
case "$j" in '{"schemaVersion":1,"status":0,'*) ok "--json" ;; *) bad "--json printed '${j:0:80}'" ;; esac

# Hostile blobs: every one must end in a normal status (0 to 3 or 64), never a crash or a hang.
if command -v python3 >/dev/null 2>&1; then
  crashed=0; n=0
  while IFS=$'\t' read -r file typ; do
    args=(); [ -n "$typ" ] && args=(--type "$typ")
    if command -v timeout >/dev/null 2>&1; then timeout 10 "$BIN" ${args[@]+"${args[@]}"} "$ROOT/fixtures/forged/$file" >/dev/null 2>&1; else "$BIN" ${args[@]+"${args[@]}"} "$ROOT/fixtures/forged/$file" >/dev/null 2>&1; fi
    s=$?; n=$((n + 1))
    case "$s" in 0|1|2|3|64) ;; *) crashed=$((crashed + 1)); echo "    $file: status $s" ;; esac
  done < <(python3 -I - "$ROOT/fixtures/forged/manifest.json" <<'PY'
import json, sys
for c in json.load(open(sys.argv[1]))["cases"]:
    print(c["file"] + "\t" + (c["itemType"] if c["itemType"] in ("float", "double", "long", "string") else ""))
PY
)
  [ "$crashed" = 0 ] && ok "$n hostile blobs: none crashed or hung" || bad "$crashed of $n hostile blobs crashed or hung"
else
  echo "  skip  hostile blobs (python3 not found)"
fi

# A text input at the size limit (62 MB, valid as hex and as base64) must be refused, not exhaust the
# memory: the heap cap (512 MB) is what the 64 MiB limit was sized for.
big="$(head -c 62000000 /dev/zero | tr '\0' 'A' | "$BIN" 2>&1 >/dev/null; echo "status=$?")"
case "$big" in *status=1) ok "62 MB input: refused cleanly (status 1)" ;; *) bad "62 MB input: ${big: -60}" ;; esac

if [ "$fail" = 0 ]; then echo "smoke test passed"; else echo "smoke test FAILED: $fail check(s)"; exit 1; fi
