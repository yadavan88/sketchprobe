package sketchprobe.cli

import sketchprobe.report._

/** The human-readable report: at most 80 columns, in the order contracts/cli.md describes. */
object HumanRenderer {
  val Width = 80
  private val LabelWidth = 18
  private val ListCap = 100

  /** Row labels of the summary, by summary key. Visible to the parity spec, which relates every
    * human row to its JSON key.
    */
  private[cli] val labels: Map[String, String] = Map(
    "estimate" -> "Estimate",
    "lgK" -> "lgK",
    "mode" -> "Mode",
    "registerWidth" -> "Register width",
    "empty" -> "Empty",
    "outOfOrder" -> "Out of order",
    "theta" -> "Theta",
    "retainedEntries" -> "Retained entries",
    "estimationMode" -> "Estimation mode",
    "ordered" -> "Ordered",
    "seedHash" -> "Seed hash",
    "lgNomLongs" -> "lgNomLongs",
    "n" -> "Items seen (n)",
    "k" -> "k",
    "m" -> "m",
    "numLevels" -> "Levels",
    "retainedItems" -> "Retained items",
    "itemType" -> "Item type",
    "min" -> "Min",
    "max" -> "Max",
    "streamLength" -> "Stream length",
    "numActive" -> "Items tracked",
    "maximumError" -> "Maximum error"
  )

  // ---- number formatting

