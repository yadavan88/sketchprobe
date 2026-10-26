package sketchprobe.preflight

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.decode.theta.ThetaKind
import sketchprobe.report.{Diagnoses, Diagnosis, DiagnosisCode}

/** What the header and the length of a Theta blob say. No estimate, bound or hash is computed here:
  * the library does that, and only after these facts have been accepted.
  */
final case class ThetaFacts(
    kind: ThetaKind,
    serVer: Int,
    preambleLongs: Int,
    /** empty, single item, exact, estimating, update sketch, state, or compressed, exact /
      * estimating.
      */
    shape: String,
    empty: Boolean,
    ordered: Boolean,
    compactFlag: Boolean,
    lgNomLongs: Option[Int],
    lgArrLongs: Option[Int],
    thetaLong: Long,
    /** Union only: the second theta, below which the result is cut. */
    unionThetaLong: Option[Long],
    /** The entry count the header claims. For a Union this is the gadget's count, not the result's.
      */
    retained: Int,
    seedHash: Int,
    length: LengthCheck
)

/** Checks a Theta blob (families 1 to 5, serial versions 3 and 4) before the library sees it: every
  * claimed size must be backed by the bytes present and every field must be in range (FR-019,
  * research Spike B). The rules were ported from the own-decoder layout code and are checked
  * against the corpus and the forged set.
  */
object ThetaPreflight {
  val SerVer = 3
  private val SerVerCompressed = 4
  private val MaxLgArr = 26

  private def notATheta(reason: String): Diagnosis = Diagnoses.notA("Theta sketch", reason)

  private def read[A](e: Either[OutOfBounds, A]): Either[Diagnosis, A] =
    e.left.map(o => Diagnoses.readPastEnd("Theta sketch", o.message))

  private def check(ok: Boolean, d: => Diagnosis): Either[Diagnosis, Unit] =
    if (ok) Right(()) else Left(d)

  private def incomplete(needed: Long, actual: Int): Diagnosis =
    Diagnoses.incomplete("Theta sketch", needed, actual)

  private def lengthCheck(actual: Int, needed: Long): Either[Diagnosis, LengthCheck] = {
    val lc = LengthCheck(actual, needed, needed)
    if (lc.complete) Right(lc) else Left(incomplete(needed, actual))
  }

  private def kindOf(familyId: Int): Either[Diagnosis, ThetaKind] =
    ThetaKind.values.find(_.familyId == familyId).toRight {
      if (familyId == 6) {
        Diagnosis(
          DiagnosisCode.UnsupportedFamily,
          "AnotB (family 6) is recognized, but the library never serializes it, so there is nothing to decode.",
          None
        )
      } else notATheta(s"Family $familyId is not a Theta family (1 to 5).")
    }

  /** Hash table size in bytes for `lgArr`, without overflow for corrupt values. */
  private def tableBytes(lgArr: Int): Long =
    if (lgArr > MaxLgArr) Long.MaxValue / 2 else 8L << lgArr

  private def validTheta(thetaLong: Long): Either[Diagnosis, Unit] =
    check(thetaLong > 0, notATheta(s"Theta $thetaLong is not positive."))

  def check(bytes: Array[Byte]): Either[Diagnosis, ThetaFacts] = {
    val r = new ByteReader(bytes)
    for {
      _ <- check(
        bytes.length >= 8,
        Diagnoses.tooShort("Theta sketch", bytes.length, 8)
      )
      byte0 <- read(r.u8(0))
      serVer <- read(r.u8(1))
      familyId <- read(r.u8(2))
      lgNom <- read(r.u8(3))
      lgArr <- read(r.u8(4))
      flags <- read(r.u8(5))
      seedHash <- read(r.u16(6))
      kind <- kindOf(familyId)
      _ <- check(
        serVer == SerVer || (serVer == SerVerCompressed && kind == ThetaKind.Compact),
        if (serVer == 1 || serVer == 2) {
          Diagnoses.legacyVersion("Theta sketch", serVer, "3 or 4")
        } else notATheta(s"Serial version $serVer is not valid for ${kind.label} (expected 3).")
      )
      _ <- check(
        (flags & 1) == 0,
        Diagnoses.bigEndian("Theta sketch")
      )
      pre = byte0 & 0x3f // the top two bits are the resize factor
      empty = (flags & 4) != 0
      ordered = (flags & 16) != 0
      compactFlag = (flags & 8) != 0
      facts <-
        if (serVer == SerVerCompressed) compressed(r, pre, seedHash)
        else {
          kind match {
            case ThetaKind.Compact => compact(r, pre, empty, ordered, compactFlag, seedHash)
            case ThetaKind.Union   =>
              union(r, pre, lgNom, lgArr, empty, ordered, compactFlag, seedHash)
            case ThetaKind.Intersection =>
              intersection(r, pre, lgArr, empty, ordered, compactFlag, seedHash)
            case k => update(r, k, pre, lgNom, lgArr, empty, ordered, compactFlag, seedHash)
          }
        }
    } yield facts
  }

