# Homebrew formula for sketchprobe. It installs the prebuilt native executable from the GitHub Release:
# no Java, no compiling. Files installed by Homebrew are not quarantined by macOS, so there is no
# "unidentified developer" dialog (a direct download would need code signing, which is out of scope).
#
# The version in the URLs and the checksums below are filled in by packaging/homebrew/update-formula.sh from a
# release's SHA256SUMS; the release workflow attaches the result to the release as sketchprobe.rb.
class Sketchprobe < Formula
  desc "Inspect and decode Apache DataSketches binaries"
  homepage "https://github.com/yadavan88/sketchprobe"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/yadavan88/sketchprobe/releases/download/v0.1.0/sketchprobe-macos-arm64.tar.gz"
      sha256 "2ae324e95f7737ce937c6db8a1be0fd991b52a4c753b49576ad16e6e0d3f508f" # macos-arm64
    end

    on_intel do
      url "https://github.com/yadavan88/sketchprobe/releases/download/v0.1.0/sketchprobe-macos-x86_64.tar.gz"
      sha256 "0000000000000000000000000000000000000000000000000000000000000000" # macos-x86_64
    end
  end

  on_linux do
    depends_on arch: :x86_64

    on_intel do
      url "https://github.com/yadavan88/sketchprobe/releases/download/v0.1.0/sketchprobe-linux-x86_64.tar.gz"
      sha256 "2533aa6157af97be14615eea323ce68a1921fe63fe1a1f1a7d3063a8b110a8dd" # linux-x86_64
    end
  end

  def install
    bin.install "sketchprobe"
    doc.install "LICENSE", "NOTICE"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/sketchprobe --version")
    # An empty Frequency sketch (8 bytes), given as hex, is decoded and named.
    output = pipe_output("#{bin}/sketchprobe --input-format hex", "01010a0303050000")
    assert_match "Frequency", output
    # Empty input is refused with status 1.
    shell_output("#{bin}/sketchprobe < /dev/null", 1)
  end
end
