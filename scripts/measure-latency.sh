#!/bin/bash
# SC-001: from a copied blob to a readable report in under 5 seconds on a typical laptop.
# Times the N largest corpus blobs of each family (default 2) fed as base64 on standard input, as a user would paste them,
# for each command given, and prints the median and the slowest of several runs.
#
# Usage: scripts/measure-latency.sh [-n BLOBS] [-r RUNS] <command> [<command> ...]
#   e.g. scripts/measure-latency.sh dist/native/sketchprobe "java -jar cli/target/scala-3.9.0/sketchprobe.jar"
set -euo pipefail
blobs=2; runs=7
while getopts "n:r:" o; do case "$o" in n) blobs="$OPTARG" ;; r) runs="$OPTARG" ;; *) exit 64 ;; esac; done
shift $((OPTIND - 1))
[ $# -ge 1 ] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 64; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 -I - "$ROOT" "$blobs" "$runs" "$@" <<'PY'
import base64, glob, json, os, shlex, subprocess, sys, time
root, blobs, runs, commands = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4:]
everything = sorted(glob.glob(os.path.join(root, "fixtures", "corpus", "*", "*", "*.bin")), key=os.path.getsize, reverse=True)
files = []
for family in ("hll", "theta", "kll", "frequency"):
    files += [f for f in everything if os.path.basename(os.path.dirname(f)) == family][:blobs]
print("slowest of %d runs / median, per blob (one paste to one report):" % runs)
worst_overall = 0.0
for cmd in commands:
    print("\n" + cmd)
    for f in files:
        manifest = json.load(open(f[:-4] + ".json"))
        t = manifest.get("params", {}).get("itemType")
        args = ["--type", t] if t in ("float", "double", "long", "string") else []
        data = base64.b64encode(open(f, "rb").read())
        times = []
        for _ in range(runs):
            s = time.perf_counter()
            p = subprocess.run(shlex.split(cmd) + args, input=data, capture_output=True)
            times.append(time.perf_counter() - s)
            if p.returncode != 0:
                sys.exit("%s failed on %s (status %d)" % (cmd, f, p.returncode))
        times.sort(); worst_overall = max(worst_overall, times[-1])
        rel = os.path.relpath(f, os.path.join(root, "fixtures", "corpus"))
        print("  %-62s %9s  %6.0f ms / %6.0f ms" % (rel, "%.1f KB" % (os.path.getsize(f) / 1024), times[-1] * 1000, times[len(times) // 2] * 1000))
print("\nslowest single run: %.0f ms (SC-001 limit 5000 ms): %s" % (worst_overall * 1000, "ok" if worst_overall < 5 else "TOO SLOW"))
sys.exit(0 if worst_overall < 5 else 1)
PY
