package sketchprobe.preflight

import sketchprobe.detect.Family
import sketchprobe.report.{Diagnoses, Diagnosis}

/** What a blob was checked as, with the facts its family's preflight established. */
enum Checked {
  case Hll(facts: HllFacts)
  case Theta(facts: ThetaFacts)
  case Kll(facts: KllFacts)
  case Frequency(facts: FrequencyFacts)
}

/** The single entry point for checking a blob before the library sees it. Picks the family from the
  * header, and runs that family's preflight. Never throws.
  */
object Preflight {
  private val MinHeader = 8
  private val ThetaIds = Set(1, 2, 3, 4, 5)
  private val HllId = 7
  private val KllId = 15
  private val FrequencyId = 10

  /** `kllItemSize` is the size in bytes of one KLL item (4 for float, 8 for double or long), when
    * known. `frequencyType` is the item type of a Frequency sketch, when known.
    */
  def check(
      bytes: Array[Byte],
      kllItemSize: Option[Int] = None,
      frequencyType: Option[FrequencyItemType] = None
  ): Either[Diagnosis, Checked] =
    if (bytes.length < MinHeader) {
      Left(Diagnoses.tooShort("sketch", bytes.length, MinHeader))
    } else {
      val id = bytes(2) & 0xff
      Family.byId(id) match {
        case None                    => Left(Diagnoses.unknownFamily(id))
        case Some(f) if !f.supported =>
          val why = f.support match {
            case sketchprobe.detect.Support.RecognizedUnsupported(r) => r
            case _                                                   => "not supported yet"
          }
          Left(Diagnoses.unsupportedFamily(f.name, id, why))
        case Some(_) if ThetaIds.contains(id) => ThetaPreflight.check(bytes).map(Checked.Theta(_))
        case Some(_) if id == HllId           => HllPreflight.check(bytes).map(Checked.Hll(_))
        case Some(_) if id == FrequencyId     =>
          FrequencyPreflight.check(bytes, frequencyType).map(Checked.Frequency(_))
        case Some(_) if id == KllId => KllPreflight.check(bytes, kllItemSize).map(Checked.Kll(_))
        case Some(f)                =>
          Left(Diagnoses.unsupportedFamily(f.name, id, "not supported yet"))
      }
    }
}
