package sketchprobe.preflight

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.decode.hll.{HllMode, RegisterWidth}
import sketchprobe.report.{Diagnoses, Diagnosis, DiagnosisCode}

/** What the header and the length of an HLL blob say. No estimate is computed here: the library
  * does that, and only after these facts have been accepted.
  *
  * The library hides the mode, the register width and the out-of-order flag (they are
  * package-private), so the report takes them from here.
  */
final case class HllFacts(
    lgK: Int,
    mode: HllMode,
    width: RegisterWidth,
    empty: Boolean,
    compactFlag: Boolean,
    outOfOrder: Boolean,
    /** LIST and SET only. */
    couponCount: Option[Int],
    /** HLL mode only. */
    curMin: Int,
    numAtCurMin: Int,
    auxCount: Int,
    length: LengthCheck
)

/** Checks an HLL blob before the library sees it: every claimed size must be backed by the bytes
  * present and every field must be in range, so a forged header cannot make the library allocate or
  * loop (FR-019, research Spike B).
  */
object HllPreflight {
  val FamilyId = 7
  val SerVer = 1
  private val MinLgK = 4
  private val MaxLgK = 21

  private val ListPreInts = 2
  private val SetPreInts = 3
  private val HllPreInts = 10

  private val ListIntArrStart = 8
  private val SetIntArrStart = 12
  private val HllByteArrStart = 40

  /** Log2 of the aux array size in ints for HLL_4 exceptions, by lgK. Only lgK 4 to 21 are used. */
  private val LgAuxArrInts: Array[Int] =
    Array(0, 2, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)

  private def notAnHll(reason: String): Diagnosis = Diagnoses.notA("HLL sketch", reason)

  private def read[A](e: Either[OutOfBounds, A]): Either[Diagnosis, A] =
    e.left.map(o => Diagnoses.readPastEnd("HLL sketch", o.message))

  private def check(ok: Boolean, d: => Diagnosis): Either[Diagnosis, Unit] =
    if (ok) Right(()) else Left(d)

  def check(bytes: Array[Byte]): Either[Diagnosis, HllFacts] = {
    val r = new ByteReader(bytes)
    for {
      _ <- check(
        bytes.length >= 8,
        Diagnoses.tooShort("HLL sketch", bytes.length, 8)
      )
      preInts <- read(r.u8(0))
      serVer <- read(r.u8(1))
      family <- read(r.u8(2))
      lgK <- read(r.u8(3))
      lgArr <- read(r.u8(4))
      flags <- read(r.u8(5))
      byte6 <- read(r.u8(6))
      modeByte <- read(r.u8(7))
      _ <- check(
        family == FamilyId && serVer == SerVer,
        notAnHll(s"Family $family version $serVer is not an HLL sketch (family 7, version 1).")
      )
      _ <- check(
        (flags & 1) == 0,
        Diagnoses.bigEndian("HLL sketch")
      )
      _ <- check(
        lgK >= MinLgK && lgK <= MaxLgK,
        notAnHll(s"lgK $lgK is outside the valid range $MinLgK to $MaxLgK.")
      )
      mode <- modeOf(modeByte & 3)
      width <- widthOf((modeByte >> 2) & 3)
      expectedPre = mode match {
        case HllMode.List => ListPreInts
        case HllMode.Set  => SetPreInts
        case HllMode.Hll  => HllPreInts
      }
      _ <- check(
        preInts == expectedPre,
        notAnHll(
          s"Preamble size $preInts ints does not fit ${mode.label} mode (expected $expectedPre)."
        )
      )
      empty = (flags & 4) != 0
      compactFlag = (flags & 8) != 0
      outOfOrder = (flags & 16) != 0
      facts <- mode match {
        case HllMode.Hll => registers(r, lgK, lgArr, byte6, width, empty, compactFlag, outOfOrder)
        case m           => coupons(r, m, lgK, lgArr, byte6, width, empty, compactFlag, outOfOrder)
      }
    } yield facts
  }

