#!/usr/bin/env node
// Checks the browser build (packaging/build-web.sh) without a browser: loads it in Node and compares its
// reports with the golden reports the tests hold, the same ones packaging/smoke-test.sh uses for the
// native executable, and checks the one known limit (compressed Theta) and a failure.
//
// Usage:  node --experimental-wasm-exnref packaging/smoke-test-web.js [dist/web]
// The flag is needed on Node versions that do not enable WebAssembly exception handling (exnref) yet.
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.resolve(__dirname, "..");
const dir = path.resolve(process.argv[2] || path.join(root, "dist", "web"));
const golden = (name) => fs.readFileSync(path.join(root, "cli/src/test/resources/golden", name + ".txt"), "utf8");
const b64 = (file) => fs.readFileSync(path.join(root, "fixtures/corpus", file)).toString("base64");

let failed = 0;
const ok = (label) => console.log("  ok    " + label);
const bad = (label, detail) => { failed++; console.log("  FAIL  " + label + (detail ? "\n" + detail : "")); };

async function main() {
  console.log("smoke test: " + dir);
  // The module starts when the script loads (as in a page); inspect appears once it has.
  require(path.join(dir, "sketchprobe.js"));
  const deadline = Date.now() + 60000;
  while (!(globalThis.sketchprobe && globalThis.sketchprobe.inspect)) {
    if (Date.now() > deadline) { bad("the decoder did not start within 60 s"); return; }
    await new Promise((r) => setTimeout(r, 50));
  }
  const inspect = (value, options) => JSON.parse(globalThis.sketchprobe.inspect(value, (options || []).join("\n")));

  // [label, fixture, golden name, options]
  const reports = [
    ["HLL", "java-6.2.0/hll/hll_HLL_8_k12_n1000_compact.bin", "hll_HLL_8_k12_n1000_compact", []],
    ["Theta", "java-6.2.0/theta/theta_compact_ordered_n5000.bin", "theta_compact_ordered_n5000", []],
    ["KLL (--type float)", "java-6.2.0/kll/kll_float_k200_n10000.bin", "kll_float_k200_n10000", ["--type", "float"]],
    ["Frequency (string items)", "field/frequency/frequency_strings_platforms.bin", "frequency_strings_platforms", []],
  ];
  for (const [label, fixture, name, options] of reports) {
    const r = inspect(b64(fixture), options);
    const want = golden(name).replace(/\n$/, "");
    if (r.status === 0 && r.out.replace(/\n$/, "") === want) ok(label);
    else bad(label + " (status " + r.status + ", output differs from golden/" + name + ".txt)", r.err);
  }

  // Every example the page offers must decode (the page builds them with packaging/web-samples.sh).
  const samples = vm.runInNewContext(require("child_process").execFileSync(path.join(root, "packaging", "web-samples.sh"), { encoding: "utf8" }));
  for (const s of samples) {
    const options = [];
    if (s.type) options.push("--type", s.type);
    if (s.ranks) options.push("--ranks", s.ranks);
    const r = inspect(s.value, options);
    if (r.status === 0 && r.out.length > 0) ok("page example: " + s.label);
    else bad("page example: " + s.label, r.err);
  }

  const compressed = inspect(b64("java-9.0.0/theta/theta_compressed_ordered_n1000.bin"), []);
  if (compressed.status === 3 && compressed.out === "" && compressed.err.includes("compressed Theta sketch")) ok("compressed Theta is refused with a message");
  else bad("compressed Theta", JSON.stringify(compressed));

  const garbage = inspect("AAAAAAAAAAAAAAAAAAAA", []);
  if (garbage.status === 1 && garbage.err.length > 0) ok("garbage input is a clear failure");
  else bad("garbage input", JSON.stringify(garbage));

  const file = inspect("x", ["--file", "/etc/passwd"]);
  if (file.status === 64) ok("--file is refused");
  else bad("--file", JSON.stringify(file));
}

main().then(() => {
  console.log(failed === 0 ? "all checks passed" : failed + " check(s) failed");
  process.exit(failed === 0 ? 0 : 1);
});
