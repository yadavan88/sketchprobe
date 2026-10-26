package sketchprobe.report

/** The single result type for both renderers (data-model.md, Report). Failures are Reports too. */

enum ConfidenceLevel(val label: String) {
  case High extends ConfidenceLevel("high")
  case Medium extends ConfidenceLevel("medium")
  case Low extends ConfidenceLevel("low")
  case NoConfidence extends ConfidenceLevel("none")
}

final case class Evidence(check: String, passed: Boolean, note: String = "")

final case class Confidence(level: ConfidenceLevel, evidence: List[Evidence])

object Confidence {
  val none: Confidence = Confidence(ConfidenceLevel.NoConfidence, Nil)
}

enum RecoverabilityKind(val label: String) {
  case EstimateOnly extends RecoverabilityKind("estimate-only")
  case HashesOnly extends RecoverabilityKind("hashes-only")
  case RealValues extends RecoverabilityKind("real-values")
  case NeedsMoreInfo extends RecoverabilityKind("needs-more-info")
}

final case class Recoverability(kind: RecoverabilityKind, statement: String)

enum WarningCode(val label: String) {
  case ConfidenceMedium extends WarningCode("confidence-medium")
  case SeedMismatch extends WarningCode("seed-mismatch")
  case ItemTypeInferred extends WarningCode("item-type-inferred")
  case InputNormalized extends WarningCode("input-normalized")
  case InputAmbiguous extends WarningCode("input-ambiguous")
  case OptionIgnored extends WarningCode("option-ignored")
}

final case class Warning(code: WarningCode, message: String, nextStep: Option[String] = None)

/** Why a blob was not decoded. The exit status is part of the CLI contract (contracts/cli.md). */
enum DiagnosisCode(val label: String, val exitStatus: Int) {
  // 1: unreadable input
  case EmptyInput extends DiagnosisCode("empty-input", 1)
  case InputUnreadable extends DiagnosisCode("input-unreadable", 1)
  case InputTooLarge extends DiagnosisCode("input-too-large", 1)
  case MultipleBlobs extends DiagnosisCode("multiple-blobs", 1)
  case InputAmbiguous extends DiagnosisCode("input-ambiguous", 1)
  // 2: not a recognizable sketch
  case TooShort extends DiagnosisCode("too-short", 2)
  case NotASketch extends DiagnosisCode("not-a-sketch", 2)
  // 3: recognized but not decoded
  case UnsupportedFamily extends DiagnosisCode("unsupported-family", 3)
  case UnsupportedLayout extends DiagnosisCode("unsupported-layout", 3)
  case BigEndian extends DiagnosisCode("big-endian", 3)
  case Incomplete extends DiagnosisCode("incomplete", 3)
  case NeedsItemType extends DiagnosisCode("needs-item-type", 3)
  // 64: usage
  case Usage extends DiagnosisCode("usage", 64)
}

final case class Diagnosis(code: DiagnosisCode, reason: String, nextStep: Option[String] = None) {
  def exitStatus: Int = code.exitStatus
}

/** Units differ by family: Theta counts 8-byte longs, HLL and KLL count 4-byte ints (research R2).
  */
enum PreambleUnit(val label: String) {
  case Longs extends PreambleUnit("longs")
  case Ints extends PreambleUnit("ints")
  case Unknown extends PreambleUnit("unknown")
}

/** A value in the report summary. Rendered by both the human and the JSON renderer. */
enum Value {
  case Num(v: Double)
  case Int64(v: Long)
  case Text(v: String)
  case Flag(v: Boolean)

  /** (rank, value) pairs, for example KLL quantiles. */
  case Pairs(v: List[(Double, Value)])
}

/** One tracked item of a Frequency sketch. The true count lies between the lower and the upper
  * bound; the estimate is the upper bound.
  */
final case class FrequentItem(item: Value, estimate: Long, lowerBound: Long, upperBound: Long)

final case class Contents(
    retainedHashes: Option[Vector[Long]],
    retainedItems: Option[Vector[Value]],
    total: Int,
    /** A Frequency sketch's items, largest first. Possibly only the top of `total`. */
    frequentItems: Option[Vector[FrequentItem]] = None
)

enum InputFormat(val label: String) {
  case MongoJson extends InputFormat("mongo-json")
  case JsonString extends InputFormat("json-string")
  case Hex extends InputFormat("hex")
  case Base64 extends InputFormat("base64")
  case Base64Url extends InputFormat("base64url")
  case Raw extends InputFormat("raw")
  case Unknown extends InputFormat("unknown")
}

enum FormatSource(val label: String) {
  case Detected extends FormatSource("detected")
  case Forced extends FormatSource("forced")
}

final case class InputAlternative(format: InputFormat, identified: Boolean)

final case class InputInfo(
    format: InputFormat,
    source: FormatSource,
    bytes: Int,
    alternatives: List[InputAlternative] = Nil,
    notes: List[String] = Nil
)

final case class HeaderInfo(
    byte0: Int,
    preambleSize: Int,
    preambleUnit: PreambleUnit,
    resizeFactor: Option[Int],
    serVer: Int,
    familyId: Int,
    rawPrefix: Vector[Byte]
) {
  def rawPrefixHex: String = rawPrefix.map(b => f"${b & 0xff}%02x").mkString
}

final case class FamilyInfo(id: Int, name: String, layout: Option[String], supported: Boolean)

final case class Report(
    input: InputInfo,
    header: Option[HeaderInfo],
    family: Option[FamilyInfo],
    confidence: Confidence,
    summary: List[(String, Value)],
    contents: Option[Contents],
    recoverability: Option[Recoverability],
    warnings: List[Warning],
    diagnosis: Option[Diagnosis]
) {

  /** Matches the process exit status (contracts/cli.md). */
  def status: Int = diagnosis.map(_.exitStatus).getOrElse(0)
}
