package sketchprobe.adapter

import java.lang.foreign.MemorySegment
import org.apache.datasketches.common.ArrayOfStringsSerDe
import org.apache.datasketches.frequencies.{ErrorType, FrequentItemsSketch, FrequentLongsSketch}
import sketchprobe.decode.frequency.FrequencyData
import sketchprobe.preflight.{FrequencyFacts, FrequencyItemType, FrequencyPreflight}
import sketchprobe.report.{Diagnosis, DiagnosisCode, FrequentItem, Value}

/** Reads a Frequency blob with long or string items: preflight first, so a forged count or string
  * length never reaches the library, then the library for the counts and their bounds. The item
  * type is not in the blob, so the bytes decide it, or the caller says (`itemType`).
  */
object FrequencyAdapter {
  private val What = "Frequency sketch"

  private def ambiguous: Diagnosis =
    Diagnosis(
      DiagnosisCode.NeedsItemType,
      "The bytes fit both long and string items, so the item type cannot be told from the blob.",
      Some("Run again with --type long or --type string.")
    )

  def read(
      bytes: Array[Byte],
      itemType: Option[FrequencyItemType] = None
  ): Either[Diagnosis, FrequencyData] =
    for {
      facts <- FrequencyPreflight.check(bytes, itemType)
      chosen <- choose(facts)
      data <- chosen match {
        case None                          => Right(emptyData(facts))
        case Some(FrequencyItemType.Int64) => longs(bytes, facts)
        case Some(FrequencyItemType.Text)  => texts(bytes, facts)
      }
    } yield data

  /** The one type to read as; `None` for an empty sketch, which has nothing to read. */
  private def choose(f: FrequencyFacts): Either[Diagnosis, Option[FrequencyItemType]] =
    if (f.empty) Right(f.candidateTypes.headOption.filter(_ => f.candidateTypes.size == 1))
    else if (f.candidateTypes.size == 1) Right(f.candidateTypes.headOption)
    else Left(ambiguous)

  private def emptyData(f: FrequencyFacts): FrequencyData =
    build(
      f,
      f.candidateTypes.headOption.filter(_ => f.candidateTypes.size == 1),
      0L,
      () => Vector.empty
    )

  private def build(
      f: FrequencyFacts,
      t: Option[FrequencyItemType],
      maximumError: Long,
      rows: () => Vector[FrequentItem]
  ): FrequencyData =
    FrequencyData(
      itemType = t,
      empty = f.empty,
      streamLength = f.streamLength,
      numActive = f.numActive,
      maxMapSize = f.maxMapSize,
      curMapCapacity = f.curMapCapacity,
      maxMapCapacity = f.maxMapCapacity,
      maximumError = maximumError,
      length = f.length(t.getOrElse(FrequencyItemType.Int64)),
      rows = rows
    )

  private def sorted(rows: Seq[FrequentItem]): Vector[FrequentItem] =
    rows.sortBy(r => (-r.estimate, r.item.toString)).toVector

  private def longs(bytes: Array[Byte], f: FrequencyFacts): Either[Diagnosis, FrequencyData] =
    Guarded.run(What) {
      val s = FrequentLongsSketch.getInstance(MemorySegment.ofArray(bytes))
      val rows = s
        .getFrequentItems(0L, ErrorType.NO_FALSE_NEGATIVES)
        .toSeq
        .map(r =>
          FrequentItem(Value.Int64(r.getItem), r.getEstimate, r.getLowerBound, r.getUpperBound)
        )
      build(f, Some(FrequencyItemType.Int64), s.getMaximumError, () => sorted(rows))
    }

  private def texts(bytes: Array[Byte], f: FrequencyFacts): Either[Diagnosis, FrequencyData] =
    Guarded.run(What) {
      val s = FrequentItemsSketch.getInstance(MemorySegment.ofArray(bytes), new ArrayOfStringsSerDe)
      val rows = s
        .getFrequentItems(0L, ErrorType.NO_FALSE_NEGATIVES)
        .toSeq
        .map(r =>
          FrequentItem(Value.Text(r.getItem), r.getEstimate, r.getLowerBound, r.getUpperBound)
        )
      build(f, Some(FrequencyItemType.Text), s.getMaximumError, () => sorted(rows))
    }
}
