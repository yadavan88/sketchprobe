package sketchprobe

import sketchprobe.adapter.{FrequencyAdapter, HllAdapter, KllAdapter, QuantilesAdapter, ThetaAdapter}
import sketchprobe.decode.frequency.FrequencyData
import sketchprobe.decode.hll.HllData
import sketchprobe.decode.kll.{KllData, KllItemType}
import sketchprobe.decode.quantiles.QuantilesData
import sketchprobe.decode.theta.ThetaData
import sketchprobe.detect.{Detector, Family, SketchHeader, Support}
import sketchprobe.preflight.{Checked, FrequencyItemType, KllFacts, Preflight}
import sketchprobe.report._

/** What the caller asked for. */
final case class InspectOptions(
    /** The `--type` the user gave, as typed: float, double, long or string. Which of these a sketch
      * accepts depends on its family.
      */
    itemTypeName: Option[String] = None,
    ranks: List[Double] = KllAdapter.DefaultRanks,
    showHashes: Boolean = false,
    showItems: Boolean = false,
    /** The Theta seed the user says the sketch was built with. */
    seed: Option[Long] = None,
    /** Identify the sketch and stop: the header and the confidence, no decoding. */
    headerOnly: Boolean = false
)

/** Identifies a decoded blob and turns the result of the right decoder into a [[Report]]. Every
  * outcome is a Report, including failures: it never throws.
  */
object Inspector {

  def inspect(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions = InspectOptions()
  ): Report = {
    val r = inspectCore(bytes, input, options)
    r.copy(warnings = r.warnings ++ ignoredOptions(options, r))
  }

  /** `--show-hashes` and `--show-items` apply to some families only; say so when one is passed for
    * a family it does not apply to (contracts/cli.md).
    */
  private def ignoredOptions(options: InspectOptions, r: Report): List[Warning] =
    r.family.filter(_ => r.summary.nonEmpty || r.diagnosis.isEmpty).toList.flatMap { f =>
      val theta = Family.isThetaFamily(f.id)
      val hasItems = f.id == 15 || f.id == 10 || f.id == 8
      def ignored(option: String, why: String) =
        Warning(WarningCode.OptionIgnored, s"$option was ignored: $why", None)
      List(
        Option.when(options.showHashes && !theta)(
          ignored(
            "--show-hashes",
            s"${f.name} sketches have no retained hashes; only Theta sketches do."
          )
        ),
        Option.when(options.showItems && !hasItems)(
          ignored(
            "--show-items",
            s"${f.name} sketches have no retained items; only KLL, Quantiles and Frequency sketches do."
          )
        )
      ).flatten
    }

  private def inspectCore(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions
  ): Report =
    SketchHeader.parse(bytes) match {
      case Left(tooShort) =>
        failure(
          input,
          None,
          None,
          Diagnoses.tooShort("sketch", tooShort.length, SketchHeader.MinBytes),
          rawFallback = Some(bytes)
        )
      case Right(header) =>
        Family.byId(header.familyId) match {
          case None =>
            failure(
              input,
              Some(header),
              None,
              Diagnoses.unknownFamily(header.familyId)
            )
          case Some(family) if !family.supported =>
            val why = family.support match {
              case Support.RecognizedUnsupported(reason) => reason
              case _                                     => "not supported"
            }
            failure(
              input,
              Some(header),
              Some(FamilyInfo(family.id, family.name, None, supported = false)),
              Diagnoses.unsupportedFamily(family.name, family.id, why)
            )
          case Some(family) => decodeSupported(bytes, input, options, header, family)
        }
    }

  private def decodeSupported(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family
  ): Report =
    Preflight.check(bytes, kllType(options).map(_.size), frequencyType(options)) match {
      case Left(d) =>
        failure(input, Some(header), Some(info(family, None)), d, low = lowConfidence(bytes))
      case Right(checked) =>
        val c = Detector.detect(bytes, Some(checked)).fold(_ => Confidence.none, _.confidence)
        if (options.headerOnly) headerOnly(input, options, header, family, c, checked)
        else
          checked match {
            case Checked.Hll(_) =>
              HllAdapter
                .read(bytes)
                .fold(
                  d => failure(input, Some(header), Some(info(family, None)), d),
                  hll(input, header, family, c, _)
                )
            case Checked.Theta(_) =>
              ThetaAdapter
                .read(bytes)
                .fold(
                  d => failure(input, Some(header), Some(info(family, None)), d),
                  theta(input, options, header, family, c, _)
                )
            case Checked.Kll(facts)   => kll(bytes, input, options, header, family, c, facts)
            case Checked.Frequency(_) => frequency(bytes, input, options, header, family, c)
            case Checked.Quantiles(_) => quantiles(bytes, input, options, header, family, c)
          }
    }

