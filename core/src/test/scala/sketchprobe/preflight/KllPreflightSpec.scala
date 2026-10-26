package sketchprobe.preflight

import sketchprobe.{CorpusFixtures, ForgedFixtures, Fixture => CorpusFixture}

/** The KLL preflight accepts every real KLL blob in both layouts and rejects every hostile one,
  * before the library sees it.
  */
class KllPreflightSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("kll")
  private def itemSize(f: CorpusFixture): Int = if (f.params("itemType").str == "float") 4 else 8
  private def isUpdatable(f: CorpusFixture): Boolean = f.header("serVer").num.toInt == 3

  private def facts(f: CorpusFixture): KllFacts =
    KllPreflight.check(f.bytes, Some(itemSize(f))) match {
      case Right(x) => x
      case Left(d)  => fail(s"$f was rejected: ${d.code.label}: ${d.reason}")
    }

  test(
    "every KLL corpus blob passes at exactly the size its layout predicts, compact and updatable"
  ) {
    assert(fixtures.size >= 100, s"only ${fixtures.size} KLL fixtures")
    assertEquals(fixtures.count(isUpdatable), 21)
    fixtures.foreach { f =>
      val x = facts(f)
      assert(
        x.length(itemSize(f)).matches,
        s"$f has trailing or missing bytes: ${x.length(itemSize(f))}"
      )
    }
  }

  test("the layout is told apart: serial version 3 is the updatable layout") {
    fixtures.foreach { f =>
      assertEquals(
        facts(f).kind,
        if (isUpdatable(f)) KllLayoutKind.Updatable else KllLayoutKind.Compact,
        s"$f"
      )
    }
  }

  test("n, k and the retained count agree with the manifest, from the header alone") {
    fixtures.foreach { f =>
      val x = facts(f)
      assertEquals(x.n, f.expected("n").num.toLong, s"n of $f")
      assertEquals(x.k, f.expected("k").num.toInt, s"k of $f")
      assertEquals(x.retained, f.expected("retained").num.toInt, s"retained of $f")
    }
  }

  test("the retained weights add up to n in every full sketch, in both layouts") {
    fixtures.foreach(f => assert(facts(f).weightsConsistent, s"weights of $f do not add up to n"))
  }

  test(
    "without an item type the blob is still checked, and the length shows which item sizes fit exactly"
  ) {
    fixtures.foreach { f =>
      KllPreflight.check(f.bytes, None) match {
        case Right(x) =>
          assert(
            x.exactItemSizes.contains(itemSize(f)),
            s"$f: exact sizes ${x.exactItemSizes}, expected ${itemSize(f)}"
          )
        case Left(d) => fail(s"$f was rejected without a type: ${d.code.label}: ${d.reason}")
      }
    }
    // A float sketch is told apart from a double or long one by its length (a sketch with no items cannot be).
    val bigFloat =
      fixtures.filter(f => f.params("itemType").str == "float" && f.expected("n").num > 1000)
    assert(bigFloat.nonEmpty)
    bigFloat.foreach(f =>
      assertEquals(KllPreflight.check(f.bytes, None).toOption.get.exactItemSizes, Set(4), s"$f")
    )
  }

  test(
    "every forged KLL blob is rejected with the diagnosis the manifest requires, with or without an item type"
  ) {
    val cases = ForgedFixtures.family("kll").filter(c => c.isForged && !c.mustBeAccepted)
    assertEquals(cases.size, 6)
    cases.foreach { c =>
      Seq(Some(4), None).foreach { size =>
        KllPreflight.check(c.bytes, size) match {
          case Left(d) =>
            assertEquals(d.code.label, c.expect, s"$c (${c.what}), type size $size: ${d.reason}")
          case Right(_) => fail(s"$c (${c.what}) was accepted, type size $size")
        }
      }
    }
  }

  test("minK above k is a soft inconsistency: the blob is accepted and the issue is recorded") {
    val c = ForgedFixtures.all.find(_.mustBeAccepted).get
    val x = KllPreflight.check(c.bytes, Some(4)).toOption.get
    assert(x.softIssues.exists(_.contains("minK")), x.softIssues.toString)
    assertEquals(
      KllPreflight
        .check(fixtures.head.bytes, Some(itemSize(fixtures.head)))
        .toOption
        .get
        .softIssues,
      Nil
    )
  }

  test("the updatable layout cannot claim capacity the blob does not hold") {
    // k 65535 with 61 levels claims gigabytes of items. It must be refused from the header alone, as incomplete.
    val base = fixtures
      .find(f => isUpdatable(f) && f.expected("n").num == 10000 && itemSize(f) == 4)
      .get
      .bytes
    val forged = base.clone()
    forged(4) = 0xff.toByte
    forged(5) = 0xff.toByte
    forged(18) = 61
    val started = System.nanoTime()
    assertEquals(KllPreflight.check(forged, Some(4)).left.map(_.code.label), Left("incomplete"))
    assertEquals(KllPreflight.check(forged, None).left.map(_.code.label), Left("incomplete"))
    assert((System.nanoTime() - started) / 1000000 < 1000, "the refusal should be instant")
  }

  test("every truncated KLL blob is rejected as incomplete") {
    val cases = ForgedFixtures.family("kll").filter(_.isTruncated)
    assertEquals(cases.size, 13)
    cases.foreach(c =>
      assertEquals(
        KllPreflight.check(c.bytes, Some(4)).left.map(_.code.label),
        Left("incomplete"),
        s"$c"
      )
    )
  }

  test("too short, a wrong family, and impossible version and preamble combinations are rejected") {
    assertEquals(
      KllPreflight.check(Array[Byte](2, 1, 15), None).left.map(_.code.label),
      Left("too-short")
    )
    val base = fixtures.find(f => !isUpdatable(f) && f.expected("n").num == 10000).get.bytes
    val wrongFamily = base.clone()
    wrongFamily(2) = 7
    assertEquals(
      KllPreflight.check(wrongFamily, Some(4)).left.map(_.code.label),
      Left("not-a-sketch")
    )
    val updatableWithSmallPreamble = base.clone()
    updatableWithSmallPreamble(1) = 3
    updatableWithSmallPreamble(0) = 2
    assertEquals(
      KllPreflight.check(updatableWithSmallPreamble, Some(4)).left.map(_.code.label),
      Left("not-a-sketch")
    )
    val singleWithBigPreamble = base.clone()
    singleWithBigPreamble(1) = 2
    assertEquals(
      KllPreflight.check(singleWithBigPreamble, Some(4)).left.map(_.code.label),
      Left("not-a-sketch")
    )
  }

  test("random bytes with a KLL header never make the preflight throw") {
    val rnd = new scala.util.Random(13)
    (0 until 5000).foreach { _ =>
      val b = new Array[Byte](rnd.nextInt(3000))
      rnd.nextBytes(b)
      if (b.length > 8) {
        val (pre, ser) = Seq((2, 1), (2, 2), (5, 1), (5, 3))(rnd.nextInt(4))
        b(0) = pre.toByte
        b(1) = ser.toByte
        b(2) = 15
      }
      KllPreflight.check(
        b,
        if (rnd.nextBoolean()) Some(4) else None
      ) // must return a value, never throw
    }
  }
}