  // ---- Compact (family 3), serial version 3

  private def compact(
      r: ByteReader,
      pre: Int,
      empty: Boolean,
      ordered: Boolean,
      compactFlag: Boolean,
      seedHash: Int
  ): Either[Diagnosis, ThetaFacts] =
    for {
      _ <- check(
        pre >= 1 && pre <= 3,
        notATheta(s"Preamble size $pre longs is not valid for a compact sketch (1 to 3).")
      )
      // The single-item flag is not reliable (C++ does not set it): a non-empty sketch with one preamble long is a single item.
      count <-
        if (empty) Right(0)
        else if (pre == 1) Right(1)
        else read(r.i32(8))
      _ <- check(count >= 0, notATheta(s"Retained entry count $count is negative."))
      thetaLong <- if (!empty && pre == 3) read(r.i64(16)) else Right(Long.MaxValue)
      _ <- validTheta(thetaLong)
      lc <- lengthCheck(r.size, (8L * pre) + (8L * count))
    } yield {
      val shape =
        if (empty) "empty"
        else if (pre == 1) "single item"
        else if (pre == 2) "exact"
        else "estimating"
      ThetaFacts(
        ThetaKind.Compact,
        SerVer,
        pre,
        shape,
        empty,
        ordered,
        compactFlag,
        None,
        None,
        thetaLong,
        None,
        count,
        seedHash,
        lc
      )
    }

  // ---- Update sketches (families 1 and 2), serial version 3

  private def update(
      r: ByteReader,
      kind: ThetaKind,
      pre: Int,
      lgNom: Int,
      lgArr: Int,
      empty: Boolean,
      ordered: Boolean,
      compactFlag: Boolean,
      seedHash: Int
  ): Either[Diagnosis, ThetaFacts] =
    for {
      _ <- check(
        pre == 3,
        notATheta(s"Preamble size $pre longs is not valid for a ${kind.label} sketch (expected 3).")
      )
      count <- read(r.i32(8))
      thetaLong <- read(r.i64(16))
      _ <- check(count >= 0, notATheta(s"Retained entry count $count is negative."))
      _ <- check(
        lgArr <= MaxLgArr && lgNom <= MaxLgArr,
        notATheta(s"lgNomLongs $lgNom or lgArrLongs $lgArr is out of range.")
      )
      _ <- validTheta(thetaLong)
      lc <- lengthCheck(r.size, 24L + tableBytes(lgArr))
    } yield ThetaFacts(
      kind,
      SerVer,
      pre,
      "update sketch",
      empty,
      ordered,
      compactFlag,
      Some(lgNom),
      Some(lgArr),
      thetaLong,
      None,
      count,
      seedHash,
      lc
    )

  // ---- Union (family 4), serial version 3

  private def union(
      r: ByteReader,
      pre: Int,
      lgNom: Int,
      lgArr: Int,
      empty: Boolean,
      ordered: Boolean,
      compactFlag: Boolean,
      seedHash: Int
  ): Either[Diagnosis, ThetaFacts] =
    for {
      _ <- check(
        pre == 4,
        notATheta(s"Preamble size $pre longs is not valid for a Union (expected 4).")
      )
      count <- read(r.i32(8))
      gadgetTheta <- read(r.i64(16))
      unionTheta <- read(r.i64(24))
      _ <- check(count >= 0, notATheta(s"Retained entry count $count is negative."))
      _ <- check(
        lgArr <= MaxLgArr && lgNom <= MaxLgArr,
        notATheta(s"lgNomLongs $lgNom or lgArrLongs $lgArr is out of range.")
      )
      _ <- validTheta(gadgetTheta)
      _ <- validTheta(unionTheta)
      lc <- lengthCheck(r.size, 32L + tableBytes(lgArr))
    } yield ThetaFacts(
      ThetaKind.Union,
      SerVer,
      pre,
      "state",
      empty,
      ordered,
      compactFlag,
      Some(lgNom),
      Some(lgArr),
      gadgetTheta,
      Some(unionTheta),
      count,
      seedHash,
      lc
    )

