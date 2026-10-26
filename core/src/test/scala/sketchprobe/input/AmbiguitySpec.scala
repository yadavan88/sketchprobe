package sketchprobe.input

import java.nio.charset.StandardCharsets.ISO_8859_1
import sketchprobe.{VariantFixtures, VariantSet}
import sketchprobe.report.{FormatSource, InputAlternative, InputFormat}

/** Text that reads as more than one format, a format the user forces, and the several-blobs rule
  * (spec FR-004, FR-005, data-model InputBlob).
  */
class AmbiguitySpec extends munit.FunSuite {
  private def text(s: String): Array[Byte] = s.getBytes(ISO_8859_1)
  private val hll: VariantSet = VariantFixtures.named("hll")
  private val theta: VariantSet = VariantFixtures.named("theta")
  private def b64(set: VariantSet): String = set.variant("base64.txt").text.trim

  // 16 characters, valid as hex and as base64, and neither reading is a sketch.
  private val both = "deadbeefdeadbeef"

  test(
    "hex text is also valid base64; the reading that identifies as a sketch wins, and the other is listed"
  ) {
    val hex = hll.variant("hex.txt").bytes
    val blob = Normalizer.normalize(hex).toOption.get
    assertEquals(blob.format, InputFormat.Hex)
    assertEquals(blob.bytes.toSeq, hll.source.toSeq)
    assert(
      blob.alternatives.contains(InputAlternative(InputFormat.Base64, identified = false)),
      blob.alternatives.toString
    )
  }

  test(
    "when both readings are valid and neither identifies, it is ambiguous and the message names --input-format"
  ) {
    Normalizer.normalize(text(both)) match {
      case Left(d) =>
        assertEquals(d.code.label, "input-ambiguous")
        assert((d.reason + d.nextStep.getOrElse("")).contains("--input-format"), d.toString)
      case Right(b) => fail(s"read as ${b.format.label}")
    }
  }

  test(
    "forcing a format decodes under it, whatever the other readings say, and records that it was forced"
  ) {
    val hex = Normalizer.normalize(text(both), Some(InputFormat.Hex)).toOption.get
    assertEquals((hex.format, hex.source), (InputFormat.Hex, FormatSource.Forced))
    assertEquals(hex.bytes.toSeq, Seq.fill(2)(Seq(0xde, 0xad, 0xbe, 0xef).map(_.toByte)).flatten)
    val b64 = Normalizer.normalize(text(both), Some(InputFormat.Base64)).toOption.get
    assertEquals(
      (b64.format, b64.source, b64.bytes.length),
      (InputFormat.Base64, FormatSource.Forced, 12)
    )
    val raw = Normalizer.normalize(text(both), Some(InputFormat.Raw)).toOption.get
    assertEquals((raw.format, raw.bytes.toSeq), (InputFormat.Raw, text(both).toSeq))
  }

  test("forcing a format the text cannot be read as is unreadable, not a silent fallback") {
    assertEquals(
      Normalizer.normalize(text("xyz!"), Some(InputFormat.Hex)).left.map(_.code.label),
      Left("input-unreadable")
    )
    assertEquals(
      Normalizer.normalize(text(both), Some(InputFormat.MongoJson)).left.map(_.code.label),
      Left("input-unreadable")
    )
  }

  test("forced Mongo and forced hex read what automatic detection read") {
    val mongo = hll.variant("mongo-current.json").bytes
    assertEquals(
      Normalizer.normalize(mongo, Some(InputFormat.MongoJson)).map(_.bytes.toSeq),
      Right(hll.source.toSeq)
    )
    val hex = hll.variant("hex-0x.txt").bytes
    assertEquals(
      Normalizer.normalize(hex, Some(InputFormat.Hex)).map(_.bytes.toSeq),
      Right(hll.source.toSeq)
    )
  }

  test("several lines that each identify as a sketch are several blobs") {
    val two = b64(hll) + "\n" + b64(theta) + "\n"
    assertEquals(Normalizer.normalize(text(two)).left.map(_.code.label), Left("multiple-blobs"))
    val same = b64(hll) + "\n" + b64(hll) + "\n"
    assertEquals(Normalizer.normalize(text(same)).left.map(_.code.label), Left("multiple-blobs"))
  }

  test("a line-wrapped base64 blob is one blob, because its lines do not identify on their own") {
    val wrapped = hll.variant("base64-multiline.txt")
    assert(
      wrapped.text.trim.linesIterator.size > 1,
      "the fixture should be wrapped over several lines"
    )
    val blob = Normalizer.normalize(wrapped.bytes).toOption.get
    assertEquals(blob.bytes.toSeq, hll.source.toSeq)
  }

  test("a sketch line mixed with text that is not a sketch is unreadable, not several blobs") {
    val mix = b64(hll) + "\nhello world!\n"
    assertEquals(Normalizer.normalize(text(mix)).left.map(_.code.label), Left("input-unreadable"))
  }
}
