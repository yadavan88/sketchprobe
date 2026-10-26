package sketchprobe.decode.kll

import sketchprobe.report.{Diagnosis, DiagnosisCode, Recoverability, RecoverabilityKind}

/** The item types a KLL sketch can hold and that we decode. The blob does not record the type, so
  * the caller must supply it (specs/001-sketch-inspector/research.md, R2).
  */
enum KllItemType(val label: String, val size: Int) {
  case Float32 extends KllItemType("float", 4)
  case Float64 extends KllItemType("double", 8)
  case Int64 extends KllItemType("long", 8)
}

object KllItemType {

  /** Parses the `--type` option. `string` is recognized but not supported in this release. */
  def parse(s: String): Either[Diagnosis, KllItemType] = s.trim.toLowerCase match {
    case "float"  => Right(KllItemType.Float32)
    case "double" => Right(KllItemType.Float64)
    case "long"   => Right(KllItemType.Int64)
    case "string" =>
      Left(
        Diagnosis(
          DiagnosisCode.UnsupportedLayout,
          "KLL sketches of text items are recognized but not supported yet.",
          Some("Use --type float, double or long for a numeric KLL sketch.")
        )
      )
    case other =>
      Left(
        Diagnosis(
          DiagnosisCode.Usage,
          s"Unknown item type '$other'.",
          Some("Use --type float, double or long.")
        )
      )
  }

  /** What a KLL sketch can reveal, which depends on whether the item type is known. */
  def recoverability(known: Boolean): Recoverability =
    if (known) {
      Recoverability(
        RecoverabilityKind.RealValues,
        "The retained items are real values from the data. Only a sample is kept, not every value."
      )
    } else {
      Recoverability(
        RecoverabilityKind.NeedsMoreInfo,
        "The values are present, but their type is not recorded in the blob. Supply --type float, double or long."
      )
    }

  def needsType: Diagnosis = Diagnosis(
    DiagnosisCode.NeedsItemType,
    "A KLL sketch does not record whether its items are float, double or long.",
    Some("Run again with --type float, --type double or --type long.")
  )
}
