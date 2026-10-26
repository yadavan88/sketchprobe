package sketchprobe.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.Base64
import sketchprobe.CorpusFixtures

/** Each kind of failure through the command line: the exit status, a plain reason, a next step
  * where one exists, the leading header bytes for statuses 2 and 3, and never a stack trace (spec
  * FR-019 to FR-021, contracts/cli.md).
  */
class FailureCliSpec extends munit.FunSuite {
  private final case class Run(status: Int, out: String, err: String)

  private def run(bytes: Array[Byte], args: String*): Run = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val status = Cli.run(
      args.toArray,
      new ByteArrayInputStream(bytes),
      new PrintStream(out, true, UTF_8),
      new PrintStream(err, true, UTF_8)
    )
    Run(status, out.toString(UTF_8), err.toString(UTF_8))
  }
  private def text(s: String, args: String*): Run = run(s.getBytes(ISO_8859_1), args*)
  private def b64(b: Array[Byte]): String = Base64.getEncoder.encodeToString(b)

  private def fixture(family: String, name: String): Array[Byte] =
    CorpusFixtures.family(family).find(_.name == name).get.bytes
  private val hll = fixture("hll", "hll_HLL_8_k12_n50_compact")
  private val theta = fixture("theta", "theta_compact_ordered_n5000")

  /** What every failure must look like, whatever its kind. */
  private def assertFailure(r: Run, status: Int, mentions: String*): Unit = {
    assertEquals(r.status, status, r.err)
    assert(r.err.startsWith("sketchprobe: "), s"does not start with the program name: ${r.err}")
    assert(
      r.err.linesIterator.forall(_.length <= 80),
      s"a line is wider than 80 columns:\n${r.err}"
    )
    assert(
      !r.err.contains("Exception") && !r.err.contains("\tat ") && !r.err.contains("org.apache"),
      r.err
    )
    mentions.foreach(m =>
      assert(r.err.toLowerCase.contains(m.toLowerCase), s"'$m' missing from:\n${r.err}")
    )
  }
  private def assertNextStep(r: Run): Unit =
    assert(r.err.contains("  next step:"), s"no next step:\n${r.err}")
  private def assertHeaderBytes(r: Run): Unit =
    assert(r.err.contains("header bytes (first"), s"no header bytes:\n${r.err}")

  // ---- status 1: unreadable input

  test("empty input and white space only: status 1, says it is empty") {
    Seq("", " \n\t \n").foreach { s =>
      val r = text(s)
      assertFailure(r, 1, "empty")
      assertNextStep(r)
      assertEquals(r.out, "")
    }
  }

  test(
    "ordinary text: status 1, says it is not a sketch value, no header bytes (there are none to show)"
  ) {
    val r = text("this is just a sentence")
    assertFailure(r, 1, "text")
    assertNextStep(r)
  }

  test("several blobs: status 1, names the count") {
    val r = text(b64(hll) + "\n" + b64(theta) + "\n")
    assertFailure(r, 1, "several")
    assertNextStep(r)
  }

  test("an ambiguous encoding: status 1, names --input-format") {
    val r = text("deadbeefdeadbeef")
    assertFailure(r, 1, "--input-format")
  }

  test("input over the size cap: status 1, names --max-input-bytes") {
    val r = text(b64(theta), "--max-input-bytes", "100")
    assertFailure(r, 1, "--max-input-bytes")
  }

  test("a file that cannot be read: status 1, names the file and says how to give a value") {
    val r = run(Array.emptyByteArray, "--file", "/no/such/file.bin")
    assertFailure(r, 1, "/no/such/file.bin")
    assert(r.err.contains("pbpaste | sketchprobe"), r.err)
  }

  // ---- status 2: not a recognizable sketch

  test("fewer than 8 bytes: status 2, says how many, shows the bytes") {
    val r = run(Array[Byte](1, 2, 3, 4, 5))
    assertFailure(r, 2, "5 bytes")
    assertHeaderBytes(r)
  }

  test(
    "64 bytes that are not a sketch (an unknown family ID): status 2, names the family ID, shows the header"
  ) {
    val b = Array.tabulate[Byte](64)(i => (i + 3).toByte)
    b(2) = 99
    val r = run(b)
    assertFailure(r, 2, "99")
    assertNextStep(r)
    assertHeaderBytes(r)
  }

  test("a known family with an invalid field (HLL, lgK 30): status 2, shows the header") {
    val b = hll.clone()
    b(3) = 30
    val r = run(b)
    assertFailure(r, 2, "lgK")
    assertHeaderBytes(r)
  }

  // ---- status 3: recognized but not decoded

  test("a truncated sketch: status 3, says it is cut off, names the family, decodes nothing") {
    val r = run(hll.take(30))
    assertFailure(r, 3, "cut off", "HLL")
    assertNextStep(r)
    assertHeaderBytes(r)
    assertEquals(r.out, "")
  }

  test("a header that claims more data than the blob holds: status 3, no crash") {
    val b = theta.clone()
    b(8) = 0xff.toByte; b(9) = 0xff.toByte; b(10) = 0xff.toByte; b(11) = 0x7f.toByte
    val r = run(b)
    assertFailure(r, 3, "claims more data")
    assertNextStep(r)
  }

  test("a recognized family that is not decoded (REQ, family 17): status 3, names it") {
    val r = run(Array[Byte](2, 1, 17, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    assertFailure(r, 3, "REQ")
    assertHeaderBytes(r)
  }

  test("AnotB (family 6) is recognized and named, status 3") {
    val r = run(Array[Byte](3, 3, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    assertFailure(r, 3, "AnotB")
  }

  test("the big-endian flag: status 3, says only little-endian is supported") {
    val b = hll.clone()
    b(5) = (b(5) | 1).toByte
    val r = run(b)
    assertFailure(r, 3, "big-endian")
    assertHeaderBytes(r)
  }

  test("a legacy Theta serial version (1 or 2): status 3, says the version is not supported") {
    val b = theta.clone()
    b(1) = 1
    val r = run(b)
    assertFailure(r, 3, "version")
    assertNextStep(r)
  }

  test(
    "a KLL sketch without --type: status 3, asks for the type, and still shows what does not depend on it"
  ) {
    val kll =
      CorpusFixtures.family("kll").find(_.name == "kll_double_k100_n10000_updatable").get.bytes
    val r = run(kll)
    assertFailure(r, 3, "--type")
    assertNextStep(r)
    assert(r.out.contains("Items seen (n)"), r.out)
  }

  test("--type string on a KLL sketch: status 3, recognized but not supported yet") {
    val kll =
      CorpusFixtures.family("kll").find(_.name == "kll_double_k100_n10000_updatable").get.bytes
    assertFailure(run(kll, "--type", "string"), 3, "not supported")
  }

  // ---- status 64: usage

  test("usage errors: status 64, name the option, show the usage") {
    Seq(
      Seq("--frobnicate") -> "--frobnicate",
      Seq("--type") -> "--type",
      Seq("--type", "bogus") -> "bogus",
      Seq("--max-input-bytes", "abc") -> "abc",
      Seq("--input-format", "zzz") -> "zzz",
      Seq("--ranks", "2") -> "--ranks"
    ).foreach { case (args, mention) =>
      val r = run(hll, args*)
      assertFailure(r, 64, mention)
    }
  }

  test("no failure prints anything on standard output unless it decoded part of the sketch") {
    Seq(text(""), text("nope nope"), run(Array[Byte](1, 2, 3)), run(hll.take(30))).foreach { r =>
      assertEquals(r.out, "")
    }
  }
}
