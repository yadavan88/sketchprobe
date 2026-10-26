package sketchprobe.detect

import sketchprobe.{CorpusFixtures, ForgedFixtures}
import sketchprobe.preflight.{Checked, Preflight}
import sketchprobe.report.{ConfidenceLevel, PreambleUnit}

class DetectorSpec extends munit.FunSuite {
  private def itemSize(family: String, itemType: => String): Option[Int] =
    if (family != "kll") None
    else
      itemType match {
        case "float" => Some(4)
        case _       => Some(8)
      }

  private def detect(bytes: Array[Byte], size: Option[Int] = None) =
    Detector.detect(bytes, Preflight.check(bytes, size).toOption).toOption.get

  test("fewer than 8 bytes has no header to detect") {
    assertEquals(Detector.detect(Array[Byte](1, 2, 3), None), Left(SketchHeader.TooShort(3)))
  }

  test("every corpus blob is detected with high confidence and the right family") {
    CorpusFixtures.all.foreach { f =>
      val d = detect(f.bytes, itemSize(f.family, f.params("itemType").str))
      assertEquals(d.confidence.level, ConfidenceLevel.High, s"$f: ${d.confidence.evidence}")
      assert(d.family.exists(_.supported), s"$f")
      assert(d.confidence.evidence.forall(_.passed), s"$f: ${d.confidence.evidence}")
    }
  }

  test("a complete blob with trailing bytes is medium: decodable, but the length is off") {
    CorpusFixtures.all.take(40).foreach { f =>
      val b = f.bytes ++ Array.fill[Byte](5)(0)
      val d = detect(b, itemSize(f.family, f.params("itemType").str))
      assertEquals(d.confidence.level, ConfidenceLevel.Medium, s"$f")
      assertEquals(d.confidence.evidence.filterNot(_.passed).map(_.check), List("length"), s"$f")
    }
  }

  test("a truncated blob is low, says the length is wrong, and still shows its header") {
    ForgedFixtures.all.filter(_.isTruncated).foreach { c =>
      val size = itemSize(c.family, c.itemType)
      val d = Detector.detect(c.bytes, Preflight.check(c.bytes, size).toOption)
      d match {
        case Right(det) =>
          assertEquals(det.confidence.level, ConfidenceLevel.Low, s"$c")
          assert(det.confidence.evidence.exists(e => e.check == "length" && !e.passed), s"$c")
        case Left(_) => assert(c.bytes.length < SketchHeader.MinBytes, s"$c")
      }
    }
  }

  test("an unknown family is low, with the raw header and no family") {
    val d = detect(Array[Byte](3, 3, 99, 0, 0, 0, 0, 0, 1, 2, 3, 4))
    assertEquals(d.confidence.level, ConfidenceLevel.Low)
    assertEquals(d.family, None)
    assertEquals(d.header.familyId, 99)
    assert(d.header.rawPrefix.nonEmpty)
  }

  test(
    "a recognized family we do not decode is low: the header fits, nothing else can be checked"
  ) {
    val d = detect(Array[Byte](2, 1, 17, 0, 0, 0, 0, 0))
    assertEquals(d.confidence.level, ConfidenceLevel.Low)
    assertEquals(d.family.map(_.name), Some("REQ"))
  }

  test("a wrong serial version or preamble size for a known family is low") {
    val base = CorpusFixtures.family("hll").head.bytes
    val badVer = base.clone(); badVer(1) = 9
    val badPre = base.clone(); badPre(0) = 63
    Seq(badVer, badPre).foreach { b =>
      assertEquals(detect(b).confidence.level, ConfidenceLevel.Low)
    }
  }

  test("byte 0 is read in the family's own unit: Theta masks the resize factor, HLL does not") {
    val theta = CorpusFixtures.family("theta").map(_.bytes).find(b => (b(0) & 0xc0) != 0)
    theta.foreach { b =>
      val d = detect(b)
      assertEquals(d.header.preambleUnit, PreambleUnit.Longs)
      assertEquals(d.header.preambleSize, b(0) & 0x3f)
    }
    val hll = detect(CorpusFixtures.family("hll").head.bytes)
    assertEquals(hll.header.preambleUnit, PreambleUnit.Ints)
  }

  test("flags are soft evidence only: the checks are the four hard ones, none of them a flag") {
    // Writers disagree about flag bits (fixtures/README.md), so no flag may decide confidence.
    CorpusFixtures.all.foreach { f =>
      val d = detect(f.bytes, itemSize(f.family, f.params("itemType").str))
      assertEquals(
        d.confidence.evidence.map(_.check),
        List("family-id", "serial-version", "preamble-size", "length"),
        s"$f"
      )
    }
  }
}
