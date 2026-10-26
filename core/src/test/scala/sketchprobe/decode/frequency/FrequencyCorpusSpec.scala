package sketchprobe.decode.frequency

import sketchprobe.{CorpusFixtures, Fixture => CorpusFixture}
import sketchprobe.adapter.FrequencyAdapter
import sketchprobe.preflight.FrequencyItemType
import sketchprobe.report.Value

/** Every Frequency blob in the corpus reads to the counts and bounds the writing library reported
  * (spec FR-026, FR-018).
  */
class FrequencyCorpusSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("frequency")

  private def trueType(f: CorpusFixture): FrequencyItemType =
    if (f.params("itemType").str == "long") FrequencyItemType.Int64 else FrequencyItemType.Text

  private def decoded(f: CorpusFixture, t: Option[FrequencyItemType]): FrequencyData =
    FrequencyAdapter.read(f.bytes, t) match {
      case Right(d)   => d
      case Left(diag) => fail(s"$f did not decode: ${diag.code.label}: ${diag.reason}")
    }

  private def itemKey(v: Value): String = v match {
    case Value.Int64(n) => n.toString
    case Value.Text(s)  => s
    case other          => fail(s"unexpected item $other")
  }

  private def manifestRows(f: CorpusFixture): Map[String, (Long, Long, Long)] =
    f.expected("rows")
      .arr
      .map { r =>
        val key = if (r(0).strOpt.isDefined) r(0).str else r(0).num.toLong.toString
        key -> ((r(1).num.toLong, r(2).num.toLong, r(3).num.toLong))
      }
      .toMap

  test("the corpus has Frequency blobs to test") {
    assertEquals(fixtures.size, 25)
  }

  test("stream length, active count, capacities, maximum error and emptiness match the manifest") {
    fixtures.foreach { f =>
      val d = decoded(f, Some(trueType(f)))
      val e = f.expected
      assertEquals(d.empty, e("empty").bool, s"empty of $f")
      assertEquals(d.numActive, e("numActive").num.toInt, s"numActive of $f")
      assertEquals(d.maxMapCapacity, e("maximumMapCapacity").num.toInt, s"max capacity of $f")
      if (!d.empty) {
        assertEquals(d.streamLength, e("streamLength").num.toLong, s"stream length of $f")
        assertEquals(d.maximumError, e("maximumError").num.toLong, s"maximum error of $f")
        assertEquals(d.curMapCapacity, e("currentMapCapacity").num.toInt, s"capacity of $f")
      }
    }
  }

  test("every row, with its estimate and both bounds, equals the manifest's") {
    fixtures.foreach { f =>
      val rows = decoded(f, Some(trueType(f))).rows()
      val got = rows.map(r => itemKey(r.item) -> ((r.estimate, r.lowerBound, r.upperBound))).toMap
      assertEquals(rows.size, got.size, s"duplicate items in $f")
      assertEquals(got, manifestRows(f), s"rows of $f")
    }
  }

  test("rows come largest estimate first, and the lower bound never exceeds the estimate") {
    fixtures.foreach { f =>
      val rows = decoded(f, Some(trueType(f))).rows()
      assertEquals(
        rows.map(_.estimate),
        rows.map(_.estimate).sorted(using Ordering[Long].reverse),
        s"order of $f"
      )
      assert(
        rows.forall(r => r.lowerBound <= r.estimate && r.estimate <= r.upperBound),
        s"bounds of $f"
      )
    }
  }

  test(
    "an exact sketch (maximum error 0) has equal estimate and bounds and counts adding up to the stream length"
  ) {
    fixtures.filter(f => !f.expected("empty").bool && f.expected("maximumError").num == 0).foreach {
      f =>
        val d = decoded(f, Some(trueType(f)))
        val rows = d.rows()
        assert(rows.forall(r => r.estimate == r.lowerBound && r.estimate == r.upperBound), s"$f")
        assertEquals(rows.map(_.estimate).sum, d.streamLength, s"counts of $f")
    }
  }

  test("a purged sketch has a positive maximum error, and its bounds are that far apart") {
    val purged = fixtures.filter(_.expected("maximumError").num > 0)
    assert(purged.nonEmpty)
    purged.foreach { f =>
      val d = decoded(f, Some(trueType(f)))
      assert(d.maximumError > 0, s"$f")
      assert(d.rows().forall(r => r.upperBound - r.lowerBound == d.maximumError), s"bounds of $f")
    }
  }

  test("without an item type, a non-empty blob reads as its own type, and the type is reported") {
    fixtures.filterNot(_.expected("empty").bool).foreach { f =>
      val d = decoded(f, None)
      assertEquals(d.itemType, Some(trueType(f)), s"type of $f")
      assertEquals(
        d.rows().map(r => itemKey(r.item) -> r.estimate).toMap,
        decoded(f, Some(trueType(f))).rows().map(r => itemKey(r.item) -> r.estimate).toMap,
        s"rows of $f"
      )
    }
  }

  test("an empty sketch without an item type reads as empty, with no type and no rows") {
    fixtures.filter(_.expected("empty").bool).foreach { f =>
      val d = decoded(f, None)
      assertEquals((d.itemType, d.rows(), d.numActive), (None, Vector.empty, 0), s"$f")
    }
  }

  test("the wrong item type is refused as needs-item-type, naming the right one") {
    fixtures.filterNot(_.expected("empty").bool).foreach { f =>
      val wrong =
        if (trueType(f) == FrequencyItemType.Int64) FrequencyItemType.Text
        else FrequencyItemType.Int64
      FrequencyAdapter.read(f.bytes, Some(wrong)) match {
        case Left(d) =>
          assertEquals(d.code.label, "needs-item-type", s"$f")
          assert(d.nextStep.exists(_.contains(trueType(f).label)), s"$f: ${d.nextStep}")
        case Right(_) => fail(s"$f was read as $wrong")
      }
    }
  }

  test("strings keep their characters: non-ASCII, empty and 300-character items") {
    val f = fixtures.find(_.name == "freq_string_m64_exact7").get
    val items = decoded(f, Some(FrequencyItemType.Text)).rows().map(r => itemKey(r.item)).toSet
    assert(items.contains("naïve-日本語-✓"), items.toString)
    assert(items.contains(""))
    assert(items.contains("x" * 300))
  }

  test("the owner's real sketch: browser platforms, exact counts adding up to 16,924,897") {
    val f = fixtures.find(_.name == "frequency_strings_platforms").get
    val d = decoded(f, None)
    assertEquals(d.itemType, Some(FrequencyItemType.Text))
    assertEquals((d.streamLength, d.numActive, d.maximumError), (16924897L, 29, 0L))
    assertEquals(d.rows().head.item, Value.Text("iPhone"))
    assertEquals(d.rows().head.estimate, 12866315L)
    assertEquals(d.rows().map(_.estimate).sum, 16924897L)
  }
}
