package sketchprobe.report

/** The wording of every failure, in one place: the reason says what is wrong in plain language, and
  * the next step says what to do about it where there is something to do (spec FR-020). Nothing
  * here names an internal class or a stack frame.
  *
  * `what` is the name of the sketch as a user would say it, for example "HLL sketch".
  */
object Diagnoses {
  private def article(what: String): String =
    if (what.headOption.exists(c => "AEIOU".contains(c)) || what.startsWith("HLL")) "an" else "a"

  /** The bytes cannot be this kind of sketch: a field has an impossible value. */
  def notA(what: String, reason: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.NotASketch,
      reason,
      Some(s"Check that the input is ${article(what)} $what and was copied completely.")
    )

  /** The blob is shorter than its own header says it should be: cut off, or the size is forged. */
  def incomplete(what: String, needed: Long, actual: Int): Diagnosis =
    Diagnosis(
      DiagnosisCode.Incomplete,
      s"The $what is cut off, or its header claims more data than the blob holds: it needs at least $needed bytes but has $actual.",
      Some("Copy the whole value; if it was copied completely, the value is damaged.")
    )

  /** A read ran past the end of the blob, with the reader's own account of where. */
  def readPastEnd(what: String, detail: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.Incomplete,
      s"The $what is cut off: $detail.",
      Some("Copy the whole value; the blob looks truncated.")
    )

  def tooShort(what: String, actual: Int, minimum: Int): Diagnosis =
    Diagnosis(
      DiagnosisCode.TooShort,
      s"Only $actual bytes, but ${article(what)} $what has at least $minimum.",
      Some("Check that the whole value was copied.")
    )

  def bigEndian(what: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.BigEndian,
      s"The $what has the big-endian flag set; only little-endian sketches are supported.",
      Some(
        "Re-serialize the sketch on a little-endian machine, which is what nearly all systems use."
      )
    )

  /** An older serial version that this release does not read. */
  def legacyVersion(what: String, serVer: Int, supported: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.UnsupportedLayout,
      s"The $what has serial version $serVer, a legacy layout that is not supported (supported: $supported).",
      Some("Re-serialize the sketch with a current Apache DataSketches release.")
    )

  def unsupportedLayout(reason: String, nextStep: Option[String]): Diagnosis =
    Diagnosis(DiagnosisCode.UnsupportedLayout, reason, nextStep)

  def unsupportedFamily(name: String, id: Int, why: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.UnsupportedFamily,
      s"This is a $name sketch (family $id), which is recognized but $why.",
      None
    )

  def unknownFamily(id: Int): Diagnosis =
    Diagnosis(
      DiagnosisCode.NotASketch,
      s"Family ID $id is not a known sketch family (1 to 22).",
      Some("Check that the input is a serialized Apache DataSketches sketch.")
    )
}
