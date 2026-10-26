package sketchprobe.input

import scala.util.Try
import sketchprobe.detect.Detector
import sketchprobe.report.{Diagnosis, DiagnosisCode, FormatSource, InputAlternative, InputFormat}

/** Turns whatever the user supplied into the sketch's bytes and says which format it was read as.
  *
  * The rules (spec FR-001 to FR-006, data-model InputBlob):
  *   - exactly one blob; several lines are several blobs only if each line is a sketch on its own,
  *     otherwise line breaks are wrapping inside one blob
  *   - text over the size cap is rejected before it is decoded
  *   - text that is valid under more than one format keeps the reading that identifies as a sketch;
  *     if both or neither do, it is ambiguous and the user is told to choose
  *   - input with non-text bytes is the sketch itself (raw)
  */
object Normalizer {

  /** The reason given when the input is none of the formats; the CLI adds a hint to it. */
  val OrdinaryText =
    "The input is not hex, base64, Mongo JSON or raw sketch bytes; it looks like ordinary text."

  val DefaultMaxBytes: Long = 64L * 1024 * 1024

  private def fail(code: DiagnosisCode, reason: String, next: String): Left[Diagnosis, Nothing] =
    Left(Diagnosis(code, reason, Some(next)))

  /** The decoded bytes carry a header that fits a known sketch family: its family ID, serial
    * version and preamble size all agree.
    */
  private def identifies(bytes: Array[Byte]): Boolean =
    Detector
      .detect(bytes, None)
      .exists(_.confidence.evidence.take(3).forall(_.passed))

  private def isText(raw: Array[Byte]): Boolean =
    raw.forall { b =>
      val c = b & 0xff
      (c >= 0x20 && c < 0x7f) || c == '\t' || c == '\n' || c == '\r'
    }

  def normalize(
      raw: Array[Byte],
      forced: Option[InputFormat] = None,
      maxBytes: Long = DefaultMaxBytes
  ): Either[Diagnosis, InputBlob] =
    if (raw.length > maxBytes)
      fail(
        DiagnosisCode.InputTooLarge,
        s"The input is larger than the limit of $maxBytes bytes.",
        "A sketch is usually kilobytes. Raise the limit with --max-input-bytes if this is intended."
      )
    else
      forced match {
        case Some(format) => forcedRead(raw, format)
        case None         => autoRead(raw)
      }

  // ---- forced

