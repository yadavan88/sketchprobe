#!/bin/sh
# Replaces the OWNER/sketchprobe placeholder (and OWNER/homebrew-sketchprobe, the tap) with the real GitHub
# repository, in install.sh, the Homebrew formula, its README and the main README.   Usage: packaging/set-repo.sh yourname/sketchprobe
set -eu
repo="${1:?usage: set-repo.sh OWNER/REPO}"
case "$repo" in */*) ;; *) echo "expected OWNER/REPO, got '$repo'" >&2; exit 1 ;; esac
owner="${repo%%/*}"
root="$(cd "$(dirname "$0")/.." && pwd)"
for f in packaging/install.sh packaging/homebrew/sketchprobe.rb packaging/homebrew/README.md README.md; do
  [ -f "$root/$f" ] || continue
  sed -e "s#OWNER/sketchprobe#$repo#g" -e "s#OWNER/homebrew-sketchprobe#$owner/homebrew-sketchprobe#g" "$root/$f" > "$root/$f.tmp" && mv "$root/$f.tmp" "$root/$f"
  case "$f" in *.sh) chmod +x "$root/$f" ;; esac
done
echo "repository set to $repo"
