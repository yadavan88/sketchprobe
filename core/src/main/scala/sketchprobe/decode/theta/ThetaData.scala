package sketchprobe.decode.theta

import sketchprobe.decode.{ByteReader, LengthCheck, OutOfBounds}
import sketchprobe.report.{Diagnosis, DiagnosisCode, Recoverability, RecoverabilityKind, Value}

/** The Theta family IDs we decode. AnotB (6) is recognized elsewhere and is not decodable. */
enum ThetaKind(val familyId: Int, val label: String) {
  case Alpha extends ThetaKind(1, "Alpha")
  case QuickSelect extends ThetaKind(2, "QuickSelect")
  case Compact extends ThetaKind(3, "Compact")
  case Union extends ThetaKind(4, "Union")
  case Intersection extends ThetaKind(5, "Intersection")
}

final case class ThetaData(
    kind: ThetaKind,
    serVer: Int,
    preambleLongs: Int,
    /** What kind of state this is, for display: empty, single item, exact, estimating, update
      * sketch, ...
      */
    shape: String,
    empty: Boolean,
    ordered: Boolean,
    compactFlag: Boolean,
    lgNomLongs: Option[Int],
    p: Option[Float],
    thetaLong: Long,
    seedHash: Int,
    /** Entries below theta, as `Sketch.getRetainedEntries(true)` reports them. */
    retained: Int,
    estimationMode: Boolean,
    estimate: Double,
    lowerBounds: Vector[Double], // index 0 is 1 standard deviation
    upperBounds: Vector[Double],
    length: LengthCheck,
    /** The retained 64-bit hashes (not the original values). Read on demand: a sketch can hold
      * millions.
      */
    hashes: () => Vector[Long]
) {
  def theta: Double = thetaLong / Long.MaxValue.toDouble

  def summary: List[(String, Value)] =
    List("estimate" -> Value.Num(estimate)) ++
      (1 to 3).flatMap(sd =>
        List(
          s"lowerBound$sd" -> Value.Num(lowerBounds(sd - 1)),
          s"upperBound$sd" -> Value.Num(upperBounds(sd - 1))
        )
      ) ++
      List(
        "theta" -> Value.Num(theta),
        "retainedEntries" -> Value.Int64(retained.toLong),
        "estimationMode" -> Value.Flag(estimationMode),
        "empty" -> Value.Flag(empty),
        "ordered" -> Value.Flag(ordered),
        "seedHash" -> Value.Int64(seedHash.toLong)
      ) ++ lgNomLongs.map(v => "lgNomLongs" -> Value.Int64(v.toLong))
}

object ThetaData {
  val recoverability: Recoverability = Recoverability(
    RecoverabilityKind.HashesOnly,
    "Original values cannot be recovered. The retained entries are 64-bit hashes, not the data."
  )
}