  // ---- Intersection (family 5), serial version 3

  private def intersection(
      r: ByteReader,
      pre: Int,
      lgArr: Int,
      empty: Boolean,
      ordered: Boolean,
      compactFlag: Boolean,
      seedHash: Int
  ): Either[Diagnosis, ThetaFacts] =
    for {
      _ <- check(
        pre == 3,
        notATheta(s"Preamble size $pre longs is not valid for an Intersection (expected 3).")
      )
      count <- read(r.i32(8))
      thetaLong <- read(r.i64(16))
      _ <- check(
        count >= 0,
        Diagnosis(
          DiagnosisCode.UnsupportedLayout,
          "This intersection holds the universal set (no sketch has been intersected yet), so there is no result to report.",
          None
        )
      )
      _ <- check(lgArr <= MaxLgArr, notATheta(s"lgArrLongs $lgArr is out of range."))
      _ <- validTheta(thetaLong)
      lc <- lengthCheck(r.size, 24L + (if (count > 0) tableBytes(lgArr) else 0L))
    } yield ThetaFacts(
      ThetaKind.Intersection,
      SerVer,
      pre,
      "state",
      empty,
      ordered,
      compactFlag,
      None,
      Some(lgArr),
      thetaLong,
      None,
      count,
      seedHash,
      lc
    )

  // ---- Compressed compact (family 3), serial version 4
  // Layout verified from the datasketches-java 9.0.0 writer (research.md, R2): preamble 1 (exact) or 2 (estimating),
  // entryBits in byte 3, numEntriesBytes in byte 4, theta at byte 8 when estimating, then the count, then the packed deltas.

  private def compressed(r: ByteReader, pre: Int, seedHash: Int): Either[Diagnosis, ThetaFacts] = {
    val bytes = r.bytes
    if (pre != 1 && pre != 2) {
      Left(notATheta(s"Preamble size $pre longs is not valid for a compressed sketch (1 or 2)."))
    } else {
      val entryBits = bytes(3) & 0xff
      val numEntriesBytes = bytes(4) & 0xff
      val estimating = pre == 2
      if (entryBits < 1 || entryBits > 64) {
        Left(notATheta(s"entryBits $entryBits is outside the valid range 1 to 64."))
      } else if (numEntriesBytes < 1 || numEntriesBytes > 4) {
        Left(
          notATheta(
            s"The entry count size $numEntriesBytes bytes is outside the valid range 1 to 4."
          )
        )
      } else {
        val countStart = if (estimating) 16 else 8
        val dataStart = countStart + numEntriesBytes
        if (bytes.length < dataStart) {
          Left(incomplete(dataStart.toLong, bytes.length))
        } else {
          val thetaLong = if (estimating) r.i64(8).getOrElse(0L) else Long.MaxValue
          val count = readCount(bytes, countStart, numEntriesBytes)
          val needed = dataStart + ((count.toLong * entryBits + 7) >>> 3)
          if (thetaLong <= 0) Left(notATheta(s"Theta $thetaLong is not positive."))
          else if (count < 0) Left(notATheta(s"Retained entry count is out of range."))
          else if (bytes.length < needed) Left(incomplete(needed, bytes.length))
          else {
            Right(
              ThetaFacts(
                ThetaKind.Compact,
                SerVerCompressed,
                pre,
                if (estimating) "compressed, estimating" else "compressed, exact",
                empty = false,
                ordered = true,
                compactFlag = true,
                None,
                None,
                thetaLong,
                None,
                count,
                seedHash,
                LengthCheck(r.size, needed, needed)
              )
            )
          }
        }
      }
    }
  }

  /** A little-endian unsigned integer of 1 to 4 bytes. Returns -1 if it does not fit in an Int. */
  private def readCount(bytes: Array[Byte], start: Int, n: Int): Int = {
    var v = 0L
    var i = 0
    while (i < n) {
      v |= (bytes(start + i) & 0xffL) << (8 * i)
      i += 1
    }
    if (v > Int.MaxValue) -1 else v.toInt
  }
}
