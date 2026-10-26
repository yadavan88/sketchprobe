package sketchprobe.preflight

import sketchprobe.CorpusFixtures.closeTo
import sketchprobe.{CorpusFixtures, ForgedFixtures, Fixture => CorpusFixture}
import sketchprobe.decode.theta.ThetaKind

/** The Theta preflight accepts every real Theta blob and rejects every hostile one, before the
  * library sees it.
  */
class ThetaPreflightSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("theta")
  private def isUnion(f: CorpusFixture): Boolean = f.header("familyId").num.toInt == 4

  private def facts(f: CorpusFixture): ThetaFacts =
    ThetaPreflight.check(f.bytes) match {
      case Right(x) => x
      case Left(d)  => fail(s"$f was rejected: ${d.code.label}: ${d.reason}")
    }

  test(
    "every Theta corpus blob passes, at exactly the size its layout predicts, for all five families"
  ) {
    assert(fixtures.size >= 70, s"only ${fixtures.size} Theta fixtures")
    fixtures.foreach { f =>
      val x = facts(f)
      assert(x.length.matches, s"$f has trailing or missing bytes: ${x.length}")
    }
    assertEquals(fixtures.map(f => facts(f).kind).toSet, ThetaKind.values.toSet)
  }

  test(
    "the family and the preamble size come out right, with the resize factor masked out of byte 0"
  ) {
    fixtures.foreach { f =>
      val x = facts(f)
      assertEquals(x.kind.familyId, f.header("familyId").num.toInt, s"family of $f")
      assertEquals(x.preambleLongs, f.header("byte0").num.toInt & 0x3f, s"preamble of $f")
    }
    val updates = fixtures.filter(f => Set(1, 2).contains(f.header("familyId").num.toInt))
    assert(
      updates.nonEmpty && updates.forall(f =>
        f.header("byte0").num.toInt == 195 && facts(f).preambleLongs == 3
      )
    )
  }

  test("emptiness, ordering and the retained count agree with the manifest") {
    fixtures.foreach { f =>
      val x = facts(f)
      assertEquals(x.empty, f.expected("empty").bool, s"empty of $f")
      if (x.kind == ThetaKind.Compact)
        assertEquals(x.ordered, f.expected("ordered").bool, s"ordered of $f")
      // For a union the manifest reports the result, which can be smaller than the state that was stored.
      if (!isUnion(f))
        assertEquals(x.retained, f.expected("retainedValid").num.toInt, s"retained of $f")
    }
  }

  test("theta agrees with the manifest, as a fraction of 2^63") {
    fixtures.filterNot(isUnion).foreach { f =>
      val x = facts(f)
      assert(closeTo(x.thetaLong / Long.MaxValue.toDouble, f.expected("theta").num), s"theta of $f")
    }
  }

  test("the stored seed hash agrees with the manifest, except for empty sketches (Java writes 0)") {
    fixtures.filterNot(f => facts(f).empty).foreach { f =>
      assertEquals(facts(f).seedHash, f.expected("seedHash").num.toInt, s"seed hash of $f")
    }
  }

  test(
    "a single item is recognized from the preamble size, because C++ does not set the single-item flag"
  ) {
    val single = fixtures.filter(_.name == "theta_compact_ordered_n1")
    assertEquals(single.map(_.writer).toSet, Set("java-6.2.0", "java-9.0.0", "cpp-5.2.0"))
    single.foreach(f => assertEquals(facts(f).shape, "single item", s"shape of $f"))
  }

  test("the compressed layout (serial version 4) is accepted with its own size rule") {
    val compressed = fixtures.filter(_.header("serVer").num.toInt == 4)
    assertEquals(compressed.size, 7)
    compressed.foreach { f =>
      val x = facts(f)
      assertEquals(x.serVer, 4)
      assert(x.shape.startsWith("compressed"), s"shape of $f: ${x.shape}")
      assert(!x.empty && x.ordered)
    }
  }

  test("every forged Theta blob is rejected with the diagnosis the manifest requires") {
    val cases = ForgedFixtures.family("theta").filter(_.isForged)
    assertEquals(cases.size, 9)
    cases.foreach { c =>
      ThetaPreflight.check(c.bytes) match {
        case Left(d)  => assertEquals(d.code.label, c.expect, s"$c (${c.what}): ${d.reason}")
        case Right(_) => fail(s"$c (${c.what}) was accepted")
      }
    }
  }

  test("every truncated Theta blob, plain and compressed, is rejected as incomplete") {
    val cases = ForgedFixtures.family("theta").filter(_.isTruncated)
    assert(cases.size >= 20)
    cases.foreach { c =>
      assertEquals(ThetaPreflight.check(c.bytes).left.map(_.code.label), Left("incomplete"), s"$c")
    }
  }

  test("too short, big-endian, legacy versions and AnotB each get their own diagnosis") {
    assertEquals(
      ThetaPreflight.check(Array[Byte](1, 3, 3)).left.map(_.code.label),
      Left("too-short")
    )
    val base = fixtures.find(_.name == "theta_compact_ordered_n100").get.bytes
    val bigEndian = base.clone()
    bigEndian(5) = (bigEndian(5) | 1).toByte
    assertEquals(ThetaPreflight.check(bigEndian).left.map(_.code.label), Left("big-endian"))
    val legacy = base.clone()
    legacy(1) = 2
    assertEquals(ThetaPreflight.check(legacy).left.map(_.code.label), Left("unsupported-layout"))
    val anotb = base.clone()
    anotb(2) = 6
    assertEquals(ThetaPreflight.check(anotb).left.map(_.code.label), Left("unsupported-family"))
  }

  test("random bytes with a Theta header never make the preflight throw") {
    val rnd = new scala.util.Random(11)
    (0 until 5000).foreach { _ =>
      val b = new Array[Byte](rnd.nextInt(3000))
      rnd.nextBytes(b)
      if (b.length > 8) {
        b(1) = (3 + rnd.nextInt(2)).toByte
        b(2) = (1 + rnd.nextInt(5)).toByte
      }
      ThetaPreflight.check(b) // must return a value, never throw
    }
  }
}
