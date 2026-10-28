package sketchprobe.cli

import java.util.{Base64, Locale}
import sketchprobe.{CorpusFixtures, InspectOptions, Inspector, Fixture => CorpusFixture}
import sketchprobe.report._

class HumanReportGoldenSpec extends munit.FunSuite {

  private def golden(name: String): String = {
    val s = scala.io.Source.fromResource(s"golden/$name.txt")(using scala.io.Codec.UTF8)
    try s.mkString.stripSuffix("\n")
    finally s.close()
  }

  private def fixture(writer: String, family: String, name: String): CorpusFixture =
    CorpusFixtures.all
      .find(f => f.writer == writer && f.family == family && f.name == name)
      .getOrElse(fail(s"missing fixture $writer/$family/$name"))

  private def report(f: CorpusFixture, options: InspectOptions = InspectOptions()): Report =
    Inspector.inspect(
      f.bytes,
      InputInfo(InputFormat.Base64, FormatSource.Detected, f.bytes.length),
      options
    )

  private def kllOptions(f: CorpusFixture): InspectOptions =
    InspectOptions(itemTypeName = Some(f.params("itemType").str))

  test("HLL report matches the golden output") {
    val f = fixture("java-6.2.0", "hll", "hll_HLL_8_k12_n1000_compact")
    assertNoDiff(HumanRenderer.render(report(f)), golden("hll_HLL_8_k12_n1000_compact"))
  }

  test("Theta report matches the golden output") {
    val f = fixture("java-6.2.0", "theta", "theta_compact_ordered_n5000")
    assertNoDiff(HumanRenderer.render(report(f)), golden("theta_compact_ordered_n5000"))
  }

  test("KLL report matches the golden output") {
    val f = fixture("java-6.2.0", "kll", "kll_float_k200_n10000")
    assertNoDiff(HumanRenderer.render(report(f, kllOptions(f))), golden("kll_float_k200_n10000"))
  }

  test("Frequency report (exact, string items, top 20 of 29) matches the golden output") {
    val f = fixture("field", "frequency", "frequency_strings_platforms")
    assertNoDiff(HumanRenderer.render(report(f)), golden("frequency_strings_platforms"))
  }

  test("Frequency report (purged, long items, estimate and bounds) matches the golden output") {
    val f = fixture("java-9.0.0", "frequency", "freq_long_m64_purged")
    assertNoDiff(HumanRenderer.render(report(f)), golden("freq_long_m64_purged"))
  }

  test("every report states what can and cannot be recovered (FR-016)") {
    val expected = Map(
      "hll" -> RecoverabilityKind.EstimateOnly,
      "theta" -> RecoverabilityKind.HashesOnly,
      "kll" -> RecoverabilityKind.RealValues,
      "frequency" -> RecoverabilityKind.RealValues,
      "quantiles" -> RecoverabilityKind.RealValues
    )
    CorpusFixtures.all
      .foreach { f =>
        val r = report(f, if (f.family == "kll") kllOptions(f) else InspectOptions())
        assertEquals(r.status, 0, s"$f")
        assertEquals(
          r.recoverability.map(_.kind),
          Some(expected(f.family)),
          s"recoverability of $f"
        )
        assert(HumanRenderer.render(r).contains("Recoverability"), s"$f has no recoverability line")
      }
  }

  test("no line is wider than 80 columns, for every blob in the corpus (FR-022)") {
    CorpusFixtures.all
      .foreach { f =>
        val options = (if (f.family == "kll") kllOptions(f) else InspectOptions())
          .copy(showHashes = true, showItems = true)
        val text = HumanRenderer.render(report(f, options))
        text.linesIterator.foreach(l => assert(l.length <= 80, s"$f: ${l.length} columns: $l"))
      }
  }

  test("numbers do not depend on the default locale (a German locale must not print 996,18)") {
    val saved = Locale.getDefault
    try {
      Locale.setDefault(Locale.GERMANY)
      val f = fixture("java-6.2.0", "hll", "hll_HLL_8_k12_n1000_compact")
      val text = HumanRenderer.render(report(f))
      assert(text.contains("996.18"), text)
      assertNoDiff(text, golden("hll_HLL_8_k12_n1000_compact"))
    } finally Locale.setDefault(saved)
  }

  test("listing retained hashes shows the first 100 and says how many more there are") {
    val f = fixture("java-6.2.0", "theta", "theta_compact_ordered_n5000")
    val text = HumanRenderer.render(report(f, InspectOptions(showHashes = true)))
    assert(text.contains("Retained hashes (1,361)"), text)
    assert(text.contains("... and 1,261 more"), text)
    val listed =
      text.linesIterator.filter(l => l.startsWith("  ") && l.trim.forall(_.isDigit)).toList
    assertEquals(listed.size, 100, "exactly 100 hashes are listed")
    assert(
      listed.forall(l => java.lang.Long.parseUnsignedLong(l.trim) > 0),
      "every listed hash is a number"
    )
  }

  test("a blob without --type shows what does not depend on the type, and no listing hint") {
    val f = fixture("java-6.2.0", "kll", "kll_float_k200_n10000")
    val r = report(f)
    assertEquals(r.status, 3)
    val text = HumanRenderer.render(r)
    assert(text.contains("Items seen (n)") && text.contains("10,000"), text)
    assert(!text.contains("--show-items"), text)
  }

  test("base64 text decodes to the same blob the report was built from") {
    val f = fixture("java-6.2.0", "hll", "hll_HLL_8_k12_n1000_compact")
    assertEquals(
      Base64.getDecoder.decode(Base64.getEncoder.encodeToString(f.bytes)).toSeq,
      f.bytes.toSeq
    )
  }
}
