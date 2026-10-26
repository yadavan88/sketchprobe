#!/usr/bin/env bash
# datasketches-java 8.0.0 on JDK 21 with --enable-preview. Usage: run.sh <out-dir>
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/../gen68/build-and-run.sh" 8.0.0 6.0.0 yes fixturegen/jvm/gen8 "${1:?usage: run.sh <out-dir>}"
