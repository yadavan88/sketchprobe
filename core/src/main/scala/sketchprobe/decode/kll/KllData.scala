package sketchprobe.decode.kll

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.report.{Diagnosis, DiagnosisCode, Value}

/** Which of the KLL layouts a blob is in. */
enum KllShape(val label: String) {
  case Empty extends KllShape("empty")
  case SingleItem extends KllShape("single item")
  case CompactFull extends KllShape("compact")

  /** The working layout the Java library can write; it stores the whole item array. */
  case Updatable extends KllShape("working layout")
}

/** What a KLL header says, whatever the item type is. */
final case class KllHeader(
    shape: KllShape,
    k: Int,
    m: Int,
    n: Long,
    minK: Int,
    numLevels: Int,
    /** Start of each level in the item array (`numLevels` entries; the last boundary is the total
      * capacity).
      */
    levels: Vector[Int],
    totalCapacity: Int,
    /** Number of retained items. */
    retained: Int,
    /** Bytes of the preamble and the level array, before the min and max items. */
    dataStart: Int,
    /** The retained items' weights add up to n, as they must in a valid sketch. */
    weightsConsistent: Boolean
) {
  def summary: List[(String, Value)] = List(
    "n" -> Value.Int64(n),
    "k" -> Value.Int64(k.toLong),
    "m" -> Value.Int64(m.toLong),
    "numLevels" -> Value.Int64(numLevels.toLong),
    "retainedItems" -> Value.Int64(retained.toLong)
  )
}

final case class KllData(
    itemType: KllItemType,
    header: KllHeader,
    min: Option[Value],
    max: Option[Value],
    /** (rank, quantile) at the requested ranks. Empty for an empty sketch. */
    quantiles: List[(Double, Value)],
    length: LengthCheck,
    /** The retained items, in storage order (level 0 first). Read on demand. */
    items: () => Vector[Value]
) {
  def summary: List[(String, Value)] =
    header.summary ++
      List("itemType" -> Value.Text(itemType.label)) ++
      min.map("min" -> _).toList ++ max.map("max" -> _).toList ++
      (if (quantiles.nonEmpty) List("quantiles" -> Value.Pairs(quantiles)) else Nil)
}
