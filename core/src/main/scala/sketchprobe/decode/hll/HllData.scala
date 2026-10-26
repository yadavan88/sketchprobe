package sketchprobe.decode.hll

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.report.{Diagnosis, DiagnosisCode, Recoverability, RecoverabilityKind, Value}

enum HllMode(val label: String) {
  case List extends HllMode("LIST")
  case Set extends HllMode("SET")
  case Hll extends HllMode("HLL")
}

enum RegisterWidth(val label: String) {
  case Hll4 extends RegisterWidth("HLL_4")
  case Hll6 extends RegisterWidth("HLL_6")
  case Hll8 extends RegisterWidth("HLL_8")
}

final case class HllData(
    lgK: Int,
    mode: HllMode,
    width: RegisterWidth,
    empty: Boolean,
    compactFlag: Boolean,
    outOfOrder: Boolean,
    couponCount: Option[Int], // LIST and SET only
    curMin: Int, // HLL mode only
    numAtCurMin: Int, // HLL mode only
    hipAccum: Double, // HLL mode only
    auxCount: Int, // HLL mode, HLL_4 only
    estimate: Double,
    lowerBounds: Vector[Double], // index 0 is 1 standard deviation
    upperBounds: Vector[Double],
    length: LengthCheck
) {
  def summary: List[(String, Value)] =
    List("estimate" -> Value.Num(estimate)) ++
      (1 to 3).flatMap(sd =>
        List(
          s"lowerBound$sd" -> Value.Num(lowerBounds(sd - 1)),
          s"upperBound$sd" -> Value.Num(upperBounds(sd - 1))
        )
      ) ++
      List(
        "lgK" -> Value.Int64(lgK.toLong),
        "mode" -> Value.Text(mode.label),
        "registerWidth" -> Value.Text(width.label),
        "empty" -> Value.Flag(empty),
        "outOfOrder" -> Value.Flag(outOfOrder)
      )
}

object HllData {
  val recoverability: Recoverability = Recoverability(
    RecoverabilityKind.EstimateOnly,
    "Original values cannot be recovered. This sketch holds only a distinct-count estimate."
  )
}