  /** Fixed-point formatting that never depends on the user's locale (a German locale would print
    * 996,18).
    */
  private def fixed(d: Double, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%." + decimals + "f", Double.box(d))

  private def trimZeros(s: String): String =
    if (s.contains('.')) s.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse else s

  private def group(s: String): String = {
    val (intPart, frac) = s.span(_ != '.')
    val neg = intPart.startsWith("-")
    val digits = if (neg) intPart.drop(1) else intPart
    val grouped = digits.reverse.grouped(3).mkString(",").reverse
    (if (neg) "-" else "") + grouped + frac
  }

  private def num(d: Double, decimals: Int): String =
    if (d.isNaN) "n/a"
    else if (d.isInfinity) (if (d > 0) "infinity" else "-infinity")
    else group(trimZeros(fixed(d, decimals)))

  private def valueText(key: String, v: Value): String = v match {
    case Value.Num(d) =>
      if (key == "theta") trimZeros(fixed(d, 8)) else num(d, if (key == "estimate") 2 else 6)
    case Value.Int64(l)  => if (key == "seedHash") l.toString else group(l.toString)
    case Value.Text(s)   => s
    case Value.Flag(b)   => if (b) "yes" else "no"
    case Value.Pairs(ps) =>
      ps.map { case (r, x) => s"${trimZeros(fixed(r, 4))}: ${valueText("q", x)}" }.mkString(", ")
  }

  // ---- layout helpers

  private def wrap(text: String, width: Int, indent: String, first: String): List[String] = {
    val words = text.split("\\s+").toList.filter(_.nonEmpty)
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    var cur = first
    var empty = true
    words.foreach { w =>
      if (!empty && cur.length + 1 + w.length > width) {
        lines += cur
        cur = indent + w
      } else {
        cur = if (empty) cur + w else cur + " " + w
      }
      empty = false
    }
    lines += cur
    lines.toList
  }

  private def row(label: String, value: String): List[String] =
    wrap(value, Width, " " * LabelWidth, label.padTo(LabelWidth, ' '))

  private def boundsRows(summary: Map[String, Value]): List[String] =
    (1 to 3).toList.flatMap { sd =>
      (summary.get(s"lowerBound$sd"), summary.get(s"upperBound$sd")) match {
        case (Some(Value.Num(lo)), Some(Value.Num(hi))) =>
          row(s"Bounds, $sd sd", s"${num(lo, 2)} to ${num(hi, 2)}")
        case _ => Nil
      }
    }

  // ---- success

  def render(report: Report): String = {
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    val family = report.family
      .map(f => f.layout.fold(f.name)(l => s"${f.name} sketch ($l)"))
      .getOrElse("Unknown input")
    val conf = s"confidence: ${report.confidence.level.label}"
    lines += family + " " * math.max(2, Width - family.length - conf.length) + conf
    lines += ""
    report.warnings.filter(_.code == WarningCode.ConfidenceMedium).foreach { w =>
      lines ++= wrap(w.message + w.nextStep.fold("")(" " + _), Width, "   ", "!! ")
      lines += ""
    }
    val summary = report.summary.toMap
    val inputText = s"${report.input.format.label}${
        if (report.input.source == FormatSource.Forced) " (forced)" else ""
      }, " +
      s"${group(report.input.bytes.toString)} bytes"
    lines ++= row("Input", inputText)
    // Header only: identification is the whole report, so the header fields are the content.
    if (report.summary.isEmpty && report.diagnosis.isEmpty) report.header.foreach { h =>
      lines ++= row(
        "Header",
        s"preamble size ${h.preambleSize} ${h.preambleUnit.label}, serial version ${h.serVer}, family ${h.familyId}"
      )
      lines ++= row("Header bytes", h.rawPrefixHex.grouped(16).mkString("  "))
    }
    report.input.alternatives
      .filter(_.identified)
      .foreach(a => lines ++= row("", s"also readable as ${a.format.label}"))
    lines ++= row("Estimate", summary.get("estimate").fold("")(valueText("estimate", _))).filter(
      _ => summary.contains("estimate")
    )
    lines ++= boundsRows(summary)
    val skip = Set("estimate", "quantiles") ++ (1 to 3).flatMap(sd =>
      List(s"lowerBound$sd", s"upperBound$sd")
    )
    report.summary.filterNot(kv => skip.contains(kv._1)).foreach {
      case ("numActive", Value.Int64(n)) =>
        val cap = summary.get("maxMapCapacity").collect { case Value.Int64(c) => c }
        lines ++= row(
          "Items tracked",
          group(n.toString) + cap.fold("")(c => s" (the table holds up to ${group(c.toString)})")
        )
      case ("maxMapSize" | "maxMapCapacity", _) => ()
      case ("maximumError", Value.Int64(e))     =>
        lines ++= row(
          "Maximum error",
          if (e == 0) "0 (every count is exact)"
          else s"${group(e.toString)} (a true count is within this of its estimate)"
        )
      case (k, v) => lines ++= row(labels.getOrElse(k, k), valueText(k, v))
    }
    summary.get("quantiles").foreach {
      case Value.Pairs(ps) =>
        lines += ""
        lines += "Quantiles"
        ps.foreach { case (r, v) =>
          lines += "  " + s"rank ${trimZeros(fixed(r, 4))}".padTo(14, ' ') + valueText("q", v)
        }
      case _ => ()
    }
    lines ++= contents(report)
    report.recoverability.foreach { r =>
      lines += ""
      lines ++= wrap(r.statement, Width, " " * LabelWidth, "Recoverability".padTo(LabelWidth, ' '))
    }
    val other = report.warnings.filterNot(_.code == WarningCode.ConfidenceMedium)
    if (other.nonEmpty) {
      lines += ""
      lines += "Warnings"
      other.zipWithIndex.foreach { case (w, i) =>
        lines ++= wrap(w.message + w.nextStep.fold("")(" " + _), Width, "     ", s"  ${i + 1}. ")
      }
    }
    lines.mkString("\n")
  }

  private def contents(report: Report): List[String] = {
    val total = report.summary.toMap
    // Without a decoded sketch (for example a KLL blob missing its item type) there is nothing to list.
    if (report.diagnosis.isDefined) Nil
    else
      (report.contents, total.get("retainedEntries"), total.get("retainedItems")) match {
        case (Some(c), _, _) if c.frequentItems.isDefined =>
          frequentTable(c, total.get("maximumError").contains(Value.Int64(0L)))
        case (Some(c), _, _) if c.retainedHashes.isDefined =>
          val hs = c.retainedHashes.get
          "" :: s"Retained hashes (${group(c.total.toString)})" ::
            hs.take(ListCap).map(h => "  " + java.lang.Long.toUnsignedString(h)).toList ++ more(
              hs.size
            )
        case (Some(c), _, _) if c.retainedItems.isDefined =>
          val xs = c.retainedItems.get
          "" :: s"Retained items (${group(c.total.toString)})" :: xs
            .take(ListCap)
            .map(v => "  " + valueText("item", v))
            .toList ++ more(xs.size)
        case (None, Some(Value.Int64(n)), _) =>
          List("", s"Retained hashes: ${group(n.toString)}. Use --show-hashes to list them.")
        case (None, _, Some(Value.Int64(n))) =>
          List("", s"Retained items: ${group(n.toString)}. Use --show-items to list them.")
        case _ => Nil
      }
  }

