package sketchprobe.preflight

import sketchprobe.{CorpusFixtures, ForgedFixtures, Fixture => CorpusFixture}
import sketchprobe.decode.hll.HllMode

/** The HLL preflight accepts every real HLL blob and rejects every hostile one, before the library
  * sees it.
  */
class HllPreflightSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("hll")

  private def facts(f: CorpusFixture): HllFacts =
    HllPreflight.check(f.bytes) match {
      case Right(x) => x
      case Left(d)  => fail(s"$f was rejected: ${d.code.label}: ${d.reason}")
    }

  test("every HLL corpus blob passes, at exactly the size its layout predicts") {
    assert(fixtures.size >= 190, s"only ${fixtures.size} HLL fixtures")
    fixtures.foreach { f =>
      val x = facts(f)
      assert(x.length.complete, s"$f: ${x.length}")
      assert(x.length.matches, s"$f has trailing or missing bytes: ${x.length}")
    }
  }

  test("the facts agree with the manifest: lgK, register width and emptiness") {
    fixtures.foreach { f =>
      val x = facts(f)
      assertEquals(x.lgK, f.expected("lgK").num.toInt, s"lgK of $f")
      assertEquals(x.width.label, f.expected("targetType").str, s"register width of $f")
      assertEquals(x.empty, f.expected("empty").bool, s"empty of $f")
    }
  }

  test("the mode follows the preamble size: LIST 2 ints, SET 3 ints, HLL 10 ints") {
    fixtures.foreach { f =>
      val expected = facts(f).mode match {
        case HllMode.List => 2
        case HllMode.Set  => 3
        case HllMode.Hll  => 10
      }
      assertEquals(f.header("byte0").num.toInt, expected, s"preamble size of $f")
    }
  }

  test("a merged sketch in HLL mode is out-of-order, which the library hides") {
    val merged = fixtures.filter(f => f.name.contains("_union_") && facts(f).mode == HllMode.Hll)
    assert(merged.nonEmpty, "no merged sketch reached HLL mode")
    merged.foreach(f => assert(facts(f).outOfOrder, s"$f should be out-of-order"))
  }

  test("coupon counts are only reported for LIST and SET, and HLL-mode facts only for HLL mode") {
    fixtures.foreach { f =>
      val x = facts(f)
      assertEquals(x.couponCount.isDefined, x.mode != HllMode.Hll, s"coupon count of $f")
    }
  }

  test("every forged HLL blob is rejected with the diagnosis the manifest requires") {
    val cases = ForgedFixtures.family("hll").filter(_.isForged)
    assert(cases.size >= 7)
    cases.foreach { c =>
      HllPreflight.check(c.bytes) match {
        case Left(d)  => assertEquals(d.code.label, c.expect, s"$c (${c.what}): ${d.reason}")
        case Right(_) => fail(s"$c (${c.what}) was accepted")
      }
    }
  }

  test("every truncated HLL blob is rejected as incomplete") {
    val cases = ForgedFixtures.family("hll").filter(_.isTruncated)
    assert(cases.nonEmpty)
    cases.foreach { c =>
      assertEquals(HllPreflight.check(c.bytes).left.map(_.code.label), Left("incomplete"), s"$c")
    }
  }

  test("fewer than 8 bytes is too short, and the big-endian flag is reported") {
    assertEquals(HllPreflight.check(Array[Byte](1, 2, 3)).left.map(_.code.label), Left("too-short"))
    val bytes = fixtures.head.bytes.clone()
    bytes(5) = (bytes(5) | 1).toByte
    assertEquals(HllPreflight.check(bytes).left.map(_.code.label), Left("big-endian"))
  }

  test("random bytes with an HLL header never make the preflight throw") {
    val rnd = new scala.util.Random(7)
    (0 until 5000).foreach { _ =>
      val b = new Array[Byte](rnd.nextInt(5000))
      rnd.nextBytes(b)
      if (b.length > 8) {
        b(1) = 1
        b(2) = 7
      }
      HllPreflight.check(b) // must return a value, never throw
    }
  }
}
