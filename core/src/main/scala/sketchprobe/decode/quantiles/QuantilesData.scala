package sketchprobe.decode.quantiles

import sketchprobe.decode.LengthCheck
import sketchprobe.report.{Recoverability, RecoverabilityKind, Value}

/** Which of the Quantiles layouts a blob is in. */
enum QuantilesShape(val label: String) {
  case Empty extends QuantilesShape("empty")
  case Compact extends QuantilesShape("compact")

  /** The working layout the Java library can write; it stores the whole item array. */
  case Updatable extends QuantilesShape("working layout")
}

/** What a Quantiles header says. */
final case class QuantilesHeader(
    shape: QuantilesShape,
    k: Int,
    n: Long,
    /** Number of retained items. */
    retained: Long
) {
  def summary: List[(String, Value)] = List(
    "n" -> Value.Int64(n),
    "k" -> Value.Int64(k.toLong),
    "retainedItems" -> Value.Int64(retained)
  )
}

final case class QuantilesData(
    header: QuantilesHeader,
    min: Option[Value],
    max: Option[Value],
    /** (rank, quantile) at the requested ranks. Empty for an empty sketch. */
    quantiles: List[(Double, Value)],
    length: LengthCheck,
    /** The retained items, sorted ascending. Read on demand. */
    items: () => Vector[Value]
) {
  def summary: List[(String, Value)] =
    header.summary ++
      min.map("min" -> _).toList ++ max.map("max" -> _).toList ++
      (if (quantiles.nonEmpty) List("quantiles" -> Value.Pairs(quantiles)) else Nil)
}

object QuantilesData {
  val recoverability: Recoverability = Recoverability(
    RecoverabilityKind.RealValues,
    "The retained items are real values from the data. Only a sample is kept, not every value."
  )
}
