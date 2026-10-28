package sketchprobe.detect

import sketchprobe.preflight.Checked
import sketchprobe.report.{Confidence, ConfidenceLevel, Evidence, HeaderInfo}

/** What the header says about a blob: its raw fields, the family if the ID is known, and how sure
  * we are, with the evidence behind that.
  */
final case class Detection(header: HeaderInfo, family: Option[Family], confidence: Confidence)

/** Decides how confident we are that a blob is the sketch its header claims.
  *
  * The evidence is four checks: the family ID is known, the serial version is one we have seen a
  * real writer produce for that family, the preamble size (read in the family's own unit) is a
  * valid one for that version, and the length matches the layout. The first three are hard: if one
  * fails, confidence is low and only the raw header is worth showing. The length is soft: a
  * complete blob with trailing bytes is medium, a cut-off one is low. Flag bits are not evidence:
  * writers disagree about them (fixtures/README.md, "Writer differences").
  *
  * `checked` is the preflight result. `None` means preflight rejected the blob, so its length
  * cannot be shown to fit.
  */
object Detector {
  def detect(
      bytes: Array[Byte],
      checked: Option[Checked]
  ): Either[SketchHeader.TooShort, Detection] =
    SketchHeader.parse(bytes).map { header =>
      val family = Family.byId(header.familyId)
      val layouts = family.map(_.layouts).getOrElse(Map.empty)
      val sizes = layouts.get(header.serVer)
      val lengthEv = lengthEvidence(bytes.length, checked)
      val evidence = List(
        Evidence(
          "family-id",
          passed = family.isDefined,
          family.fold(s"byte 2 = ${header.familyId}, not a known family")(f =>
            s"byte 2 = ${header.familyId} (${f.name})"
          )
        ),
        Evidence("serial-version", passed = sizes.isDefined, s"byte 1 = ${header.serVer}"),
        Evidence(
          "preamble-size",
          passed = sizes.exists(_.contains(header.preambleSize)),
          s"${header.preambleSize} ${header.preambleUnit.label}"
        ),
        lengthEv.evidence
      )
      val hardOk = evidence.take(3).forall(_.passed)
      val level =
        if (!hardOk || !lengthEv.complete) ConfidenceLevel.Low
        else if (lengthEv.evidence.passed) ConfidenceLevel.High
        else ConfidenceLevel.Medium
      Detection(header, family, Confidence(level, evidence))
    }

  private final case class LengthEv(evidence: Evidence, complete: Boolean)

  private def lengthEvidence(actual: Int, checked: Option[Checked]): LengthEv = {
    val (complete, exact) = checked match {
      case None                       => (false, false)
      case Some(Checked.Hll(f))       => (f.length.complete, f.length.matches)
      case Some(Checked.Theta(f))     => (f.length.complete, f.length.matches)
      case Some(Checked.Quantiles(f)) => (f.length.complete, f.length.matches)
      case Some(Checked.Kll(f))       => (f.length(4).complete, f.exactItemSizes.nonEmpty)
      case Some(Checked.Frequency(f)) =>
        val lengths = f.candidateTypes.map(f.length)
        (lengths.exists(_.complete), lengths.exists(_.matches))
    }
    val note =
      if (exact) "matches the layout exactly"
      else if (complete) "longer than the layout needs: trailing bytes"
      else "shorter than the layout needs, or the header claims more than is there"
    LengthEv(Evidence("length", exact, s"$actual bytes; $note"), complete)
  }
}
