package sketchprobe.preflight

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.report.{Diagnoses, Diagnosis, DiagnosisCode}

/** Which serialization a KLL blob is in. */
enum KllLayoutKind {
  case Compact, Updatable
}

/** The four shapes a KLL blob can take. */
enum KllForm {
  case CompactEmpty, CompactSingle, CompactFull, UpdatableFull
}

/** What the header and the length of a KLL blob say. Nothing is read from the items and no quantile
  * is computed: the library does that, and only after these facts have been accepted.
  *
  * A KLL blob does not record whether its items are float, double or long, so the size of one item
  * (4 or 8 bytes) is an input to every length rule here.
  */
final case class KllFacts private[preflight] (
    form: KllForm,
    n: Long,
    k: Int,
    m: Int,
    minK: Int,
    numLevels: Int,
    /** The level boundaries, `numLevels + 1` of them, the last being the total capacity. Empty for
      * an empty or single-item blob.
      */
    levels: Vector[Int],
    totalCapacity: Int,
    retained: Int,
    /** Bytes before the min and max items. */
    dataStart: Int,
    /** The retained items' weights add up to n, as they must in a valid sketch. */
    weightsConsistent: Boolean,
    /** Inconsistencies that do not stop a blob from decoding, such as `minK` above k. */
    softIssues: List[String],
    actualLength: Int
) {
  def kind: KllLayoutKind =
    if (form == KllForm.UpdatableFull) KllLayoutKind.Updatable else KllLayoutKind.Compact

  /** Bytes the layout needs when one item takes `itemSize` bytes. */
  def neededBytes(itemSize: Int): Long = form match {
    case KllForm.CompactEmpty  => 8L
    case KllForm.CompactSingle => 8L + itemSize
    case KllForm.CompactFull   => dataStart + (2L * itemSize) + (retained.toLong * itemSize)
    // The updatable layout stores the whole item array, whatever is in use.
    case KllForm.UpdatableFull => dataStart + (2L * itemSize) + (totalCapacity.toLong * itemSize)
  }

  def length(itemSize: Int): LengthCheck = {
    val needed = neededBytes(itemSize)
    LengthCheck(actualLength, needed, needed)
  }

  /** The item sizes (of 4 and 8 bytes) for which the blob has exactly the right length. A float
    * sketch is told apart from a double or long one this way. An empty sketch cannot be.
    */
  def exactItemSizes: Set[Int] = Set(4, 8).filter(s => length(s).matches)
}

/** Checks a KLL blob (compact and updatable layouts) before the library sees it: every claimed size
  * must be backed by the bytes present and every field must be in range (FR-019, research Spike B).
  * The updatable size rule was derived from the library's layout and verified on every updatable
  * blob in the corpus: a 20-byte preamble, `numLevels + 1` level boundaries, the min and max items
  * and `capacity` items, with the same capacity formula as the compact layout.
  */
object KllPreflight {
  val FamilyId = 15
  private val FullDataStart = 20
  private val SingleItemStart = 8

  private def notAKll(reason: String): Diagnosis = Diagnoses.notA("KLL sketch", reason)

  private def incomplete(needed: Long, actual: Int): Diagnosis =
    Diagnoses.incomplete("KLL sketch", needed, actual)

  private def read[A](e: Either[OutOfBounds, A]): Either[Diagnosis, A] =
    e.left.map(o => Diagnoses.readPastEnd("KLL sketch", o.message))

  private def check(ok: Boolean, d: => Diagnosis): Either[Diagnosis, Unit] =
    if (ok) Right(()) else Left(d)

  // ---- capacity: how many item slots a sketch with this many levels has (the reference implementation's KllHelper)

  private def intCapAuxAux(k: Long, depth: Int): Long = {
    val twok = k << 1 // pre-multiply by 2 for rounding at the end
    var pow3 = 1L
    var i = 0
    while (i < depth) {
      pow3 *= 3
      i += 1
    }
    val tmp = (twok << depth) / pow3 // 2k * (2/3)^depth
    (tmp + 1L) >>> 1 // round up
  }

  private def intCapAux(k: Long, depth: Int): Long =
    if (depth <= 30) intCapAuxAux(k, depth)
    else {
      val half = depth / 2
      intCapAuxAux(intCapAuxAux(k, half), depth - half)
    }

  private def levelCapacity(k: Int, numLevels: Int, level: Int, m: Int): Long =
    math.max(m.toLong, intCapAux(k.toLong, numLevels - level - 1))

  private def totalCapacity(k: Int, m: Int, numLevels: Int): Long =
    (0 until numLevels).map(level => levelCapacity(k, numLevels, level, m)).sum

