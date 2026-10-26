package sketchprobe.cli

import java.nio.file.Files
import sketchprobe.cli.CliHarness.{corpus, run}

/** Each exit status occurs for the input it is defined for, with and without `--json`, and a file
  * argument works (spec FR-021, contracts/cli.md "Exit statuses").
  */
class ExitStatusSpec extends munit.FunSuite {
  private val hll = corpus("hll", "hll_HLL_8_k12_n1000_compact").bytes

  private val cases: Seq[(Int, String, Array[Byte], Seq[String])] = Seq(
    (0, "a valid sketch", hll, Nil),
    (0, "a sketch with trailing bytes (medium confidence)", hll ++ Array.fill[Byte](3)(0), Nil),
    (
      0,
      "a Theta seed mismatch",
      corpus("theta", "theta_compact_ordered_n5000_seed12345").bytes,
      Nil
    ),
    (1, "empty input", Array.emptyByteArray, Nil),
    (1, "text that is not a sketch", "hello there".getBytes, Nil),
    (2, "fewer than 8 bytes", Array[Byte](1, 2, 3), Nil),
    (2, "an unknown family ID", hll.updated(2, 99.toByte), Nil),
    (2, "an invalid field (lgK 30)", hll.updated(3, 30.toByte), Nil),
    (3, "a truncated sketch", hll.take(40), Nil),
    (
      3,
      "a recognized family that is not decoded",
      Array[Byte](2, 1, 17, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      Nil
    ),
    (
      3,
      "a KLL sketch without its item type",
      corpus("kll", "kll_double_k100_n10000_updatable").bytes,
      Nil
    ),
    (64, "an unknown option", hll, Seq("--frobnicate")),
    (64, "an option without its value", hll, Seq("--type")),
    (64, "an option with a bad value", hll, Seq("--max-input-bytes", "lots"))
  )

  cases.foreach { case (status, label, input, args) =>
    test(s"status $status for $label") {
      assertEquals(run(input, args*).status, status)
      val json = run(input, ("--json" +: args)*)
      assertEquals(json.status, status, "with --json")
      assertEquals(json.json("status").num.toInt, status, "the status field")
    }
  }

  test("all five statuses occur, and no other") {
    val seen = cases.map { case (_, _, input, args) => run(input, args*).status }.toSet
    assertEquals(seen, Set(0, 1, 2, 3, 64))
  }

  test("--file reads a file like standard input, and '-' (or --file -) means standard input") {
    val f = Files.createTempFile("sketchprobe", ".bin")
    try {
      Files.write(f, hll)
      val viaFile = run(Array.emptyByteArray, "--file", f.toString)
      val viaStdin = run(hll)
      assertEquals(viaFile.status, 0, viaFile.err)
      assertEquals(viaFile.out, viaStdin.out)
      assertEquals(run(hll, "-").out, viaStdin.out)
      assertEquals(run(hll, "--file", "-").out, viaStdin.out)
    } finally Files.deleteIfExists(f)
  }

  test("a file that does not exist is status 1, with --json too") {
    assertEquals(run(Array.emptyByteArray, "--file", "/no/such/file").status, 1)
    assertEquals(run(Array.emptyByteArray, "--json", "--file", "/no/such/file").status, 1)
  }

  test(
    "a success writes nothing to standard error, and --json a failure writes the text there, not the object"
  ) {
    val ok = run(hll, "--json")
    assertEquals(ok.err, "")
    val bad = run(Array[Byte](1, 2, 3), "--json")
    assert(bad.err.nonEmpty && !bad.err.contains("{"), bad.err)
  }

  test(
    "--help prints the tagline and every option on at most 80 columns, --version the version, both status 0"
  ) {
    val help = run(Array.emptyByteArray, "--help")
    assertEquals(help.status, 0)
    assert(help.out.contains("inspect and decode Apache DataSketches binaries"), help.out)
    Seq(
      "--json",
      "--file",
      "--input-format",
      "--type",
      "--seed",
      "--ranks",
      "--show-hashes",
      "--show-items",
      "--header-only",
      "--max-input-bytes",
      "--version",
      "--help"
    )
      .foreach(o => assert(help.out.contains(o), s"help does not mention $o"))
    assert(
      help.out.linesIterator.forall(_.length <= 80),
      help.out.linesIterator.filter(_.length > 80).mkString("\n")
    )
    val version = run(Array.emptyByteArray, "--version")
    assertEquals(
      (version.status, version.out.trim),
      (0, s"sketchprobe ${sketchprobe.Version.current}")
    )
  }

  test(
    "--header-only identifies without decoding: the header and confidence, no summary, status 0"
  ) {
    val r = run(hll, "--header-only")
    assertEquals(r.status, 0, r.err)
    assert(r.out.contains("Header ") && r.out.contains("family 7"), r.out)
    assert(!r.out.contains("Estimate"), r.out)
    val j = run(hll, "--header-only", "--json").json
    assertEquals(j("summary").obj.size, 0)
    assertEquals(j("family")("name").str, "HLL")
    assert(j("recoverability")("statement").str.nonEmpty)
  }

  test("an option that does not apply to the family is ignored with one warning") {
    val r = run(hll, "--show-hashes", "--json")
    assertEquals(r.status, 0)
    assert(r.json("warnings").arr.exists(_("code").str == "option-ignored"), r.out)
    assert(!run(hll).out.contains("ignored"), "no note when nothing was ignored")
  }

  test("a second input is a usage error, not a silent choice") {
    assertEquals(run(hll, "a.bin", "b.bin").status, 64)
  }
}
