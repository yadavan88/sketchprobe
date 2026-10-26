package sketchprobe.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import sketchprobe.CorpusFixtures

/** What the user sees at each confidence level (spec FR-010). High: no warning. Medium: the decoded
  * summary under a prominent warning that says why. Low: the raw header fields and no decoded
  * numbers (exit status 2).
  */
class ConfidenceRenderSpec extends munit.FunSuite {
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

  private def fixture(family: String, name: String): Array[Byte] =
    CorpusFixtures.family(family).find(_.name == name).get.bytes
  private val hll = fixture("hll", "hll_HLL_8_k12_n50_compact")
  private val theta = fixture("theta", "theta_compact_ordered_n5000")

  private def lineOf(text: String, prefix: String): Int =
    text.linesIterator.indexWhere(_.startsWith(prefix))

  test("high confidence: the title says so and there is no warning") {
    val r = run(hll)
    assertEquals(r.status, 0)
    assert(r.out.linesIterator.next().endsWith("confidence: high"), r.out)
    assert(!r.out.contains("!!"), r.out)
    assertEquals(r.err, "")
  }

  test(
    "medium confidence (trailing bytes): the decoded summary is shown under a warning that says why"
  ) {
    Seq(hll, theta).foreach { b =>
      val r = run(b ++ Array.fill[Byte](7)(0))
      assertEquals(r.status, 0, r.err)
      assert(r.out.linesIterator.next().endsWith("confidence: medium"), r.out)
      val warning = r.out.linesIterator.indexWhere(_.startsWith("!!"))
      assert(warning > 0, s"no warning:\n${r.out}")
      assert(warning < lineOf(r.out, "Input"), s"the warning is not above the summary:\n${r.out}")
      assert(warning < lineOf(r.out, "Estimate"), r.out)
      val text = r.out.linesIterator.takeWhile(!_.startsWith("Input")).mkString(" ")
      assert(
        text.contains("medium") && text.contains("length"),
        s"the warning must say why:\n${r.out}"
      )
      assert(r.out.contains("Estimate"), "medium still shows the decoded summary")
    }
  }

  test(
    "low confidence (a known family, an impossible serial version): the raw header fields, no decoded numbers, status 2"
  ) {
    val b = hll.clone()
    b(1) = 9
    val r = run(b)
    assertEquals(r.status, 2, r.err)
    assertEquals(r.out, "", "no decoded summary on standard output")
    assert(r.err.contains("confidence: low"), r.err)
    assert(r.err.contains("serial version 9"), r.err)
    assert(r.err.contains("family 7"), r.err)
    assert(r.err.contains("header bytes"), r.err)
    assert(!r.err.contains("Estimate"), r.err)
  }

  test(
    "low confidence (a known family, a preamble size that does not fit it): the same raw header view"
  ) {
    val b = theta.clone()
    b(0) = 7
    val r = run(b)
    assertEquals(r.status, 2, r.err)
    assertEquals(r.out, "")
    assert(r.err.contains("confidence: low"), r.err)
    assert(r.err.contains("preamble size 7"), r.err)
  }

  test("an unknown family has no confidence to state: the header bytes only, status 2") {
    val b = hll.clone()
    b(2) = 99
    val r = run(b)
    assertEquals(r.status, 2)
    assert(!r.err.contains("confidence: low"), r.err)
    assert(r.err.contains("header bytes"), r.err)
  }
}
