package sketchprobe.preflight

import java.nio.{ByteBuffer, ByteOrder}
import sketchprobe.decode.LengthCheck
import sketchprobe.report.{Diagnoses, Diagnosis, DiagnosisCode}

/** What a Frequency sketch holds. The blob does not record it (research R16, R18). */
enum FrequencyItemType(val label: String) {
  case Int64 extends FrequencyItemType("long")
  case Text extends FrequencyItemType("string")
}

/** What the header, the counts and the item bytes of a Frequency blob say. Nothing is estimated
  * here: the library does that, and only after these facts have been accepted.
  */
final case class FrequencyFacts private[preflight] (
    empty: Boolean,
    lgMaxMapSize: Int,
    lgCurMapSize: Int,
    numActive: Int,
    streamLength: Long,
    /** The purge offset, which is also the maximum error of every count. */
    offset: Long,
    /** The item types whose bytes fit this blob exactly (or, failing that, completely). Both for an
      * empty sketch, which cannot tell.
      */
    candidateTypes: Set[FrequencyItemType],
    /** Where the strings end, if the blob parses as string items. */
    textEnd: Option[Long],
    actualLength: Int
) {
  def maxMapSize: Int = 1 << lgMaxMapSize
  def maxMapCapacity: Int = FrequencyPreflight.capacity(lgMaxMapSize)
  def curMapCapacity: Int = FrequencyPreflight.capacity(lgCurMapSize)

  /** Bytes the layout needs for this item type, or `Long.MaxValue` if the bytes are not valid as
    * that type.
    */
  def neededBytes(t: FrequencyItemType): Long =
    if (empty) FrequencyPreflight.EmptyBytes
    else
      t match {
        case FrequencyItemType.Int64 =>
          FrequencyPreflight.PreambleBytes + (16L * numActive)
        case FrequencyItemType.Text => textEnd.getOrElse(Long.MaxValue)
      }

  def length(t: FrequencyItemType): LengthCheck = {
    val needed = neededBytes(t)
    LengthCheck(actualLength, needed, needed)
  }
}

/** Checks a Frequency blob (family 10, long or string items) before the library sees it. The
  * library trusts `numActive`, every string length and the table sizes, so a forged blob made it
  * run out of memory, throw on a negative size or accept nonsense (research R18). Every claimed
  * size is checked against the bytes present, and every field against its range, before anything is
  * allocated.
  *
  * Layout: an empty sketch is 8 bytes (preLongs 1). Otherwise a 32-byte preamble (preLongs 4,
  * serVer 1, family 10, lgMaxMapSize, lgCurMapSize, flags, a serde slot, `numActive` at 8, stream
  * length at 16, offset at 24), `numActive` counts of 8 bytes, then the items: 8 bytes each for
  * long items, a 4-byte length plus UTF-8 each for string items.
  */
object FrequencyPreflight {
  val FamilyId = 10
  val SerVer = 1
  private[preflight] val EmptyBytes = 8L
  private[preflight] val PreambleBytes = 32L
  private val MinLgMax = 3
  private val MaxLgMax = 30
  private val EmptyFlags = 5

  /** Items a table of `2^lg` slots holds before it purges: three quarters of the slots. */
  private[preflight] def capacity(lg: Int): Int = ((1L << lg) * 3 / 4).toInt

  private def notAFrequency(reason: String): Diagnosis =
    Diagnoses.notA("Frequency sketch", reason)

  private def incomplete(needed: Long, actual: Int): Diagnosis =
    Diagnoses.incomplete("Frequency sketch", needed, actual)

  private def wrongType(blob: FrequencyItemType, asked: FrequencyItemType): Diagnosis =
    Diagnosis(
      DiagnosisCode.NeedsItemType,
      s"The bytes do not fit ${asked.label} items; they fit ${blob.label} items.",
      Some(s"Run again with --type ${blob.label}.")
    )

  private def check(ok: Boolean, d: => Diagnosis): Either[Diagnosis, Unit] =
    if (ok) Right(()) else Left(d)

  /** The strings from `start`, `n` of them: where they end, or why they are not strings. */
  private def walkStrings(b: ByteBuffer, start: Long, n: Int): Either[Diagnosis, Long] = {
    val size = b.limit()
    var pos = start
    var i = 0
    var failure: Option[Diagnosis] = None
    while (failure.isEmpty && i < n) {
      if (pos + 4 > size) failure = Some(incomplete(pos + 4, size))
      else {
        val len = b.getInt(pos.toInt)
        if (len < 0) failure = Some(notAFrequency(s"A string item has the length $len."))
        else if (pos + 4 + len > size) failure = Some(incomplete(pos + 4 + len, size))
        else pos += 4L + len
      }
      i += 1
    }
    failure.toLeft(pos)
  }

