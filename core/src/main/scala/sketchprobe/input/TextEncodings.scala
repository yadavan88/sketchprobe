package sketchprobe.input

import java.util.Base64

/** Turns text into bytes under one encoding, or says it is not that encoding. Nothing here guesses:
  * the caller decides which reading to keep.
  */
object TextEncodings {

  /** Bytes plus anything worth telling the user about what was stripped or added. */
  final case class Decoded(bytes: Array[Byte], notes: List[String])

  private val HexDigits = "[0-9a-fA-F]+".r
  private val BackslashHex = raw"(\\x[0-9a-fA-F]{2})+".r

  /** Hex, upper or lower case, with an optional `0x` prefix, a `\x` before every byte, and white
    * space anywhere (a space between bytes, or line breaks).
    */
  def hex(text: String): Option[Decoded] = {
    val compact = text.filterNot(_.isWhitespace)
    val (body, notes) =
      if (compact.startsWith("0x") || compact.startsWith("0X"))
        (compact.drop(2), List("stripped the 0x prefix"))
      else if (BackslashHex.matches(compact))
        (compact.replace("\\x", ""), List("stripped the \\x before each byte"))
      else (compact, Nil)
    if (body.nonEmpty && body.length % 2 == 0 && HexDigits.matches(body))
      Some(Decoded(body.grouped(2).map(java.lang.Integer.parseInt(_, 16).toByte).toArray, notes))
    else None
  }

  /** Base64 in the standard or the URL-safe alphabet, with or without padding. Line breaks are
    * wrapping and are removed; a space inside a line is not base64.
    */
  def base64(text: String): Option[Decoded] = {
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    val body = lines.mkString
    val urlChars = body.exists(c => c == '-' || c == '_')
    val stdChars = body.exists(c => c == '+' || c == '/')
    val valid =
      body.nonEmpty && body.forall(c => c.isLetterOrDigit && c < 128 || "+/-_=".indexOf(c) >= 0)
    if (!valid || (urlChars && stdChars)) None
    else {
      val unpadded = body.stripSuffix("==").stripSuffix("=")
      val notes =
        (if (lines.size > 1) List("joined the wrapped lines") else Nil) ++
          (if (body.length % 4 != 0 && !body.endsWith("=")) List("padding added") else Nil)
      val decoder = if (urlChars) Base64.getUrlDecoder else Base64.getDecoder
      try Some(Decoded(decoder.decode(unpadded), notes))
      catch { case _: IllegalArgumentException => None }
    }
  }

  /** The base64 alphabet in use, for naming the format. */
  def usesUrlAlphabet(text: String): Boolean = text.exists(c => c == '-' || c == '_')
}
