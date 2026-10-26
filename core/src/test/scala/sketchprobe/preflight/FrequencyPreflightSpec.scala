package sketchprobe.preflight

import sketchprobe.{CorpusFixtures, ForgedCase, ForgedFixtures, Fixture => CorpusFixture}

/** Every Frequency blob the tool must accept is accepted, every hostile one is rejected with the
  * diagnosis the manifest requires, and long items are told from string items by the bytes (spec
  * FR-019, FR-026, FR-027, plan amendment 2026-10-26).
  */
class FrequencyPreflightSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("frequency")
  private val hostile = ForgedFixtures.family("frequency")

  private def typeOf(itemType: String): FrequencyItemType = itemType match {
    case "long"   => FrequencyItemType.Int64
    case "string" => FrequencyItemType.Text
    case other    => fail(s"unknown item type $other")
  }
  private def trueType(f: CorpusFixture): FrequencyItemType = typeOf(f.params("itemType").str)
  private def facts(f: CorpusFixture, t: Option[FrequencyItemType]): FrequencyFacts =
    FrequencyPreflight.check(f.bytes, t) match {
      case Right(x) => x
      case Left(d)  => fail(s"$f was rejected: ${d.code.label}: ${d.reason}")
    }

  test("the corpus has Frequency blobs: long and string items, empty, exact and purged") {
    assertEquals(fixtures.size, 25)
    assertEquals(fixtures.map(_.params("itemType").str).toSet, Set("long", "string"))
    assert(fixtures.exists(_.expected("empty").bool))
    assert(fixtures.exists(f => f.expected("maximumError").num > 0), "no purged sketch")
    assert(fixtures.exists(f => f.expected("maximumError").num == 0 && !f.expected("empty").bool))
  }

  test("every corpus blob is accepted with its item type, and the facts equal the manifest") {
    fixtures.foreach { f =>
      val x = facts(f, Some(trueType(f)))
      val e = f.expected
      assertEquals(x.empty, e("empty").bool, s"empty of $f")
      assertEquals(x.numActive, e("numActive").num.toInt, s"numActive of $f")
      assertEquals(x.maxMapCapacity, e("maximumMapCapacity").num.toInt, s"max capacity of $f")
      if (!x.empty) {
        assertEquals(x.streamLength, e("streamLength").num.toLong, s"stream length of $f")
        assertEquals(x.offset, e("maximumError").num.toLong, s"offset (maximum error) of $f")
        assertEquals(x.curMapCapacity, e("currentMapCapacity").num.toInt, s"capacity of $f")
        assertEquals(x.maxMapSize, f.params("maxMapSize").num.toInt, s"max map size of $f")
      }
    }
  }

  test("every corpus blob is exactly as long as its layout needs, for its own item type") {
    fixtures.foreach { f =>
      val x = facts(f, Some(trueType(f)))
      assert(x.length(trueType(f)).matches, s"$f: ${x.length(trueType(f))}")
    }
  }

  test(
    "without an item type, the bytes decide: one candidate for every non-empty blob, and it is the right one"
  ) {
    fixtures.filterNot(_.expected("empty").bool).foreach { f =>
      val x = facts(f, None)
      assertEquals(x.candidateTypes, Set(trueType(f)), s"candidates for $f")
    }
  }

  test("an empty sketch cannot tell long from string, so both are candidates") {
    fixtures.filter(_.expected("empty").bool).foreach { f =>
      assertEquals(
        facts(f, None).candidateTypes,
        Set(FrequencyItemType.Int64, FrequencyItemType.Text),
        s"$f"
      )
    }
  }

  test("the wrong item type is refused, saying the type is the problem") {
    fixtures.filterNot(_.expected("empty").bool).foreach { f =>
      val wrong =
        if (trueType(f) == FrequencyItemType.Int64) FrequencyItemType.Text
        else FrequencyItemType.Int64
      FrequencyPreflight.check(f.bytes, Some(wrong)) match {
        case Left(d)  => assertEquals(d.code.label, "needs-item-type", s"$f as $wrong")
        case Right(_) => fail(s"$f was accepted as $wrong")
      }
    }
  }

  test("a valid blob with trailing bytes is complete but not an exact match") {
    fixtures.filterNot(_.expected("empty").bool).take(8).foreach { f =>
      val padded = f.bytes ++ Array.fill[Byte](5)(0)
      val x = FrequencyPreflight.check(padded, Some(trueType(f))).toOption.get
      val len = x.length(trueType(f))
      assert(len.complete && !len.matches, s"$f: $len")
    }
  }

  private def expectCode(c: ForgedCase): Either[String, FrequencyFacts] =
    FrequencyPreflight.check(c.bytes, Some(typeOf(c.itemType))).left.map(_.code.label)

  test(
    "the hostile Frequency set: 20 forged and 26 truncated, each rejected as the manifest says, within 5 seconds"
  ) {
    assertEquals(hostile.size, 46)
    hostile.foreach { c =>
      val started = System.nanoTime()
      val result = expectCode(c)
      assert((System.nanoTime() - started) / 1000000 < 5000, s"$c too slow")
      if (c.mustBeAccepted) assert(result.isRight, s"$c should be accepted")
      else assertEquals(result.map(_ => ()), Left(c.expect), s"$c (${c.what})")
    }
  }

  test("a forged count is refused before anything is allocated from it") {
    // numActive 48 on a 144-byte blob claims 800 bytes; the preflight must answer from the header alone.
    val c = hostile.find(_.name == "freq_long_numActive_48_on_144_bytes").get
    val before = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
    val r = FrequencyPreflight.check(c.bytes, Some(FrequencyItemType.Int64))
    val grown = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory - before
    assertEquals(r.left.map(_.code.label), Left("incomplete"))
    assert(grown < 64L * 1024 * 1024, s"allocated about $grown bytes")
  }

  test(
    "10,000 random blobs with a Frequency-looking header never make it throw, and finish quickly"
  ) {
    val rnd = new scala.util.Random(10)
    val started = System.nanoTime()
    (0 until 10000).foreach { _ =>
      val b = new Array[Byte](8 + rnd.nextInt(600))
      rnd.nextBytes(b)
      b(0) = Seq(1, 4, 2, 0, 255)(rnd.nextInt(5)).toByte
      b(1) = 1
      b(2) = 10
      b(3) = (3 + rnd.nextInt(5)).toByte
      b(4) = (3 + rnd.nextInt(5)).toByte
      FrequencyPreflight.check(
        b,
        Seq(None, Some(FrequencyItemType.Int64), Some(FrequencyItemType.Text))(rnd.nextInt(3))
      )
    }
    assert((System.nanoTime() - started) / 1000000 < 20000)
  }

  test("single-bit flips of every small corpus blob never make it throw") {
    fixtures.filter(_.bytes.length < 700).foreach { f =>
      (0 until math.min(f.bytes.length * 8, 400)).foreach { bit =>
        val b = f.bytes.clone()
        b(bit / 8) = (b(bit / 8) ^ (1 << (bit % 8))).toByte
        FrequencyPreflight.check(b, Some(trueType(f)))
        FrequencyPreflight.check(b, None)
      }
    }
  }
}