  /** An item as text on one line: control characters shown as escapes, and long items cut. */
  private def itemText(v: Value, width: Int): String = {
    val raw = v match {
      case Value.Text(s)  => if (s.isEmpty) "(empty string)" else s
      case Value.Int64(l) => l.toString
      case other          => valueText("item", other)
    }
    val clean = raw.flatMap {
      case '\n'                             => "\\n"
      case '\t'                             => "\\t"
      case '\r'                             => "\\r"
      case ch if Character.isISOControl(ch) => "?"
      case ch                               => ch.toString
    }
    if (clean.length <= width) clean else clean.take(math.max(1, width - 3)) + "..."
  }

  /** The Frequency items, largest first. An exact sketch (maximum error 0) shows one count; an
    * inexact one shows the estimate and both bounds.
    */
  private def frequentTable(c: Contents, exact: Boolean): List[String] = {
    val items = c.frequentItems.getOrElse(Vector.empty)
    if (items.isEmpty) Nil
    else {
      val shown = items.take(ListCap)
      val heads = if (exact) List("Count") else List("Estimate", "Lower", "Upper")
      val cells = shown.map { i =>
        if (exact) List(group(i.estimate.toString))
        else List(i.estimate, i.lowerBound, i.upperBound).map(n => group(n.toString))
      }
      val widths = heads.indices.map(j => (heads(j) +: cells.map(_(j))).map(_.length).max)
      val numeric = widths.map(_ + 2).sum
      val itemWidth = math.max(8, math.min(40, Width - 2 - numeric))
      val texts = shown.map(i => itemText(i.item, itemWidth))
      val itemCol = math.max("Item".length, texts.map(_.length).max)
      def line(item: String, nums: List[String]): String =
        "  " + item.padTo(itemCol, ' ') + nums
          .zip(widths)
          .map { case (n, w) => "  " + " " * (w - n.length) + n }
          .mkString
      val title =
        if (c.total > items.size)
          s"Items, largest first (top ${items.size} of ${group(c.total.toString)})"
        else s"Items, largest first (${group(c.total.toString)})"
      val footer =
        if (c.total > shown.size)
          List(
            s"  ... and ${group((c.total - shown.size).toString)} more (" +
              (if (items.size > ListCap) "use --json for all)" else "use --show-items to list all)")
          )
        else Nil
      "" :: title :: line("Item", heads) :: shown.indices.toList.map(k =>
        line(texts(k), cells(k))
      ) ++ footer
    }
  }

  private def more(total: Int): List[String] =
    if (total > ListCap)
      List(s"  ... and ${group((total - ListCap).toString)} more (use --json for all)")
    else Nil

  // ---- failure (printed to standard error)

  def renderFailure(report: Report): String = {
    val d = report.diagnosis.getOrElse(Diagnosis(DiagnosisCode.NotASketch, "unknown error", None))
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    lines ++= wrap(d.reason, Width, "  ", "sketchprobe: ")
    d.nextStep.foreach(n => lines ++= wrap(n, Width, "              ", "  next step:  "))
    // Low confidence: the header is all the evidence there is, so its fields are shown by name (FR-010).
    if (report.confidence.level == ConfidenceLevel.Low) {
      lines += "  confidence: low"
      report.header.foreach { h =>
        val fam = report.family.fold("")(f => s" (${f.name})")
        lines ++= wrap(
          s"${h.preambleSize} ${h.preambleUnit.label}, serial version ${h.serVer}, family ${h.familyId}$fam",
          Width,
          "                ",
          "  header:       preamble size "
        )
      }
    }
    report.header.filter(_.rawPrefix.nonEmpty).foreach { h =>
      val hex = h.rawPrefix.map(b => f"${b & 0xff}%02x").grouped(8).map(_.mkString(" ")).toList
      lines += "  header bytes (first " + h.rawPrefix.length + "):"
      hex.foreach(l => lines += "    " + l)
    }
    lines.mkString("\n")
  }
}
