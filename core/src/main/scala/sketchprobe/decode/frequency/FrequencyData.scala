package sketchprobe.decode.frequency

import sketchprobe.decode.LengthCheck
import sketchprobe.preflight.FrequencyItemType
import sketchprobe.report.{FrequentItem, Recoverability, RecoverabilityKind, Value}

final case class FrequencyData(
    /** `None` only for an empty sketch whose item type was not given. */
    itemType: Option[FrequencyItemType],
    empty: Boolean,
    streamLength: Long,
    numActive: Int,
    maxMapSize: Int,
    curMapCapacity: Int,
    maxMapCapacity: Int,
    /** 0 means every count is exact. */
    maximumError: Long,
    length: LengthCheck,
    /** Every tracked item, largest estimate first. Read on demand. */
    rows: () => Vector[FrequentItem]
) {
  def summary: List[(String, Value)] =
    List(
      "streamLength" -> Value.Int64(streamLength),
      "numActive" -> Value.Int64(numActive.toLong),
      "maxMapSize" -> Value.Int64(maxMapSize.toLong),
      "maxMapCapacity" -> Value.Int64(maxMapCapacity.toLong),
      "maximumError" -> Value.Int64(maximumError),
      "empty" -> Value.Flag(empty)
    ) ++ itemType.map(t => "itemType" -> Value.Text(t.label))
}

object FrequencyData {
  val recoverability: Recoverability = Recoverability(
    RecoverabilityKind.RealValues,
    "The tracked items are real values from the data, with their counts. Items that were purged are not kept."
  )
}
