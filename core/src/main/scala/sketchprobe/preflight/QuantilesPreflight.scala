package sketchprobe.preflight

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.report.{Diagnoses, Diagnosis}

/** Which serialization a Quantiles blob is in. */
enum QuantilesForm {
  case Empty, Compact, Updatable
}

/** What the header and the length of a Quantiles blob say. Nothing is read from the retained items
  * and no quantile is computed: the library does that, and only after these facts have been
  * accepted. The items are always doubles, so unlike KLL no item type is needed.
  */
final case class QuantilesFacts private[preflight] (
    form: QuantilesForm,
    n: Long,
    k: Int,
    /** Items waiting in the base buffer: `n mod 2k`. */
    baseBufferCount: Int,
    /** One bit per full level: `n / 2k`. */
    bitPattern: Long,
    /** Levels above the base buffer that hold `k` sorted items each. */
    levelsUsed: Int,
    /** Items the sketch keeps. */
    retained: Long,
    /** Items the blob has room for: the retained items when compact, the whole working array when
      * updatable.
      */
    storedItems: Long,
    softIssues: List[String],
    actualLength: Int
) {
  def neededBytes: Long = QuantilesPreflight.PreambleBytes(form) + (storedItems * 8L)

  def length: LengthCheck = LengthCheck(actualLength, neededBytes, neededBytes)
}

/** Checks a classic Quantiles (doubles) blob before the library sees it: every claimed size must be
  * backed by the bytes present and every field must be in range (FR-019).
  *
  * Layout, verified on the corpus (fixtures/corpus/java-9.0.0/quantiles), little-endian: byte 0 is
  * the preamble size in longs (1 empty, 2 otherwise), byte 1 the serial version (3), byte 2 the
  * family (8), byte 3 the flags (2 read-only, 4 empty, 8 compact, 16 ordered), bytes 4-5 `k`, bytes
  * 8-15 `n`, 16-23 min, 24-31 max, then the items from byte 32. The compact layout keeps only
  * `n mod 2k + k * popcount(n / 2k)` items; the updatable layout keeps its whole working array,
  * which has `max(4, ceilPow2(baseBufferCount))` slots while there is no full level and
  * `(2 + levels) * k` slots after that.
  */
object QuantilesPreflight {
  val FamilyId = 8
  private val EmptyBytes = 8
  private val FullPreambleBytes = 32
  private val MinK = 2
  private val MaxK = 32768
  private val MinWorkingSlots = 4

  private val FlagEmpty = 4
  private val FlagCompact = 8

  def PreambleBytes(form: QuantilesForm): Int =
    if (form == QuantilesForm.Empty) EmptyBytes else FullPreambleBytes

  private def notAQuantiles(reason: String): Diagnosis = Diagnoses.notA("Quantiles sketch", reason)

  private def read[A](e: Either[OutOfBounds, A]): Either[Diagnosis, A] =
    e.left.map(o => Diagnoses.readPastEnd("Quantiles sketch", o.message))

  private def check(ok: Boolean, d: => Diagnosis): Either[Diagnosis, Unit] =
    if (ok) Right(()) else Left(d)

  private def isPowerOfTwo(x: Int): Boolean = x > 0 && (x & (x - 1)) == 0

  private def ceilPowerOfTwo(x: Int): Int = if (x <= 1) 1 else Integer.highestOneBit(x - 1) << 1

  def check(bytes: Array[Byte]): Either[Diagnosis, QuantilesFacts] = {
    val r = new ByteReader(bytes)
    for {
      _ <- check(
        bytes.length >= EmptyBytes,
        Diagnoses.tooShort("Quantiles sketch", bytes.length, EmptyBytes)
      )
      preLongs <- read(r.u8(0))
      serVer <- read(r.u8(1))
      family <- read(r.u8(2))
      flags <- read(r.u8(3))
      k <- read(r.u16(4))
      _ <- check(
        family == FamilyId,
        notAQuantiles(s"Family $family is not a Quantiles sketch (family 8).")
      )
      _ <- check(
        serVer == 3,
        if (serVer == 1 || serVer == 2) Diagnoses.legacyVersion("Quantiles sketch", serVer, "3")
        else notAQuantiles(s"Version $serVer is not a Quantiles layout we know.")
      )
      empty = (flags & FlagEmpty) != 0
      compact = (flags & FlagCompact) != 0
      _ <- check(
        preLongs == (if (empty) 1 else 2),
        notAQuantiles(
          s"Preamble size $preLongs longs does not fit a ${
              if (empty) "empty" else "non-empty"
            } Quantiles sketch."
        )
      )
      _ <- check(
        k >= MinK && k <= MaxK && isPowerOfTwo(k),
        notAQuantiles(s"k $k is not a power of two from $MinK to $MaxK.")
      )
      facts <- if (empty) Right(emptyFacts(bytes.length, k)) else full(r, k, compact)
      _ <- check(
        bytes.length >= facts.neededBytes,
        Diagnoses.incomplete("Quantiles sketch", facts.neededBytes, bytes.length)
      )
    } yield facts
  }

  private def emptyFacts(actual: Int, k: Int): QuantilesFacts =
    QuantilesFacts(QuantilesForm.Empty, 0L, k, 0, 0L, 0, 0L, 0L, Nil, actual)

  private def full(r: ByteReader, k: Int, compact: Boolean): Either[Diagnosis, QuantilesFacts] =
    for {
      n <- read(r.i64(8))
      min <- read(r.f64(16))
      max <- read(r.f64(24))
      _ <- check(n >= 1, notAQuantiles(s"n $n is not positive for a non-empty sketch."))
    } yield {
      val twoK = 2L * k
      val baseBuffer = (n % twoK).toInt
      val bitPattern = n / twoK
      val levelsUsed = java.lang.Long.bitCount(bitPattern)
      val retained = baseBuffer.toLong + (k.toLong * levelsUsed)
      // The highest set bit of the bit pattern decides how many levels the working array reserves.
      val levelsNeeded = 64 - java.lang.Long.numberOfLeadingZeros(bitPattern)
      val working =
        if (levelsNeeded == 0) math.max(MinWorkingSlots, ceilPowerOfTwo(baseBuffer)).toLong
        else (2L + levelsNeeded) * k
      val issues =
        if (min.isNaN || max.isNaN) List("min or max is not a number")
        else if (min > max) List(s"min $min is above max $max")
        else Nil
      QuantilesFacts(
        if (compact) QuantilesForm.Compact else QuantilesForm.Updatable,
        n,
        k,
        baseBuffer,
        bitPattern,
        levelsUsed,
        retained,
        if (compact) retained else working,
        issues,
        r.size
      )
    }
}