  private def modeOf(bits: Int): Either[Diagnosis, HllMode] = bits match {
    case 0 => Right(HllMode.List)
    case 1 => Right(HllMode.Set)
    case 2 => Right(HllMode.Hll)
    case _ => Left(notAnHll(s"Mode bits $bits are not LIST (0), SET (1) or HLL (2)."))
  }

  private def widthOf(bits: Int): Either[Diagnosis, RegisterWidth] = bits match {
    case 0 => Right(RegisterWidth.Hll4)
    case 1 => Right(RegisterWidth.Hll6)
    case 2 => Right(RegisterWidth.Hll8)
    case _ =>
      Left(notAnHll(s"Register width bits $bits are not HLL_4 (0), HLL_6 (1) or HLL_8 (2)."))
  }

  /** The blob must hold at least what the compact layout needs. Both layouts are computed, because
    * the writers disagree about the compact flag (fixtures/README.md, "Writer differences"), so the
    * flag cannot decide.
    */
  private def lengthCheck(
      actual: Int,
      compact: Long,
      updatable: Long
  ): Either[Diagnosis, LengthCheck] = {
    val lc = LengthCheck(actual, compact, updatable)
    if (lc.complete) Right(lc)
    else
      Left(Diagnoses.incomplete("HLL sketch", compact, actual))
  }

  /** 4 << lgArr as a Long, without overflow for corrupt lgArr values. */
  private def intsBytes(lgArr: Int): Long = if (lgArr > 26) Long.MaxValue / 2 else 4L << lgArr

  private def coupons(
      r: ByteReader,
      mode: HllMode,
      lgK: Int,
      lgArr: Int,
      byte6: Int,
      width: RegisterWidth,
      empty: Boolean,
      compactFlag: Boolean,
      outOfOrder: Boolean
  ): Either[Diagnosis, HllFacts] = {
    val isList = mode == HllMode.List
    val dataStart = if (isList) ListIntArrStart else SetIntArrStart
    for {
      count <- if (isList) Right(byte6) else read(r.i32(8))
      _ <- check(count >= 0, notAnHll(s"Coupon count $count is negative."))
      lc <- lengthCheck(r.size, dataStart + 4L * count, dataStart + intsBytes(lgArr))
    } yield HllFacts(
      lgK,
      mode,
      width,
      empty,
      compactFlag,
      outOfOrder,
      couponCount = Some(count),
      curMin = 0,
      numAtCurMin = 0,
      auxCount = 0,
      length = lc
    )
  }

  private def registerBytes(lgK: Int, width: RegisterWidth): Long = width match {
    case RegisterWidth.Hll4 => 1L << (lgK - 1)
    case RegisterWidth.Hll6 => (((1L << lgK) * 3) >>> 2) + 1
    case RegisterWidth.Hll8 => 1L << lgK
  }

  private def registers(
      r: ByteReader,
      lgK: Int,
      lgArr: Int,
      curMin: Int,
      width: RegisterWidth,
      empty: Boolean,
      compactFlag: Boolean,
      outOfOrder: Boolean
  ): Either[Diagnosis, HllFacts] =
    for {
      numAtCurMin <- read(r.i32(32))
      auxCount <- read(r.i32(36))
      _ <- check(auxCount >= 0, notAnHll(s"Aux count $auxCount is negative."))
      regs = registerBytes(lgK, width)
      // Only HLL_4 has an aux array, for values that do not fit in four bits.
      auxCompact = if (width == RegisterWidth.Hll4) 4L * auxCount else 0L
      auxUpdatable =
        if (width == RegisterWidth.Hll4) intsBytes(if (lgArr != 0) lgArr else LgAuxArrInts(lgK))
        else 0L
      lc <- lengthCheck(
        r.size,
        HllByteArrStart + regs + auxCompact,
        HllByteArrStart + regs + auxUpdatable
      )
    } yield HllFacts(
      lgK,
      HllMode.Hll,
      width,
      empty,
      compactFlag,
      outOfOrder,
      couponCount = None,
      curMin = curMin,
      numAtCurMin = numAtCurMin,
      auxCount = auxCount,
      length = lc
    )
}
