package sketchprobe.decode.kll

import sketchprobe.CorpusFixtures
import sketchprobe.adapter.KllAdapter
import sketchprobe.preflight.KllPreflight
import sketchprobe.report.Value

/** Every KLL blob in the corpus decodes to the values the writing library reported (spec FR-013,
  * FR-014, FR-018).
  */
class KllCorpusSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("kll")
  private def isUpdatable(f: sketchprobe.Fixture): Boolean = f.header("serVer").num.toInt == 3
  // Every layout decodes now; `decodable` is the compact ones, for the tests about compact shapes.
  private def decodable = fixtures.filterNot(isUpdatable)

  private def typeOf(f: sketchprobe.Fixture): KllItemType =
    KllItemType.parse(f.params("itemType").str).toOption.get

  private def decoded(f: sketchprobe.Fixture): KllData =
    KllAdapter.read(f.bytes, typeOf(f)) match {
      case Right(d)   => d
      case Left(diag) => fail(s"$f did not decode: ${diag.code.label}: ${diag.reason}")
    }

  /** A manifest number: JSON numbers for float and double, decimal strings for long. */
  private def expectedValue(f: sketchprobe.Fixture, v: ujson.Value): Value =
    typeOf(f) match {
      case KllItemType.Int64 => Value.Int64(v.str.toLong)
      case _                 => Value.Num(v.num)
    }

  test("the corpus has float, double and long KLL blobs from several writers") {
    assertEquals(fixtures.map(_.params("itemType").str).toSet, Set("float", "double", "long"))
    assert(fixtures.map(_.writer).toSet.size >= 5)
    assert(decodable.size >= 80, s"only ${decodable.size} decodable KLL fixtures")
  }

  test("n, k and the retained count match the manifest for every compact blob") {
    fixtures.foreach { f =>
      val d = decoded(f)
      assertEquals(d.header.n, f.expected("n").num.toLong, s"n of $f")
      assertEquals(d.header.k, f.expected("k").num.toInt, s"k of $f")
      assertEquals(d.header.retained, f.expected("retained").num.toInt, s"retained of $f")
    }
  }

  /** The older Java line 5.0.2 and datasketches-cpp 5.2.0 return the smallest (largest) RETAINED
    * item at rank 0 (1), not the stream minimum (maximum). The current Java lines return the
    * minimum and maximum, which is what we do. Interior ranks must match exactly for every writer
    * (fixtures/README.md, "Writer differences").
    */
  private val retainedEndsWriters = Set("cpp-5.2.0", "java-5.0.2")

  private def num(v: Value): Double = v match {
    case Value.Num(d)   => d
    case Value.Int64(l) => l.toDouble
    case other          => fail(s"not a number: $other")
  }

  test("min and max match the manifest exactly") {
    fixtures.filter(_.expected.obj.contains("min")).foreach { f =>
      val d = decoded(f)
      assertEquals(d.min, Some(expectedValue(f, f.expected("min"))), s"min of $f")
      assertEquals(d.max, Some(expectedValue(f, f.expected("max"))), s"max of $f")
    }
  }

  test("quantiles at every interior rank match the manifest exactly, for every writer") {
    fixtures.filter(_.expected.obj.contains("min")).foreach { f =>
      val d = decoded(f)
      val want = f.expected("quantiles").arr.map(p => (p(0).num, expectedValue(f, p(1)))).toList
      assertEquals(d.quantiles.map(_._1), want.map(_._1), s"ranks of $f")
      d.quantiles.zip(want).filter { case ((rank, _), _) => rank > 0.0 && rank < 1.0 }.foreach {
        case ((rank, ours), (_, theirs)) =>
          assertEquals(ours, theirs, s"quantile at rank $rank of $f")
      }
    }
  }

  test("at ranks 0 and 1 we return the true min and max; the current Java lines agree exactly") {
    fixtures.filter(_.expected.obj.contains("min")).foreach { f =>
      val d = decoded(f)
      val want = f.expected("quantiles").arr.map(p => (p(0).num, expectedValue(f, p(1)))).toMap
      assertEquals(d.quantiles.head, (0.0, d.min.get), s"rank 0 of $f")
      assertEquals(d.quantiles.last, (1.0, d.max.get), s"rank 1 of $f")
      if (!retainedEndsWriters.contains(f.writer)) {
        assertEquals(want(0.0), d.min.get, s"manifest rank 0 of $f")
        assertEquals(want(1.0), d.max.get, s"manifest rank 1 of $f")
      } else {
        // The older writers report an end of the retained sample, which is never beyond the true min or max.
        val retained = d.items().map(num)
        assert(num(want(0.0)) == num(d.min.get) || num(want(0.0)) == retained.min, s"rank 0 of $f")
        assert(num(want(1.0)) == num(d.max.get) || num(want(1.0)) == retained.max, s"rank 1 of $f")
      }
    }
  }

  test("an empty sketch has no min, max or quantiles") {
    val empties = decodable.filter(_.expected("empty").bool)
    assert(empties.nonEmpty)
    empties.foreach { f =>
      val d = decoded(f)
      assertEquals((d.header.n, d.min, d.max, d.quantiles), (0L, None, None, Nil), s"$f")
      assertEquals(d.header.shape, KllShape.Empty)
    }
  }

  test("a single-item sketch reports that item as min, max and every quantile") {
    val singles = decodable.filter(f => f.expected("n").num == 1)
    assert(singles.nonEmpty)
    singles.foreach { f =>
      val d = decoded(f)
      assertEquals(d.header.shape, KllShape.SingleItem, s"shape of $f")
      assertEquals(d.min, d.max)
    }
  }

  test("the item weights add up to n in every full sketch") {
    decodable.filter(f => decoded(f).header.shape == KllShape.CompactFull).foreach { f =>
      assert(decoded(f).header.weightsConsistent, s"weights of $f do not add up to n")
    }
  }

  test("every blob is complete and exactly the expected size") {
    fixtures.foreach { f =>
      val d = decoded(f)
      assert(d.length.matches, s"$f has trailing or missing bytes: ${d.length}")
    }
  }

  test("the type-independent header is available without an item type") {
    fixtures.foreach { f =>
      val h = KllPreflight.check(f.bytes, None).fold(d => fail(s"$f: ${d.reason}"), identity)
      assertEquals(h.n, f.expected("n").num.toLong, s"n of $f")
      assertEquals(h.retained, f.expected("retained").num.toInt, s"retained of $f")
    }
  }

  test(
    "the updatable layout (serVer 3) decodes, with the same n and retained count as the writer"
  ) {
    val updatable = fixtures.filter(isUpdatable)
    assert(updatable.nonEmpty)
    updatable.foreach { f =>
      val d = decoded(f)
      assertEquals(d.header.n, f.expected("n").num.toLong, s"n of $f")
      assertEquals(d.items().size, f.expected("retained").num.toInt, s"retained of $f")
    }
  }

  test(
    "item type names: float, double and long parse; string is recognized but unsupported; others are usage errors"
  ) {
    assertEquals(KllItemType.parse("float"), Right(KllItemType.Float32))
    assertEquals(KllItemType.parse("DOUBLE"), Right(KllItemType.Float64))
    assertEquals(KllItemType.parse("long"), Right(KllItemType.Int64))
    assertEquals(KllItemType.parse("string").left.map(_.code.label), Left("unsupported-layout"))
    assertEquals(KllItemType.parse("int").left.map(_.code.label), Left("usage"))
  }

  test("retained items are listed on demand and as many as the count") {
    fixtures.foreach { f =>
      assertEquals(decoded(f).items().size, f.expected("retained").num.toInt, s"items of $f")
    }
  }
}
