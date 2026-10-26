#!/bin/sh
# Writes a release's version and checksums into the formula.
# Usage: packaging/homebrew/update-formula.sh <version> <SHA256SUMS> [formula]   (writes in place)
set -eu
version="${1:?usage: update-formula.sh <version> <SHA256SUMS> [formula]}"
sums="${2:?usage: update-formula.sh <version> <SHA256SUMS> [formula]}"
formula="${3:-$(dirname "$0")/sketchprobe.rb}"
sha() {
  s="$(grep "  sketchprobe-$1.tar.gz\$" "$sums" | cut -d' ' -f1 || true)"
  [ -n "$s" ] || { echo "SHA256SUMS has no entry for sketchprobe-$1.tar.gz" >&2; exit 1; }
  printf '%s' "$s"
}
mac="$(sha macos-arm64)"; linux="$(sha linux-x86_64)"
sed -e "s#^  version \".*\" \# version#  version \"${version#v}\" \# version#" \
    -e "s#sha256 \"[0-9a-f]*\" \# macos-arm64#sha256 \"$mac\" \# macos-arm64#" \
    -e "s#sha256 \"[0-9a-f]*\" \# linux-x86_64#sha256 \"$linux\" \# linux-x86_64#" \
    "$formula" > "$formula.tmp" && mv "$formula.tmp" "$formula"
echo "formula updated to ${version#v}"
