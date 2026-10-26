package sketchprobe.detect

import sketchprobe.CorpusFixtures
import sketchprobe.report.PreambleUnit

class SketchHeaderSpec extends munit.FunSuite {
  test("fewer than 8 bytes is too short") {
    assertEquals(SketchHeader.parse(Array.emptyByteArray), Left(SketchHeader.TooShort(0)))
    assertEquals(SketchHeader.parse(Array.fill(7)(1.toByte)), Left(SketchHeader.TooShort(7)))
    assert(SketchHeader.parse(Array.fill(8)(1.toByte)).isRight)
  }

  test("Theta masks the resize factor out of byte 0") {
    // byte 0 = 0xC3: resize factor 3 in the top two bits, 3 preamble longs below
    val bytes = Array[Byte](0xc3.toByte, 3, 2, 10, 10, 0, 0, 0)
    val h = SketchHeader.parse(bytes).toOption.get
    assertEquals(h.byte0, 195)
    assertEquals(h.preambleSize, 3)
    assertEquals(h.resizeFactor, Some(3))
    assertEquals(h.preambleUnit, PreambleUnit.Longs)
  }

  test("HLL and KLL keep byte 0 whole, in ints") {
    val hll = SketchHeader.parse(Array[Byte](10, 1, 7, 12, 0, 0, 0, 2)).toOption.get
    assertEquals(
      (hll.preambleSize, hll.preambleUnit, hll.resizeFactor),
      (10, PreambleUnit.Ints, None)
    )
    val kll = SketchHeader.parse(Array[Byte](5, 1, 15, 0, 0, 0, 0, 0)).toOption.get
    assertEquals((kll.preambleSize, kll.preambleUnit), (5, PreambleUnit.Ints))
  }

  test("an unknown family still parses, with an unknown unit, so the raw header can be shown") {
    val h = SketchHeader.parse(Array[Byte](9, 9, 99, 0, 0, 0, 0, 0)).toOption.get
    assertEquals((h.familyId, h.preambleUnit), (99, PreambleUnit.Unknown))
  }

  test("the raw prefix is at most 24 bytes, as lowercase hex") {
    val h = SketchHeader.parse(Array.tabulate(40)(_.toByte)).toOption.get
    assertEquals(h.rawPrefix.length, 24)
    assertEquals(h.rawPrefixHex.take(8), "00010203")
  }

  test("the header agrees with the manifest of every blob in the corpus") {
    CorpusFixtures.all.foreach { f =>
      val h = SketchHeader.parse(f.bytes).toOption.get
      assertEquals(h.byte0, f.header("byte0").num.toInt, s"byte0 of $f")
      assertEquals(h.serVer, f.header("serVer").num.toInt, s"serVer of $f")
      assertEquals(h.familyId, f.header("familyId").num.toInt, s"familyId of $f")
      assertEquals(h.rawPrefixHex, f.header("firstBytesHex").str, s"prefix of $f")
    }
  }

  test("no input makes the parser throw (random and truncated bytes)") {
    val rnd = new scala.util.Random(42)
    (0 until 2000).foreach { _ =>
      val b = new Array[Byte](rnd.nextInt(64))
      rnd.nextBytes(b)
      SketchHeader.parse(b) // must return, never throw
    }
  }
}
