package sketchprobe.input

import scala.collection.mutable
import scala.util.Try
import sketchprobe.report.{Diagnosis, DiagnosisCode}

/** Finds the binary field of a Mongo Extended JSON value, in the current form
  * `{"$binary": {"base64": "...", "subType": "00"}}` and the legacy form `{"$binary": "...",
  * "$type": "00"}`, anywhere in a larger document. A trailing comma (a value copied out of a JSON
  * array) is tolerated.
  */
object MongoJson {

  private def unreadable(reason: String, next: String): Diagnosis =
    Diagnosis(DiagnosisCode.InputUnreadable, reason, Some(next))

  /** True if the text looks like a JSON object or array, so that it should be read as one. */
  def looksLikeJson(text: String): Boolean = {
    val t = text.trim
    t.startsWith("{") || t.startsWith("[")
  }

  /** The base64 text of the one binary field, or why there is not exactly one. */
  def extract(text: String): Either[Diagnosis, String] = {
    val trimmed = text.trim.stripSuffix(",").trim
    Try(ujson.read(trimmed)).toEither match {
      case Left(_) =>
        Left(
          unreadable(
            "The input looks like JSON but could not be parsed.",
            "Copy the whole value, or use --input-format to choose how to read it."
          )
        )
      case Right(json) =>
        val found = mutable.ArrayBuffer.empty[String]
        collect(json, found)
        found.toList match {
          case one :: Nil => Right(one)
          case Nil        =>
            Left(
              unreadable(
                "The JSON holds no binary field ($binary).",
                "Paste the sketch value, or the document with exactly one binary field."
              )
            )
          case many =>
            Left(
              Diagnosis(
                DiagnosisCode.MultipleBlobs,
                s"The JSON holds ${many.size} binary fields, so there are several blobs.",
                Some("Paste one binary field at a time.")
              )
            )
        }
    }
  }

  private def collect(v: ujson.Value, out: mutable.ArrayBuffer[String]): Unit = v match {
    case ujson.Obj(fields) =>
      fields.get("$binary") match {
        case Some(ujson.Str(s))                                                     => out += s
        case Some(o: ujson.Obj) if o.value.get("base64").exists(_.strOpt.isDefined) =>
          out += o.value("base64").str
        case _ => fields.values.foreach(collect(_, out))
      }
    case ujson.Arr(items) => items.foreach(collect(_, out))
    case _                => ()
  }
}