  /** `itemSize` is the size of one item in bytes if the caller knows the item type. Without it the
    * smallest size (4) is used as the lower bound, so that no blob is accepted that could not even
    * hold float items.
    */
  def check(bytes: Array[Byte], itemSize: Option[Int] = None): Either[Diagnosis, KllFacts] = {
    val r = new ByteReader(bytes)
    val size = itemSize.getOrElse(4)
    for {
      _ <- check(
        bytes.length >= 8,
        Diagnoses.tooShort("KLL sketch", bytes.length, 8)
      )
      preInts <- read(r.u8(0))
      serVer <- read(r.u8(1))
      family <- read(r.u8(2))
      k <- read(r.u16(4))
      m <- read(r.u8(6))
      _ <- check(family == FamilyId, notAKll(s"Family $family is not a KLL sketch (family 15)."))
      _ <- check(
        (serVer == 1 && (preInts == 2 || preInts == 5)) || (serVer == 2 && preInts == 2) || (serVer == 3 && preInts == 5),
        notAKll(s"Version $serVer with preamble size $preInts ints is not a KLL layout we know.")
      )
      _ <- check(k >= 1 && m >= 1 && m <= 8, notAKll(s"k $k or m $m is not valid."))
      facts <-
        if (preInts == 2) emptyOrSingle(bytes.length, serVer, k, m)
        else if (serVer == 3) updatable(r, k, m, size)
        else compactFull(r, k, m)
      _ <- {
        val needed = facts.neededBytes(size)
        check(bytes.length >= needed, incomplete(needed, bytes.length))
      }
    } yield facts
  }

  private def emptyOrSingle(
      actual: Int,
      serVer: Int,
      k: Int,
      m: Int
  ): Either[Diagnosis, KllFacts] = {
    val empty = serVer == 1
    Right(
      KllFacts(
        if (empty) KllForm.CompactEmpty else KllForm.CompactSingle,
        n = if (empty) 0L else 1L,
        k,
        m,
        minK = k,
        numLevels = 1,
        levels = Vector.empty,
        totalCapacity = 0,
        retained = if (empty) 0 else 1,
        dataStart = SingleItemStart,
        weightsConsistent = true,
        softIssues = Nil,
        actualLength = actual
      )
    )
  }

  private def readLevels(r: ByteReader, count: Int): Either[Diagnosis, Vector[Int]] =
    (0 until count).foldLeft[Either[Diagnosis, Vector[Int]]](Right(Vector.empty)) { (acc, i) =>
      acc.flatMap(v => read(r.i32(FullDataStart + (4 * i))).map(v :+ _))
    }

  private def weightsConsistent(levels: Vector[Int], numLevels: Int, n: Long): Boolean =
    (0 until numLevels).map(l => (levels(l + 1) - levels(l)).toLong << l).sum == n

  private def softIssues(k: Int, minK: Int): List[String] =
    if (minK > k) List(s"minK $minK is above k $k") else Nil

  private def compactFull(r: ByteReader, k: Int, m: Int): Either[Diagnosis, KllFacts] =
    for {
      n <- read(r.i64(8))
      minK <- read(r.u16(16))
      numLevels <- read(r.u8(18))
      _ <- check(
        numLevels >= 1 && numLevels <= 61,
        notAKll(s"numLevels $numLevels is outside 1 to 61.")
      )
      _ <- check(n >= 1, notAKll(s"n $n is not positive for a non-empty sketch."))
      stored <- readLevels(r, numLevels)
      cap = totalCapacity(k, m, numLevels)
      _ <- check(
        stored.head >= 0 && stored.head <= cap && stored.zip(stored.drop(1)).forall { case (a, b) =>
          a <= b
        } && stored.last <= cap,
        notAKll("The level boundaries are not in order or exceed the sketch capacity.")
      )
    } yield {
      val levels = stored :+ cap.toInt
      KllFacts(
        KllForm.CompactFull,
        n,
        k,
        m,
        minK,
        numLevels,
        levels,
        cap.toInt,
        retained = (cap - stored.head).toInt,
        dataStart = FullDataStart + (4 * numLevels),
        weightsConsistent = weightsConsistent(levels, numLevels, n),
        softIssues = softIssues(k, minK),
        actualLength = r.size
      )
    }

  private def updatable(r: ByteReader, k: Int, m: Int, size: Int): Either[Diagnosis, KllFacts] =
    for {
      n <- read(r.i64(8))
      minK <- read(r.u16(16))
      numLevels <- read(r.u8(18))
      _ <- check(
        numLevels >= 1 && numLevels <= 61,
        notAKll(s"numLevels $numLevels is outside 1 to 61.")
      )
      _ <- check(n >= 0, notAKll(s"n $n is negative."))
      cap = totalCapacity(k, m, numLevels)
      dataStart = FullDataStart + (4 * (numLevels + 1))
      // The size claim comes first, from the header alone: the layout stores the whole item array, so a forged k or
      // level count cannot be backed by a small blob, and nothing is read or allocated for it.
      needed = dataStart + (2L * size) + (cap * size)
      _ <- check(r.size >= needed, incomplete(needed, r.size))
      levels <- readLevels(r, numLevels + 1)
      _ <- check(
        levels.head >= 0 && levels.head <= cap && levels.zip(levels.drop(1)).forall { case (a, b) =>
          a <= b
        } && levels.last == cap,
        notAKll(
          "The level boundaries are not in order, or the last one does not match the sketch capacity."
        )
      )
    } yield KllFacts(
      KllForm.UpdatableFull,
      n,
      k,
      m,
      minK,
      numLevels,
      levels,
      cap.toInt,
      retained = (cap - levels.head).toInt,
      dataStart = dataStart,
      weightsConsistent = weightsConsistent(levels, numLevels, n),
      softIssues = softIssues(k, minK),
      actualLength = r.size
    )
}
