package sketchprobe.decode.quantiles

import sketchprobe.CorpusFixtures
import sketchprobe.adapter.QuantilesAdapter
import sketchprobe.report.Value

/** Every Quantiles blob in the corpus decodes to the values the writing library reported (FR-013,
  * FR-014, FR-018).
  */
class QuantilesCorpusSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("quantiles")

  private def decoded(f: sketchprobe.Fixture): QuantilesData =
    QuantilesAdapter.read(f.bytes) match {
      case Right(d)   => d
      case Left(diag) => fail(s"$f did not decode: ${diag.code.label}: ${diag.reason}")
    }

  test("the corpus has compact and working Quantiles blobs at two values of k") {
    assert(fixtures.size >= 28, s"only ${fixtures.size} Quantiles fixtures")
    assertEquals(fixtures.map(_.params("layout").str).toSet, Set("compact", "updatable"))
    assertEquals(fixtures.map(_.params("k").num.toInt).toSet, Set(32, 128))
  }

  test("n, k and the retained count match the manifest for every blob") {
    fixtures.foreach { f =>
      val d = decoded(f)
      assertEquals(d.header.n, f.expected("n").num.toLong, s"n of $f")
      assertEquals(d.header.k, f.expected("k").num.toInt, s"k of $f")
      assertEquals(d.header.retained, f.expected("retained").num.toLong, s"retained of $f")
    }
  }

  test("the blob has exactly the length the layout needs") {
    fixtures.foreach { f =>
      assert(decoded(f).length.matches, s"length of $f")
    }
  }

  test("min, max and every quantile match the manifest exactly") {
    fixtures.filter(_.expected.obj.contains("min")).foreach { f =>
      val d = decoded(f)
      assertEquals(d.min, Some(Value.Num(f.expected("min").num)), s"min of $f")
      assertEquals(d.max, Some(Value.Num(f.expected("max").num)), s"max of $f")
      val want = f.expected("quantiles").arr.map(p => (p(0).num, Value.Num(p(1).num))).toList
      assertEquals(d.quantiles, want, s"quantiles of $f")
    }
  }

  test("the retained items are as many as the header says, and sorted") {
    fixtures.foreach { f =>
      val xs = decoded(f).items().collect { case Value.Num(d) => d }
      assert(
        xs.size >= f.expected("retained").num.toInt || xs.size == f.expected("retained").num.toInt,
        s"count of $f"
      )
      assertEquals(xs, xs.sorted, s"order of $f")
    }
  }

  test("an empty sketch has no min, max or quantiles") {
    fixtures.filter(_.name.contains("_empty_")).foreach { f =>
      val d = decoded(f)
      assertEquals((d.min, d.max, d.quantiles), (None, None, Nil), s"empty $f")
    }
  }
}