  private def forcedRead(raw: Array[Byte], format: InputFormat): Either[Diagnosis, InputBlob] = {
    val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)
    def cannot(what: String): Left[Diagnosis, Nothing] =
      fail(
        DiagnosisCode.InputUnreadable,
        s"The input cannot be read as $what.",
        "Check the format, or use --input-format auto to detect it."
      )
    def done(bytes: Array[Byte], notes: List[String]) =
      Right(InputBlob(bytes, format, FormatSource.Forced, Nil, notes))
    format match {
      case InputFormat.Raw if raw.isEmpty =>
        fail(
          DiagnosisCode.EmptyInput,
          "The input is empty.",
          "Paste the sketch, pipe it in, give it as the argument, or name a file with --file."
        )
      case InputFormat.Raw => done(raw, Nil)
      case InputFormat.Hex =>
        TextEncodings.hex(text).fold(cannot("hex"))(d => done(d.bytes, d.notes))
      case InputFormat.Base64 | InputFormat.Base64Url =>
        TextEncodings
          .base64(unquote(text)._1)
          .fold(cannot(format.label))(d => done(d.bytes, d.notes))
      case InputFormat.MongoJson =>
        if (MongoJson.looksLikeJson(text))
          MongoJson.extract(text).flatMap { b64 =>
            TextEncodings
              .base64(b64)
              .fold(cannot("Mongo JSON (the binary field is not base64)"))(d =>
                done(d.bytes, d.notes)
              )
          }
        else cannot("Mongo JSON")
      case _ => cannot(format.label)
    }
  }

  // ---- automatic

  /** Text without surrounding single quotes, and a note when they were removed. */
  private def unquote(text: String): (String, List[String]) = {
    val t = text.trim
    if (t.length >= 2 && t.startsWith("'") && t.endsWith("'"))
      (t.substring(1, t.length - 1), List("stripped the quotes"))
    else (t, Nil)
  }

  private def autoRead(raw: Array[Byte]): Either[Diagnosis, InputBlob] =
    if (raw.forall(b => " \t\r\n".indexOf(b.toChar) >= 0)) {
      fail(
        DiagnosisCode.EmptyInput,
        "The input is empty.",
        "Paste the sketch, pipe it in, give it as the argument, or name a file with --file."
      )
    } else if (!isText(raw)) {
      Right(InputBlob(raw, InputFormat.Raw, FormatSource.Detected))
    } else {
      val text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).trim
      if (MongoJson.looksLikeJson(text)) readMongo(text)
      else if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2)
        readJsonString(text)
      else readPlain(text)
    }

  private def readMongo(text: String): Either[Diagnosis, InputBlob] =
    MongoJson.extract(text).flatMap { b64 =>
      TextEncodings.base64(b64) match {
        case Some(d) =>
          Right(InputBlob(d.bytes, InputFormat.MongoJson, FormatSource.Detected, Nil, d.notes))
        case None =>
          fail(
            DiagnosisCode.InputUnreadable,
            "The binary field in the JSON is not valid base64.",
            "Copy the whole value."
          )
      }
    }

  private def readJsonString(text: String): Either[Diagnosis, InputBlob] =
    Try(ujson.read(text)).toOption.flatMap(_.strOpt) match {
      case None =>
        fail(
          DiagnosisCode.InputUnreadable,
          "The quoted text is not a valid JSON string.",
          "Copy the whole value, or use --input-format."
        )
      case Some(inner) =>
        readPlain(inner).map(_.copy(format = InputFormat.JsonString))
    }

  private final case class Reading(format: InputFormat, decoded: TextEncodings.Decoded) {
    def identified: Boolean = identifies(decoded.bytes)
  }

  private def readings(text: String): List[Reading] = {
    val hex = TextEncodings.hex(text).map(Reading(InputFormat.Hex, _))
    val b64 = TextEncodings.base64(text).map { d =>
      Reading(
        if (TextEncodings.usesUrlAlphabet(text)) InputFormat.Base64Url else InputFormat.Base64,
        d
      )
    }
    hex.toList ++ b64.toList
  }

  private def readPlain(text: String): Either[Diagnosis, InputBlob] = {
    val (unquoted, quoteNotes) = unquote(text)
    val lines = unquoted.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    if (lines.size > 1 && lines.forall(l => readings(l).exists(_.identified)))
      fail(
        DiagnosisCode.MultipleBlobs,
        s"The input holds ${lines.size} lines that are each a sketch, so there are several blobs.",
        "Pass one sketch at a time."
      )
    else
      readings(unquoted) match {
        case Nil =>
          fail(
            DiagnosisCode.InputUnreadable,
            OrdinaryText,
            "Copy the sketch value itself, or use --input-format to say how to read it."
          )
        case one :: Nil =>
          Right(
            InputBlob(
              one.decoded.bytes,
              one.format,
              FormatSource.Detected,
              Nil,
              quoteNotes ++ one.decoded.notes
            )
          )
        case many =>
          many.filter(_.identified) match {
            case winner :: Nil =>
              val others =
                many.filterNot(_ eq winner).map(r => InputAlternative(r.format, identified = false))
              Right(
                InputBlob(
                  winner.decoded.bytes,
                  winner.format,
                  FormatSource.Detected,
                  others,
                  quoteNotes ++ winner.decoded.notes
                )
              )
            case _ =>
              fail(
                DiagnosisCode.InputAmbiguous,
                s"The text is valid as ${many.map(_.format.label).mkString(" and ")}, and ${
                    if (many.exists(_.identified)) "both" else "neither"
                  } reading is a recognizable sketch.",
                "Choose the format with --input-format hex or --input-format base64."
              )
          }
      }
  }
}
