#!/bin/sh
# Installs sketchprobe from a GitHub Release, for Linux (x86-64) and macOS (Apple silicon).
#
#   curl -fsSL https://raw.githubusercontent.com/yadavan88/sketchprobe/main/packaging/install.sh | sh
#   sh install.sh --version 0.1.0 --dir ~/bin
#
# It downloads the release asset for this machine and the list of checksums, refuses to install if the
# checksum does not match, and puts one executable in the install directory. Nothing else on the machine
# is touched, and no Java is needed.
#
# Options (or environment variables):
#   --version V     install release V (default: the latest)         SKETCHPROBE_VERSION
#   --dir DIR       install directory (default: ~/.local/bin, or /usr/local/bin when run as root)
#                                                                    SKETCHPROBE_INSTALL_DIR
#   --jlink         install the bundled-runtime build instead of the native executable (about 80 MB,
#                   starts in about 100 ms instead of 10); it goes in DIR/../share/sketchprobe
#   --help
# Testing and unusual setups:
#   SKETCHPROBE_BASE_URL   where the assets are (default: the repository's releases; file:// works)
#   SKETCHPROBE_PLATFORM   macos-arm64 or linux-x86_64, instead of detecting it
set -eu

REPO="yadavan88/sketchprobe"   # replaced by packaging/set-repo.sh

version="${SKETCHPROBE_VERSION:-latest}"
dir="${SKETCHPROBE_INSTALL_DIR:-}"
jlink=0

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --version) [ $# -ge 2 ] || die "--version needs a value"; version="$2"; shift 2 ;;
    --dir)     [ $# -ge 2 ] || die "--dir needs a value"; dir="$2"; shift 2 ;;
    --jlink)   jlink=1; shift ;;
    --help|-h) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option '$1' (try --help)" ;;
  esac
done

# ---- which asset
platform="${SKETCHPROBE_PLATFORM:-}"
if [ -z "$platform" ]; then
  os="$(uname -s)"; arch="$(uname -m)"
  case "$os/$arch" in
    Darwin/arm64)             platform=macos-arm64 ;;
    Linux/x86_64|Linux/amd64) platform=linux-x86_64 ;;
    Darwin/x86_64)
      die "this is an Intel Mac, or a shell running as x86_64. Releases are built for Apple silicon and Linux x86-64. On Apple silicon run: arch -arm64 sh install.sh" ;;
    *) die "no release for $os on $arch; releases are built for Apple silicon (macOS) and Linux x86-64" ;;
  esac
fi
case "$platform" in macos-arm64|linux-x86_64) ;; *) die "unknown platform '$platform'" ;; esac

asset="sketchprobe-$platform"
[ "$jlink" = 1 ] && asset="$asset-jlink"
asset="$asset.tar.gz"

if [ -n "${SKETCHPROBE_BASE_URL:-}" ]; then base="$SKETCHPROBE_BASE_URL"
elif [ "$version" = latest ]; then base="https://github.com/$REPO/releases/latest/download"
else base="https://github.com/$REPO/releases/download/v${version#v}"; fi

# ---- where to
if [ -z "$dir" ]; then
  if [ "$(id -u)" = 0 ]; then dir=/usr/local/bin; else dir="$HOME/.local/bin"; fi
fi

# ---- download and check
if command -v curl >/dev/null 2>&1; then
  fetch() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  fetch() { wget -q -O "$2" "$1"; }
else die "curl or wget is needed to download the release"; fi

if command -v sha256sum >/dev/null 2>&1; then sha() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else die "sha256sum or shasum is needed to check the download"; fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT INT TERM

say "downloading $asset ($version) ..."
fetch "$base/$asset" "$tmp/$asset" || die "could not download $base/$asset"
fetch "$base/SHA256SUMS" "$tmp/SHA256SUMS" || die "could not download $base/SHA256SUMS"

want="$(grep "  $asset\$" "$tmp/SHA256SUMS" | cut -d' ' -f1 || true)"
[ -n "$want" ] || die "SHA256SUMS has no entry for $asset"
got="$(sha "$tmp/$asset")"
[ "$got" = "$want" ] || die "checksum mismatch for $asset (expected $want, got $got); nothing was installed"
say "checksum ok"

# ---- install
tar -xzf "$tmp/$asset" -C "$tmp" || die "could not unpack $asset"
mkdir -p "$dir" || die "cannot create $dir (use --dir)"
if [ "$jlink" = 1 ]; then
  [ -d "$tmp/jlink" ] || die "the archive has no jlink directory"
  share="$(dirname "$dir")/share/sketchprobe"
  rm -rf "$share"; mkdir -p "$(dirname "$share")"
  mv "$tmp/jlink" "$share"
  ln -sf "$share/bin/sketchprobe" "$dir/sketchprobe"
  target="$share"
else
  [ -f "$tmp/sketchprobe" ] || die "the archive has no sketchprobe executable"
  cp "$tmp/sketchprobe" "$dir/sketchprobe.new" && chmod 755 "$dir/sketchprobe.new" && mv -f "$dir/sketchprobe.new" "$dir/sketchprobe"
  target="$dir/sketchprobe"
fi

say "installed: $target"
case ":$PATH:" in *":$dir:"*) ;; *) say "note: $dir is not on your PATH; add it, for example: export PATH=\"$dir:\$PATH\"" ;; esac
"$dir/sketchprobe" --version