  private def info(family: Family, layout: Option[String]): FamilyInfo = {
    // The Theta families are all "Theta"; the library's own names (Compact, Union, ...) alone would be unclear.
    val name = if (Family.isThetaFamily(family.id)) s"Theta ${family.name}" else family.name
    FamilyInfo(family.id, name, layout, family.supported)
  }

  private def mediumWarning(c: Confidence): List[Warning] =
    if (c.level == ConfidenceLevel.Medium) {
      val failed = c.evidence.filterNot(_.passed).map(e => s"${e.check} (${e.note})").mkString(", ")
      List(
        Warning(
          WarningCode.ConfidenceMedium,
          s"Confidence is medium: $failed.",
          Some("The values below were decoded anyway; treat them with care.")
        )
      )
    } else Nil

  // ---- HLL

  private def hll(
      input: InputInfo,
      header: HeaderInfo,
      family: Family,
      c: Confidence,
      d: HllData
  ): Report = {
    Report(
      input,
      Some(header),
      Some(info(family, Some(s"${d.width.label}, ${d.mode.label} mode"))),
      c,
      d.summary,
      None,
      Some(HllData.recoverability),
      mediumWarning(c),
      None
    )
  }

  // ---- Theta

  private def theta(
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence,
      d: ThetaData
  ): Report = {
    val contents =
      if (options.showHashes) {
        val hs = d.hashes()
        Some(Contents(Some(hs), None, hs.size))
      } else None
    Report(
      input,
      Some(header),
      Some(info(family, Some(d.shape))),
      c,
      d.summary,
      contents,
      Some(ThetaData.recoverability),
      seedWarnings(options, d) ++ mediumWarning(c),
      None
    )
  }

  /** The sketch's stored seed hash against the one the seed in use gives. An empty sketch has no
    * usable seed hash (Java writes 0), so it never warns. The decoded values do not depend on the
    * seed: the estimate is the retained entries over theta.
    */
  private def seedWarnings(options: InspectOptions, d: ThetaData): List[Warning] = {
    val seed = options.seed.getOrElse(ThetaAdapter.DefaultSeed)
    val expected = ThetaAdapter.seedHash(seed)
    val stored = d.seedHash & 0xffff
    if (d.empty || stored == expected) Nil
    else {
      val which =
        if (options.seed.isDefined) s"the seed $seed you gave"
        else s"the default seed $seed"
      List(
        Warning(
          WarningCode.SeedMismatch,
          s"The sketch's seed hash is $stored, but $which gives $expected, so it was probably built with a different seed. The values shown do not depend on the seed.",
          Some("If you know the seed it was built with, pass it with --seed <seed> to confirm.")
        )
      )
    }
  }

  // ---- KLL

  private def kll(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence,
      facts: KllFacts
  ): Report =
    options.itemTypeName.map(KllItemType.parse).collect { case Left(d) => d }.headOption match {
      case Some(d) => failure(input, Some(header), Some(info(family, None)), d)
      case None    => kllReport(bytes, input, options, header, family, c, facts)
    }

  private def kllReport(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence,
      facts: KllFacts
  ): Report =
    kllType(options) match {
      case None =>
        // Without a type we can still report what does not depend on it.
        val h = KllAdapter.header(facts)
        Report(
          input,
          Some(header),
          Some(info(family, Some(h.shape.label))),
          c,
          h.summary,
          None,
          Some(KllItemType.recoverability(known = false)),
          Nil,
          Some(KllItemType.needsType)
        )
      case Some(t) =>
        KllAdapter.read(bytes, t, options.ranks) match {
          case Left(d)  => failure(input, Some(header), Some(info(family, None)), d)
          case Right(d) =>
            val contents = if (options.showItems) {
              val items = d.items(); Some(Contents(None, Some(items), items.size))
            } else None
            Report(
              input,
              Some(header),
              Some(info(family, Some(s"${d.header.shape.label}, ${t.label} items"))),
              c,
              d.summary,
              contents,
              Some(KllItemType.recoverability(known = true)),
              mediumWarning(c),
              None
            )
        }
    }

  // ---- Quantiles

