package sketchprobe.cli

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.Base64
import sketchprobe.CorpusFixtures
import sketchprobe.cli.CliHarness.{corpus, run, schema}

/** `--json` prints one JSON object that matches docs/report.schema.json, for a success and for
  * every failure type (spec FR-022, User Story 4, contracts/cli.md "Structured output").
  */
class JsonSchemaSpec extends munit.FunSuite {
  private def valid(r: CliHarness.Run, label: String): ujson.Value = {
    assert(
      r.out.endsWith("\n") && r.out.trim.linesIterator.size == 1,
      s"$label: not one JSON line:\n${r.out}"
    )
    val json = scala.util.Try(r.json).getOrElse(fail(s"$label: not JSON:\n${r.out}"))
    val problems = MiniJsonSchema.validate(schema, json)
    assertEquals(problems, Nil, s"$label violates the schema")
    assertEquals(
      json("status").num.toInt,
      r.status,
      s"$label: status field differs from the exit status"
    )
    json
  }

  private def typeArgs(f: sketchprobe.Fixture): Seq[String] =
    if (Set("kll", "frequency").contains(f.family)) Seq("--type", f.params("itemType").str) else Nil

  test("the schema itself is a draft 2020-12 object schema with the fields the contract names") {
    assertEquals(schema("$schema").str, "https://json-schema.org/draft/2020-12/schema")
    assertEquals(
      schema("required").arr.map(_.str).toSet,
      Set("schemaVersion", "status", "input", "confidence", "warnings")
    )
    assert(schema("properties")("status")("enum").arr.map(_.num.toInt).contains(64))
  }

  test(
    "every corpus blob gives a valid success object, with status 0 and the summary the family has"
  ) {
    CorpusFixtures.all.foreach { f =>
      val r = run(f.bytes, ("--json" +: typeArgs(f))*)
      val json = valid(r, f.toString)
      assertEquals(r.status, 0, s"$f: ${r.err}")
      assertEquals(json("schemaVersion").num.toInt, 1)
      assert(json("summary").obj.nonEmpty, s"$f: empty summary")
      assert(json("header")("rawPrefixHex").str.nonEmpty, f.toString)
      assertEquals(json.obj.get("diagnosis"), None, s"$f has a diagnosis")
    }
  }

  test("with --show-hashes and --show-items the lists are valid too, hashes as decimal strings") {
    val theta = CorpusFixtures.family("theta").find(_.name == "theta_compact_ordered_n5000").get
    val json = valid(run(theta.bytes, "--json", "--show-hashes"), "theta hashes")
    val hashes = json("contents")("retainedHashes").arr
    assert(
      hashes.nonEmpty && hashes.forall(_.strOpt.exists(_.forall(_.isDigit))),
      "hashes must be decimal strings"
    )
    assertEquals(json("contents")("total").num.toInt, hashes.size)
    val kll = CorpusFixtures.family("kll").find(_.name == "kll_double_k100_n10000_updatable").get
    val k = valid(run(kll.bytes, "--json", "--type", "double", "--show-items"), "kll items")
    assertEquals(k("contents")("retainedItems").arr.size, k("contents")("total").num.toInt)
    val freq = CorpusFixtures.family("frequency").find(_.name == "frequency_strings_platforms").get
    val all = valid(run(freq.bytes, "--json", "--show-items"), "frequency items")
    assertEquals(all("contents")("frequentItems").arr.size, 29)
    val top = valid(run(freq.bytes, "--json"), "frequency top")
    assertEquals(top("contents")("frequentItems").arr.size, 20)
    assertEquals(top("contents")("total").num.toInt, 29)
  }

  test("integers are written exactly, not as doubles") {
    val freq = CorpusFixtures.family("frequency").find(_.name == "frequency_strings_platforms").get
    val r = run(freq.bytes, "--json")
    assert(r.out.contains("\"streamLength\":16924897"), r.out)
    assert(!r.out.contains("E7"), r.out)
  }

  private val hllBytes = corpus("hll", "hll_HLL_8_k12_n1000_compact").bytes
  private def b64(b: Array[Byte]): Array[Byte] = Base64.getEncoder.encode(b)
  private val kllBytes =
    CorpusFixtures.family("kll").find(_.name == "kll_double_k100_n10000_updatable").get.bytes