  def check(
      bytes: Array[Byte],
      itemType: Option[FrequencyItemType] = None
  ): Either[Diagnosis, FrequencyFacts] = {
    val size = bytes.length
    if (size < EmptyBytes.toInt)
      Left(Diagnoses.tooShort("Frequency sketch", size, EmptyBytes.toInt))
    else {
      val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val preLongs = bytes(0) & 0xff
      val serVer = bytes(1) & 0xff
      val family = bytes(2) & 0xff
      val lgMax = bytes(3) & 0xff
      val lgCur = bytes(4) & 0xff
      val flags = bytes(5) & 0xff
      val empty = (flags & EmptyFlags) != 0
      for {
        _ <- check(
          family == FamilyId,
          notAFrequency(s"Family $family is not a Frequency sketch (family $FamilyId).")
        )
        _ <- check(
          serVer == SerVer,
          Diagnoses.legacyVersion("Frequency sketch", serVer, SerVer.toString)
        )
        _ <- check(
          preLongs == 1 || preLongs == 4,
          notAFrequency(s"The preamble size is $preLongs longs; it must be 1 (empty) or 4.")
        )
        _ <- check(
          lgMax >= MinLgMax && lgMax <= MaxLgMax,
          notAFrequency(s"The table size 2^$lgMax is outside 2^$MinLgMax to 2^$MaxLgMax.")
        )
        _ <- check(
          lgCur >= MinLgMax && lgCur <= lgMax,
          notAFrequency(s"The current table size 2^$lgCur is outside 2^$MinLgMax to 2^$lgMax.")
        )
        _ <- check(
          (preLongs == 1) == empty,
          notAFrequency(
            if (empty) "The empty flag is set but the preamble is the full 4 longs."
            else "The empty flag is not set but the preamble is the 1 long of an empty sketch."
          )
        )
        facts <-
          if (empty) Right(emptyFacts(lgMax, lgCur, size)) else full(b, lgMax, lgCur, itemType)
      } yield facts
    }
  }

  private def emptyFacts(lgMax: Int, lgCur: Int, size: Int): FrequencyFacts =
    FrequencyFacts(
      empty = true,
      lgMaxMapSize = lgMax,
      lgCurMapSize = lgCur,
      numActive = 0,
      streamLength = 0L,
      offset = 0L,
      candidateTypes = FrequencyItemType.values.toSet,
      textEnd = None,
      actualLength = size
    )

  private def full(
      b: ByteBuffer,
      lgMax: Int,
      lgCur: Int,
      itemType: Option[FrequencyItemType]
  ): Either[Diagnosis, FrequencyFacts] = {
    val size = b.limit()
    for {
      _ <- check(size >= PreambleBytes, incomplete(PreambleBytes, size))
      n = b.getInt(8)
      streamLength = b.getLong(16)
      offset = b.getLong(24)
      _ <- check(n >= 0, notAFrequency(s"The active item count is $n."))
      _ <- check(
        n <= capacity(lgMax),
        notAFrequency(
          s"The active item count $n is more than a table of 2^$lgMax slots holds (${capacity(lgMax)})."
        )
      )
      _ <- check(streamLength >= 0L, notAFrequency(s"The stream length is $streamLength."))
      _ <- check(offset >= 0L, notAFrequency(s"The offset (maximum error) is $offset."))
      countsEnd = PreambleBytes + (8L * n)
      // Every item takes at least its 8-byte count and, as a string, a 4-byte length (a long takes 8):
      // a blob shorter than that cannot hold n items of either type, whatever its bytes say.
      minEnd = PreambleBytes + (12L * n)
      _ <- check(size >= minEnd, incomplete(minEnd, size))
      total <- countsTotal(b, n, streamLength)
      textEnd = walkStrings(b, countsEnd, n)
      longEnd = PreambleBytes + (16L * n)
      fitsLong = size >= longEnd
      exactLong = size.toLong == longEnd
      exactText = textEnd.exists(_ == size.toLong)
      candidates <- chooseType(itemType, size, longEnd, textEnd, exactLong, exactText, fitsLong)
    } yield FrequencyFacts(
      empty = false,
      lgMaxMapSize = lgMax,
      lgCurMapSize = lgCur,
      numActive = n,
      streamLength = streamLength,
      offset = offset,
      candidateTypes = candidates,
      textEnd = textEnd.toOption,
      actualLength = size
    )
  }

  /** Every count is positive and together they cannot exceed the stream length. */
  private def countsTotal(b: ByteBuffer, n: Int, streamLength: Long): Either[Diagnosis, Long] = {
    var sum = 0L
    var i = 0
    var failure: Option[Diagnosis] = None
    while (failure.isEmpty && i < n) {
      val c = b.getLong((PreambleBytes + (8L * i)).toInt)
      if (c <= 0L) failure = Some(notAFrequency(s"Item $i has the count $c; counts are positive."))
      else sum += c
      if (sum < 0L || sum > streamLength)
        failure = failure.orElse(
          Some(notAFrequency(s"The counts add up to more than the stream length $streamLength."))
        )
      i += 1
    }
    failure.toLeft(sum)
  }

  /** Which item type or types fit the blob. A type that fits exactly wins over one that merely fits
    * with bytes to spare; a given type that does not fit while the other does is the user's
    * mistake, and is said so.
    */
  private def chooseType(
      asked: Option[FrequencyItemType],
      size: Int,
      longEnd: Long,
      textEnd: Either[Diagnosis, Long],
      exactLong: Boolean,
      exactText: Boolean,
      fitsLong: Boolean
  ): Either[Diagnosis, Set[FrequencyItemType]] = {
    import FrequencyItemType.{Int64, Text}
    asked match {
      case Some(Int64) =>
        if (exactLong) Right(Set(Int64))
        else if (exactText) Left(wrongType(Text, Int64))
        else if (fitsLong) Right(Set(Int64))
        else Left(incomplete(longEnd, size))
      case Some(Text) =>
        if (exactText) Right(Set(Text))
        else if (exactLong) Left(wrongType(Int64, Text))
        else textEnd.map(_ => Set[FrequencyItemType](Text))
      case None =>
        (exactLong, exactText) match {
          case (true, false) => Right(Set(Int64))
          case (false, true) => Right(Set(Text))
          case (true, true)  => Right(Set(Int64, Text))
          case _             =>
            Left(
              Diagnosis(
                DiagnosisCode.Incomplete,
                s"The blob's $size bytes end where neither long items (${longEnd} bytes) nor string items would: it is cut off, has trailing bytes, or is damaged.",
                Some(
                  "If it is complete, run again with --type long or --type string to read it anyway."
                )
              )
            )
        }
    }
  }
}
