package sketchprobe.detect

import sketchprobe.decode.ByteReader
import sketchprobe.report.{HeaderInfo, PreambleUnit}

/** The three bytes every family shares: preamble size (byte 0), serial version (byte 1), family ID
  * (byte 2).
  */
object SketchHeader {

  /** The smallest possible serialized sketch (an empty one) is 8 bytes. */
  val MinBytes: Int = 8

  /** How many leading bytes the raw-header display shows. */
  val PrefixBytes: Int = 24

  final case class TooShort(length: Int)

  def parse(bytes: Array[Byte]): Either[TooShort, HeaderInfo] =
    if bytes.length < MinBytes then Left(TooShort(bytes.length))
    else {
      val r = new ByteReader(bytes)
      val byte0 = bytes(0) & 0xff
      val serVer = bytes(1) & 0xff
      val familyId = bytes(2) & 0xff
      val family = Family.byId(familyId)
      val theta = Family.isThetaFamily(familyId)
      val unit = family.map(_.unit).getOrElse(PreambleUnit.Unknown)
      // Theta stores the resize factor in the top two bits of byte 0, so mask them off.
      val size = if theta then byte0 & 0x3f else byte0
      val resize = if theta then Some(byte0 >> 6) else None
      Right(HeaderInfo(byte0, size, unit, resize, serVer, familyId, r.prefix(PrefixBytes)))
    }
}
