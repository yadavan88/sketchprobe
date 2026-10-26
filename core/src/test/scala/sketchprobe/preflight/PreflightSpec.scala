package sketchprobe.preflight

import sketchprobe.{CorpusFixtures, ForgedFixtures, Fixture => CorpusFixture, ForgedCase}

/** One entry point for every family: every real blob is accepted, every hostile one rejected with
  * the right diagnosis, each within 5 seconds (SC-003), before the library could see it.
  */
class PreflightSpec extends munit.FunSuite {
  private def sizeOf(itemType: String): Option[Int] = itemType match {
    case "float"           => Some(4)
    case "double" | "long" => Some(8)
    case _                 => None
  }
  private def typeOf(f: CorpusFixture): Option[Int] =
    if (f.family == "kll") sizeOf(f.params("itemType").str) else None
  private def typeOf(c: ForgedCase): Option[Int] =
    if (c.family == "kll") sizeOf(c.itemType) else None
  private def freqTypeOf(c: ForgedCase): Option[FrequencyItemType] = c.itemType match {
    case "long"   => Some(FrequencyItemType.Int64)
    case "string" => Some(FrequencyItemType.Text)
    case _        => None
  }

  test("every corpus blob is accepted, as the right kind of facts for its family") {
    assertEquals(CorpusFixtures.all.size, 406)
    CorpusFixtures.all.foreach { f =>
      Preflight.check(f.bytes, typeOf(f)) match {
        case Right(Checked.Hll(_))       => assertEquals(f.family, "hll", s"$f")
        case Right(Checked.Theta(_))     => assertEquals(f.family, "theta", s"$f")
        case Right(Checked.Kll(_))       => assertEquals(f.family, "kll", s"$f")
        case Right(Checked.Frequency(_)) => assertEquals(f.family, "frequency", s"$f")
        case Left(d)                     => fail(s"$f was rejected: ${d.code.label}: ${d.reason}")
      }
    }
  }

  test(
    "every forged and truncated blob gets the diagnosis the manifest requires, within 5 seconds each"
  ) {
    assertEquals(ForgedFixtures.all.size, 121)
    ForgedFixtures.all.foreach { c =>
      val started = System.nanoTime()
      val result = Preflight.check(c.bytes, typeOf(c), freqTypeOf(c))
      val ms = (System.nanoTime() - started) / 1000000
      assert(ms < 5000, s"$c took $ms ms")
      if (c.mustBeAccepted)
        assert(result.isRight, s"$c (${c.what}) should be accepted with a warning")
      else assertEquals(result.left.map(_.code.label), Left(c.expect), s"$c (${c.what})")
    }
  }

  test("an unknown family ID is not a sketch") {
    val base = CorpusFixtures.family("hll").head.bytes
    Seq(0, 23, 50, 255).foreach { id =>
      val b = base.clone()
      b(2) = id.toByte
      assertEquals(
        Preflight.check(b, None).left.map(_.code.label),
        Left("not-a-sketch"),
        s"family $id"
      )
    }
  }

  test(
    "a family that is recognized but not supported is named, and is not treated as an error in the blob"
  ) {
    val base = CorpusFixtures.family("hll").head.bytes
    (Seq(6, 8, 9, 11, 12, 13, 14) ++ (16 to 22)).foreach { id =>
      val b = base.clone()
      b(2) = id.toByte
      Preflight.check(b, None) match {
        case Left(d) =>
          assertEquals(d.code.label, "unsupported-family", s"family $id")
          assert(!d.reason.isEmpty)
        case Right(_) => fail(s"family $id was accepted")
      }
    }
  }

  test("fewer than 8 bytes is too short, whatever the header says") {
    assertEquals(
      Preflight.check(Array.emptyByteArray, None).left.map(_.code.label),
      Left("too-short")
    )
    assertEquals(
      Preflight.check(Array[Byte](2, 1, 15, 0, 0), None).left.map(_.code.label),
      Left("too-short")
    )
  }

  test(
    "10,000 random blobs, half with a plausible header, never make it throw and always finish quickly"
  ) {
    val rnd = new scala.util.Random(2026)
    val started = System.nanoTime()
    (0 until 10000).foreach { i =>
      val b = new Array[Byte](rnd.nextInt(4000))
      rnd.nextBytes(b)
      if (i % 2 == 0 && b.length > 8) {
        b(2) = Seq(1, 2, 3, 4, 5, 7, 10, 15)(rnd.nextInt(8)).toByte
        b(1) = rnd.nextInt(5).toByte
      }
      Preflight.check(b, if (rnd.nextBoolean()) None else Some(8)) // must return, never throw
    }
    assert(
      (System.nanoTime() - started) / 1000000 < 20000,
      "10,000 blobs should take well under 20 seconds"
    )
  }

  test("single-bit flips of a valid blob never make it throw") {
    CorpusFixtures.all.filter(f => f.bytes.length < 400).take(60).foreach { f =>
      (0 until math.min(f.bytes.length * 8, 160)).foreach { bit =>
        val b = f.bytes.clone()
        b(bit / 8) = (b(bit / 8) ^ (1 << (bit % 8))).toByte
        Preflight.check(b, typeOf(f))
      }
    }
  }
}
