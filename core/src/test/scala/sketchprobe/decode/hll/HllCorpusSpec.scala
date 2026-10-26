package sketchprobe.decode.hll

import sketchprobe.CorpusFixtures
import sketchprobe.adapter.HllAdapter
import sketchprobe.CorpusFixtures.closeTo

/** Every HLL blob in the corpus decodes to the values the writing library reported (spec FR-011,
  * FR-018).
  */
class HllCorpusSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("hll")

  test("the corpus has HLL blobs to test") {
    assert(fixtures.size >= 100, s"only ${fixtures.size} HLL fixtures")
  }

  private def decoded(f: sketchprobe.Fixture): HllData =
    HllAdapter.read(f.bytes) match {
      case Right(d)   => d
      case Left(diag) => fail(s"$f did not decode: ${diag.code.label}: ${diag.reason}")
    }

  /** Bounds from datasketches-cpp 5.2.0 and older use truncated constants (0.8325546 and 1.03896,
    * instead of sqrt(ln 2) and sqrt(3 ln 2 - 1)) for lgK above 12. Java uses the exact values and
    * we match Java bit for bit. The difference is at most 1.3e-10 relative, so C++ bounds at lgK
    * above 12 get a looser tolerance. Everything else, including every C++ estimate, must match to
    * 1e-12 (fixtures/README.md, research R12).
    */
  private def boundTolerance(f: sketchprobe.Fixture, lgK: Int): Double =
    if f.writer.startsWith("cpp-") && lgK > 12 then 1e-9 else 1e-12

  test("estimate, bounds, lgK, register width and emptiness match the manifest for every blob") {
    fixtures.foreach { f =>
      val d = decoded(f)
      val e = f.expected
      assert(
        closeTo(d.estimate, e("estimate").num),
        s"estimate of $f: ours ${d.estimate} vs ${e("estimate").num}"
      )
      val tol = boundTolerance(f, d.lgK)
      (1 to 3).foreach { sd =>
        val lo = e(s"lowerBound$sd").num
        val hi = e(s"upperBound$sd").num
        assert(
          closeTo(d.lowerBounds(sd - 1), lo, tol),
          s"lowerBound$sd of $f: ours ${d.lowerBounds(sd - 1)} vs $lo"
        )
        assert(
          closeTo(d.upperBounds(sd - 1), hi, tol),
          s"upperBound$sd of $f: ours ${d.upperBounds(sd - 1)} vs $hi"
        )
      }
      assertEquals(d.lgK, e("lgK").num.toInt, s"lgK of $f")
      assertEquals(d.width.label, e("targetType").str, s"register width of $f")
      assertEquals(d.empty, e("empty").bool, s"empty of $f")
    }
  }

  test("the Java writers match bit for bit: estimate and every bound are exactly equal") {
    fixtures.filter(_.writer.startsWith("java-")).foreach { f =>
      val d = decoded(f)
      assertEquals(d.estimate, f.expected("estimate").num, s"estimate of $f")
      (1 to 3).foreach { sd =>
        assertEquals(
          d.lowerBounds(sd - 1),
          f.expected(s"lowerBound$sd").num,
          s"lowerBound$sd of $f"
        )
        assertEquals(
          d.upperBounds(sd - 1),
          f.expected(s"upperBound$sd").num,
          s"upperBound$sd of $f"
        )
      }
    }
  }

  test("merged (union) sketches are out-of-order and use the composite estimator") {
    val unions = fixtures.filter(_.name.contains("_union_"))
    assert(unions.nonEmpty)
    val inHllMode = unions.filter(f => decoded(f).mode == HllMode.Hll)
    assert(
      inHllMode.nonEmpty,
      "no union fixture reached HLL mode, so the composite estimator is untested"
    )
    // The composite estimator ran for at least one blob from each writer line that has unions.
    assertEquals(inHllMode.map(_.writer).toSet, Set("cpp-5.2.0", "java-6.2.0", "java-9.0.0"))
    unions.foreach { f =>
      val d = decoded(f)
      // Only a sketch in HLL mode carries the flag; a small union can still be in LIST or SET mode.
      if d.mode == HllMode.Hll then assert(d.outOfOrder, s"$f should be out-of-order")
    }
  }

  test("the mode follows the preamble size: LIST 2 ints, SET 3 ints, HLL 10 ints") {
    fixtures.foreach { f =>
      val d = decoded(f)
      val expected = d.mode match {
        case HllMode.List => 2
        case HllMode.Set  => 3
        case HllMode.Hll  => 10
      }
      assertEquals(f.header("byte0").num.toInt, expected, s"preamble size of $f")
    }
  }

  test("every blob is complete: it is at least as long as the layout needs") {
    fixtures.foreach { f =>
      val d = decoded(f)
      assert(d.length.complete, s"$f: ${d.length}")
    }
  }

  test("both layouts are present and the length check recognizes the real sizes") {
    val compact = fixtures.filter(_.name.endsWith("_compact"))
    val updatable = fixtures.filter(_.name.endsWith("_updatable"))
    assert(compact.nonEmpty && updatable.nonEmpty)
    // Where the writer really produced a compact or updatable layout, the size is exactly the predicted one.
    val exact = fixtures.count(f => decoded(f).length.matches)
    assertEquals(
      exact,
      fixtures.size,
      "every blob should match the compact or the updatable size exactly"
    )
  }
}