  private def quantiles(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence
  ): Report =
    QuantilesAdapter.read(bytes, options.ranks) match {
      case Left(d)  => failure(input, Some(header), Some(info(family, None)), d)
      case Right(d) =>
        val contents = if (options.showItems) {
          val items = d.items(); Some(Contents(None, Some(items), items.size))
        } else None
        Report(
          input,
          Some(header),
          Some(info(family, Some(d.header.shape.label))),
          c,
          d.summary,
          contents,
          Some(QuantilesData.recoverability),
          mediumWarning(c),
          None
        )
    }

  // ---- header only

  /** Identification and nothing else: the header, how sure we are, and what the family can hold. */
  private def headerOnly(
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence,
      checked: Checked
  ): Report = {
    val recoverability = checked match {
      case Checked.Hll(_)   => HllData.recoverability
      case Checked.Theta(_) => ThetaData.recoverability
      case Checked.Kll(_)   => KllItemType.recoverability(known = options.itemTypeName.isDefined)
      case Checked.Frequency(_) => FrequencyData.recoverability
      case Checked.Quantiles(_) => QuantilesData.recoverability
    }
    Report(
      input,
      Some(header),
      Some(info(family, None)),
      c,
      Nil,
      None,
      Some(recoverability),
      mediumWarning(c),
      None
    )
  }

  // ---- item type, as the user typed it

  private def kllType(options: InspectOptions): Option[KllItemType] =
    options.itemTypeName.flatMap(KllItemType.parse(_).toOption)

  private def frequencyType(options: InspectOptions): Option[FrequencyItemType] =
    options.itemTypeName.map(_.trim.toLowerCase).collect {
      case "long"   => FrequencyItemType.Int64
      case "string" => FrequencyItemType.Text
    }

  // ---- Frequency

  /** How many items a Frequency report lists unless asked for all. */
  val FrequencyTopItems = 20

  private def frequency(
      bytes: Array[Byte],
      input: InputInfo,
      options: InspectOptions,
      header: HeaderInfo,
      family: Family,
      c: Confidence
  ): Report =
    options.itemTypeName.filter(_ => frequencyType(options).isEmpty) match {
      case Some(name) =>
        failure(
          input,
          Some(header),
          Some(info(family, None)),
          Diagnosis(
            DiagnosisCode.Usage,
            s"A Frequency sketch holds long or string items; --type $name does not apply.",
            Some(
              "Use --type long or --type string, or leave --type out to let the blob's size decide."
            )
          )
        )
      case None =>
        FrequencyAdapter.read(bytes, frequencyType(options)) match {
          case Left(d)  => failure(input, Some(header), Some(info(family, None)), d)
          case Right(d) =>
            val all = d.rows()
            val shown = if (options.showItems) all else all.take(FrequencyTopItems)
            val chosen = options.itemTypeName.isEmpty
            val summary = d.summary.map {
              case ("itemType", Value.Text(t)) if chosen =>
                "itemType" -> Value.Text(s"$t (chosen from the blob's size)")
              case other => other
            }
            Report(
              input,
              Some(header),
              Some(info(family, d.itemType.map(t => s"${t.label} items"))),
              c,
              summary,
              Some(Contents(None, None, all.size, Some(shown))),
              Some(FrequencyData.recoverability),
              mediumWarning(c),
              None
            )
        }
    }

  // ---- failures

  private def failure(
      input: InputInfo,
      header: Option[HeaderInfo],
      family: Option[FamilyInfo],
      d: Diagnosis,
      rawFallback: Option[Array[Byte]] = None,
      low: Option[Confidence] = None
  ): Report = {
    val level = d.code match {
      case DiagnosisCode.NotASketch | DiagnosisCode.TooShort => ConfidenceLevel.NoConfidence
      case _                                                 => ConfidenceLevel.Medium
    }
    val hdr = header.orElse(rawFallback.map { b =>
      HeaderInfo(0, 0, PreambleUnit.Unknown, None, 0, 0, b.take(SketchHeader.PrefixBytes).toVector)
    })
    Report(input, hdr, family, low.getOrElse(Confidence(level, Nil)), Nil, None, None, Nil, Some(d))
  }

  /** A known family whose serial version or preamble size does not fit it: the header is all the
    * evidence there is, so confidence is low and the report shows the raw header fields (FR-010).
    */
  private def lowConfidence(bytes: Array[Byte]): Option[Confidence] =
    Detector
      .detect(bytes, None)
      .toOption
      .filter(d => d.family.isDefined && !d.confidence.evidence.take(3).forall(_.passed))
      .map(d => Confidence(ConfidenceLevel.Low, d.confidence.evidence))
}
