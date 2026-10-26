# Homebrew tap

macOS users install with Homebrew, which avoids the "unidentified developer" dialog that a direct download
would raise (Homebrew-installed files are not quarantined):

```bash
brew tap yadavan88/sketchprobe https://github.com/yadavan88/homebrew-sketchprobe   # once
brew install sketchprobe
brew upgrade sketchprobe                                                    # later
```

(On Linux, Homebrew on x86-64 works the same way.)

## Setting the tap up (the maintainer, once)

1. Create a GitHub repository named `homebrew-sketchprobe` (Homebrew requires the `homebrew-` prefix).
2. Put the formula at `Formula/sketchprobe.rb` in it.

## Publishing a release (the maintainer, each time)

The release workflow attaches a ready formula, `sketchprobe.rb`, with the new version and checksums, to the
GitHub Release. Copy it to `Formula/sketchprobe.rb` in the tap repository and commit. To do it by hand:

```bash
curl -fsSLO https://github.com/yadavan88/sketchprobe/releases/download/vX.Y.Z/SHA256SUMS
packaging/homebrew/update-formula.sh X.Y.Z SHA256SUMS
```

Check it before publishing: `brew audit --strict --new --formula Formula/sketchprobe.rb` and
`brew install --build-from-source ./Formula/sketchprobe.rb && brew test sketchprobe`.
