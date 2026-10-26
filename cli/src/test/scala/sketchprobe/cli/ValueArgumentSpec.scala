package sketchprobe.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import sketchprobe.cli.CliHarness.{corpus, run}

/** The sketch can be given as text on the command line, in any format the input normaliser reads.
  */
class ValueArgumentSpec extends munit.FunSuite {
  private val blob = corpus("hll", "hll_HLL_8_k12_n50_compact")
  private val base64 = Base64.getEncoder.encodeToString(blob.bytes)
  private val hex = blob.bytes.map(b => f"${b & 0xff}%02x").mkString
  private val piped = run(base64.getBytes(UTF_8))

  private def noInput(args: String*) = run(Array.emptyByteArray, args*)

  test("a value argument gives the same report as the same text on standard input") {
    val r = noInput(base64)
    assertEquals(r.status, 0, r.err)
    assertEquals(r.out, piped.out)
    assertEquals(noInput("--json", base64).out, run(base64.getBytes(UTF_8), "--json").out)
  }

  test("hex and Mongo JSON values work, and options may come before or after the value") {
    assertEquals(noInput(hex).status, 0)
    val mongo = s"""{ "$$binary": { "base64": "$base64", "subType": "00" } }"""
    assertEquals(noInput(mongo).status, 0)
    assertEquals(noInput("--json", base64, "--header-only").status, 0)
  }

  test("after '--' the next argument is the value, even if it starts with '-'") {
    val r = noInput("--", "-abc not a sketch")
    assertEquals(r.status, 1)
    assert(r.err.contains("ordinary text"), r.err)
  }

  test("a value that is plain text suggests --file, and the same text on standard input does not") {
    val asValue = noInput("skech.bin")
    assertEquals(asValue.status, 1)
    assert(asValue.err.contains("--file"), asValue.err)
    val onStdin = run("skech.bin".getBytes(UTF_8))
    assertEquals(onStdin.status, 1)
    assert(!onStdin.err.contains("--file"), onStdin.err)
  }

  test("two inputs, or a value with --file, are a usage error (status 64)") {
    assertEquals(noInput(base64, hex).status, 64)
    assertEquals(noInput(base64, "--file", "x.bin").status, 64)
    assertEquals(noInput("--file", "x.bin", base64).status, 64)
    assertEquals(noInput("--file").status, 64)
  }

  test("a long value is abbreviated in the usage message, not echoed in full") {
    val r = noInput(base64, hex)
    assert(r.err.contains("..."), r.err)
    assert(!r.err.contains(base64), r.err)
  }
}
