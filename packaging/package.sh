#!/bin/sh
# Turns the output of build-native.sh and build-jlink.sh into the release assets for one platform.
#
# Usage: packaging/package.sh <platform> [out-dir]      platform: macos-arm64, macos-x86_64 or linux-x86_64
# Writes <out-dir>/sketchprobe-<platform>.tar.gz        sketchprobe, LICENSE, NOTICE (the native executable)
#        <out-dir>/sketchprobe-<platform>-jlink.tar.gz  jlink/ (the bundled-runtime build)
# The names carry no version, so that .../releases/latest/download/<name> always finds the newest.
set -eu
platform="${1:?usage: package.sh <platform> [out-dir]}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${2:-$root/dist/release}"
mkdir -p "$out"
[ -f "$root/dist/native/sketchprobe" ] || { echo "run packaging/build-native.sh first" >&2; exit 1; }
[ -d "$root/dist/jlink" ] || { echo "run packaging/build-jlink.sh first" >&2; exit 1; }
# Absolute path for the archives; tar -C changes directory.
out="$(cd "$out" && pwd)"
tar -C "$root/dist/native" -czf "$out/sketchprobe-$platform.tar.gz" sketchprobe LICENSE NOTICE
tar -C "$root/dist" -czf "$out/sketchprobe-$platform-jlink.tar.gz" jlink
ls -l "$out" | sed -n '2,$p'
