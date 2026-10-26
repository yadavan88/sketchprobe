#!/usr/bin/env bash
# datasketches-java 6.2.0 on JDK 21, no flags. Usage: run.sh <out-dir>
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/../gen68/build-and-run.sh" 6.2.0 3.0.2 no fixturegen/jvm/gen6 "${1:?usage: run.sh <out-dir>}"
