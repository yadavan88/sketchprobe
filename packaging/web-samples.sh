#!/bin/bash
# Prints the example sketches of the web page as a JavaScript object literal, read from the reference corpus so
# they cannot drift from it. build-web.sh puts the output where web/page/index.html has the @SAMPLES@ marker.
# Each entry: key|label|corpus file|--type value|ranks
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/fixtures/corpus/java-9.0.0"

ENTRIES='hll|Sample HLL sketch|hll/hll_HLL_4_k12_n50_compact.bin||
theta|Sample Theta sketch|theta/theta_compact_unordered_n100_seed12345.bin||
kll|Sample KLL sketch|kll/kll_float_k200_n10_updatable.bin|float|0.5,0.99
frequency|Sample Frequency sketch|frequency/freq_string_m64_exact40.bin|string|
quantiles|Sample Quantiles sketch|quantiles/quantiles_k32_n10_compact.bin||0.5,0.99'

printf '['
sep=''
while IFS='|' read -r key label file type ranks; do
  printf '%s{key:"%s",label:"%s",type:"%s",ranks:"%s",value:"%s"}' \
    "$sep" "$key" "$label" "$type" "$ranks" "$(base64 < "$C/$file" | tr -d '\n')"
  sep=','
done <<< "$ENTRIES"
printf ']\n'