  private val failures: Seq[(String, Array[Byte], Seq[String], String)] = Seq(
    ("empty input", Array.emptyByteArray, Nil, "empty-input"),
    ("ordinary text", "just words".getBytes(ISO_8859_1), Nil, "input-unreadable"),
    ("several blobs", b64(hllBytes) ++ "\n".getBytes ++ b64(hllBytes), Nil, "multiple-blobs"),
    ("ambiguous", "deadbeefdeadbeef".getBytes, Nil, "input-ambiguous"),
    ("over the cap", b64(hllBytes), Seq("--max-input-bytes", "20"), "input-too-large"),
    ("too short", Array[Byte](1, 2, 3), Nil, "too-short"),
    (
      "unknown family",
      Array.tabulate[Byte](40)(i => if (i == 2) 99 else (i + 3).toByte),
      Nil,
      "not-a-sketch"
    ),
    ("truncated", hllBytes.take(30), Nil, "incomplete"),
    (
      "unsupported family",
      Array[Byte](2, 1, 17, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      Nil,
      "unsupported-family"
    ),
    ("big-endian", hllBytes.updated(5, (hllBytes(5) | 1).toByte), Nil, "big-endian"),
    (
      "legacy Theta",
      corpus("theta", "theta_compact_ordered_n5000").bytes.updated(1, 1.toByte),
      Nil,
      "unsupported-layout"
    ),
    ("KLL without a type", kllBytes, Nil, "needs-item-type"),
    ("usage error", hllBytes, Seq("--frobnicate"), "usage")
  )

  failures.foreach { case (label, input, args, code) =>
    test(
      s"failure, $label: a valid object with diagnosis '$code' on standard output, the text on standard error"
    ) {
      val r = run(input, ("--json" +: args)*)
      val json = valid(r, label)
      assertEquals(json("diagnosis")("code").str, code)
      assert(json("diagnosis")("reason").str.nonEmpty)
      assert(r.status != 0)
      if (code != "usage")
        assert(
          r.err.startsWith("sketchprobe: "),
          s"the human failure text belongs on standard error:\n${r.err}"
        )
    }
  }

  test("a blob of fewer than 8 bytes has no header but its bytes are in the diagnosis") {
    val json = valid(run(Array[Byte](1, 2, 3), "--json"), "short")
    assertEquals(json.obj.get("header"), None)
    assertEquals(json("diagnosis")("rawPrefixHex").str, "010203")
  }

  test(
    "a low-confidence blob has confidence 'low', its failed evidence, and the header, but no summary"
  ) {
    val json = valid(run(hllBytes.updated(1, 9.toByte), "--json"), "low")
    assertEquals(json("confidence")("level").str, "low")
    assert(json("confidence")("evidence").arr.exists(e => !e("passed").bool))
    assertEquals(json("summary").obj.size, 0)
    assertEquals(json("header")("serVer").num.toInt, 9)
  }

  test("a medium-confidence blob carries the warning in the JSON too") {
    val json = valid(run(hllBytes ++ Array.fill[Byte](5)(0), "--json"), "medium")
    assertEquals(json("confidence")("level").str, "medium")
    assert(json("warnings").arr.exists(_("code").str == "confidence-medium"))
  }

  test("a seed mismatch is a warning with code seed-mismatch and status 0") {
    val t =
      CorpusFixtures.family("theta").find(_.name == "theta_compact_ordered_n5000_seed12345").get
    val json = valid(run(t.bytes, "--json"), "seed")
    assertEquals(json("status").num.toInt, 0)
    assert(json("warnings").arr.exists(_("code").str == "seed-mismatch"))
    val fixed = valid(run(t.bytes, "--json", "--seed", "12345"), "seed given")
    assertEquals(fixed("warnings").arr.size, 0)
  }

  test("the checker rejects a report with a required part removed, so these specs can fail") {
    val json = valid(run(hllBytes, "--json"), "hll")
    json.obj.remove("confidence")
    assert(MiniJsonSchema.validate(schema, json).exists(_.contains("confidence")))
  }
}
