package sketchprobe.input

import java.nio.charset.StandardCharsets.ISO_8859_1
import sketchprobe.{VariantFixtures, VariantSet}
import sketchprobe.report.{FormatSource, InputFormat}

/** Every text form people paste turns into the sketch's bytes (spec FR-002, FR-003, User Story 2).
  * The forms are the files in fixtures/variants, plus the failures the input rules name.
  */
class NormalizerSpec extends munit.FunSuite {
  private def text(s: String): Array[Byte] = s.getBytes(ISO_8859_1)
  private val hll: VariantSet = VariantFixtures.named("hll")
  private val b64: String = java.util.Base64.getEncoder.encodeToString(hll.source)

  test(
    "every variant of every family gives the sketch's own bytes and the format the manifest names"
  ) {
    assertEquals(
      VariantFixtures.all.map(_.name),
      Vector("hll", "hll-hll4", "theta", "theta-compressed", "kll")
    )
    VariantFixtures.all.foreach { set =>
      set.variants.foreach { v =>
        Normalizer.normalize(v.bytes) match {
          case Left(d) => fail(s"${set.name}/${v.file} was rejected: ${d.code.label}: ${d.reason}")
          case Right(blob) =>
            assertEquals(blob.bytes.toSeq, set.source.toSeq, s"bytes of ${set.name}/${v.file}")
            assertEquals(blob.format.label, v.format, s"format of ${set.name}/${v.file}")
            assertEquals(blob.source, FormatSource.Detected, s"${set.name}/${v.file}")
        }
      }
    }
  }

  test("the Mongo form copied from a JSON array, with a trailing comma, is read as Mongo JSON") {
    val v = hll.variant("mongo-legacy-comma.txt")
    assert(v.text.trim.endsWith("},"), "the fixture should keep the trailing comma")
    assertEquals(Normalizer.normalize(v.bytes).map(_.format), Right(InputFormat.MongoJson))
  }

  test(
    "a Mongo document with several binary fields is several blobs, and one with none is unreadable"
  ) {
    val two =
      s"""{"a": {"$$binary": {"base64": "$b64", "subType": "00"}}, "b": {"$$binary": {"base64": "$b64", "subType": "00"}}}"""
    val none = """{"_id": 1, "name": "daily", "n": 3}"""
    assertEquals(Normalizer.normalize(text(two)).left.map(_.code.label), Left("multiple-blobs"))
    assertEquals(Normalizer.normalize(text(none)).left.map(_.code.label), Left("input-unreadable"))
  }

  test("a bare JSON string is read as one, a single-quoted string loses its quotes with a note") {
    val json = Normalizer.normalize(text("\"" + b64 + "\"")).toOption.get
    assertEquals(json.format, InputFormat.JsonString)
    val single = Normalizer.normalize(text("'" + b64 + "'")).toOption.get
    assertEquals(single.bytes.toSeq, hll.source.toSeq)
    assert(single.notes.exists(_.toLowerCase.contains("quote")), single.notes.toString)
  }

  test("base64 without padding gets a note saying padding was added") {
    val nopad = Normalizer.normalize(hll.variant("base64-nopad.txt").bytes).toOption.get
    assertEquals(nopad.bytes.toSeq, hll.source.toSeq)
    if (hll.variant("base64.txt").text.trim.endsWith("="))
      assert(nopad.notes.exists(_.toLowerCase.contains("padding")), nopad.notes.toString)
  }

  test("hex accepts upper case, a 0x prefix, a backslash-x per byte, and spaces between bytes") {
    Seq("hex-upper.txt", "hex-0x.txt", "hex-backslash-x.txt", "hex-spaced.txt").foreach { f =>
      val blob = Normalizer.normalize(hll.variant(f).bytes).toOption.getOrElse(fail(f))
      assertEquals((blob.format, blob.bytes.toSeq), (InputFormat.Hex, hll.source.toSeq), f)
    }
  }

  test("raw bytes that are not text pass through as they are") {
    val blob = Normalizer.normalize(hll.source).toOption.get
    assertEquals((blob.format, blob.bytes.toSeq), (InputFormat.Raw, hll.source.toSeq))
  }

  test("empty and whitespace-only input is empty-input") {
    assertEquals(
      Normalizer.normalize(Array.emptyByteArray).left.map(_.code.label),
      Left("empty-input")
    )
    assertEquals(Normalizer.normalize(text(" \n\t \n")).left.map(_.code.label), Left("empty-input"))
  }

  test(
    "text that is none of the formats is unreadable, with a next step, not a guess at its bytes"
  ) {
    Seq("not a sketch", "pbpaste | sketchprobe", "AAA!", "{\"x\": ").foreach { s =>
      Normalizer.normalize(text(s)) match {
        case Left(d) =>
          assertEquals(d.code.label, "input-unreadable", s)
          assert(d.nextStep.nonEmpty, s)
        case Right(b) => fail(s"'$s' was read as ${b.format.label}")
      }
    }
  }

  test("text over the size cap is rejected before decoding, and the cap can be changed") {
    val big = text(b64)
    assertEquals(
      Normalizer.normalize(big, maxBytes = 100).left.map(_.code.label),
      Left("input-too-large")
    )
    assert(Normalizer.normalize(big, maxBytes = big.length.toLong).isRight)
  }

  test("the default cap is 64 MiB") {
    assertEquals(Normalizer.DefaultMaxBytes, 64L * 1024 * 1024)
  }
}
