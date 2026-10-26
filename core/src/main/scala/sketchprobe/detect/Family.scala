package sketchprobe.detect

import sketchprobe.report.PreambleUnit

/** Whether the tool can decode a family in this release. */
enum Support {
  case Supported
  case RecognizedUnsupported(reason: String)
}

/** One of the 22 known family IDs.
  *
  * `layouts` maps each serVer we have seen a real writer produce to the valid byte-0 preamble sizes
  * (in the family's own unit, see [[PreambleUnit]]). It was derived from the reference corpus
  * (specs/001-sketch-inspector/research.md R2 and R12) and is checked against it by `FamilySpec`.
  * Families we have not verified have an empty map: the detector then cannot confirm serVer or
  * preamble size and treats them as unverified (soft evidence only).
  *
  * `legacySerVers` are older layouts we recognize but do not decode.
  */
final case class Family(
    id: Int,
    name: String,
    support: Support,
    unit: PreambleUnit,
    layouts: Map[Int, Set[Int]] = Map.empty,
    legacySerVers: Set[Int] = Set.empty
) {
  def supported: Boolean = support == Support.Supported
  def knownSerVers: Set[Int] = layouts.keySet
  def verified: Boolean = layouts.nonEmpty
}

object Family {
  private val notYet = Support.RecognizedUnsupported("not supported yet")
  private def unverified(id: Int, name: String) = Family(id, name, notYet, PreambleUnit.Unknown)

  val all: Vector[Family] = Vector(
    // Theta family: preamble size in 8-byte longs, low 6 bits of byte 0 (top 2 bits: resize factor).
    Family(1, "Alpha", Support.Supported, PreambleUnit.Longs, Map(3 -> Set(3)), Set(1, 2)),
    Family(2, "QuickSelect", Support.Supported, PreambleUnit.Longs, Map(3 -> Set(3)), Set(1, 2)),
    Family(
      3,
      "Compact",
      Support.Supported,
      PreambleUnit.Longs,
      Map(3 -> Set(1, 2, 3), 4 -> Set(1, 2)),
      Set(1, 2)
    ),
    Family(4, "Union", Support.Supported, PreambleUnit.Longs, Map(3 -> Set(4)), Set(1, 2)),
    Family(5, "Intersection", Support.Supported, PreambleUnit.Longs, Map(3 -> Set(3)), Set(1, 2)),
    // The library never serializes AnotB, so there is no fixture and no verified layout.
    Family(
      6,
      "AnotB",
      Support.RecognizedUnsupported(
        "the library never serializes it, so there is nothing to decode"
      ),
      PreambleUnit.Longs
    ),
    // HLL and KLL: preamble size in 4-byte ints.
    Family(7, "HLL", Support.Supported, PreambleUnit.Ints, Map(1 -> Set(2, 3, 10))),
    unverified(8, "Quantiles"),
    unverified(9, "Tuple"),
    Family(10, "Frequency", Support.Supported, PreambleUnit.Longs, Map(1 -> Set(1, 4))),
    unverified(11, "Reservoir"),
    unverified(12, "ReservoirUnion"),
    unverified(13, "VarOpt"),
    unverified(14, "VarOptUnion"),
    Family(
      15,
      "KLL",
      Support.Supported,
      PreambleUnit.Ints,
      Map(1 -> Set(2, 5), 2 -> Set(2), 3 -> Set(5))
    ),
    unverified(16, "CPC"),
    unverified(17, "REQ"),
    unverified(18, "CountMin"),
    unverified(19, "EBPPS"),
    unverified(20, "TDigest"),
    unverified(21, "BloomFilter"),
    unverified(22, "XorFilter")
  )

  private val byIdMap: Map[Int, Family] = all.map(f => f.id -> f).toMap

  def byId(id: Int): Option[Family] = byIdMap.get(id)

  def isThetaFamily(id: Int): Boolean = id >= 1 && id <= 6
}
