package sketchprobe.cli

import sketchprobe.report._

/** The report as one line of JSON, as docs/report.schema.json describes it. Written by hand rather
  * than through a JSON library's value type, because that type holds numbers as doubles and the
  * counts here (stream lengths, KLL n) are 64-bit integers that must stay exact. 64-bit hashes are
  * strings.
  */
object JsonRenderer {
  val SchemaVersion = 1

  private def str(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.append('"').toString
  }

  private def num(d: Double): String =
    if (d.isNaN || d.isInfinite) "null" else java.lang.Double.toString(d)

  private def obj(fields: (String, String)*): String =
    fields.map { case (k, v) => str(k) + ":" + v }.mkString("{", ",", "}")

  private def arr(items: Iterable[String]): String = items.mkString("[", ",", "]")

  private def opt(key: String, value: Option[String]): Seq[(String, String)] =
    value.toSeq.map(key -> _)

  private def value(v: Value): String = v match {
    case Value.Num(d)    => num(d)
    case Value.Int64(l)  => l.toString
    case Value.Text(s)   => str(s)
    case Value.Flag(b)   => b.toString
    case Value.Pairs(ps) =>
      arr(ps.map { case (rank, x) => obj("rank" -> num(rank), "value" -> value(x)) })
  }

  private def hex(b: Vector[Byte]): String = str(b.map(x => f"${x & 0xff}%02x").mkString)

  private def input(i: InputInfo): String =
    obj(
      Seq(
        "format" -> str(i.format.label),
        "formatSource" -> str(i.source.label),
        "bytes" -> i.bytes.toString
      ) ++
        (if (i.alternatives.isEmpty) Nil
         else
           Seq(
             "alternatives" -> arr(
               i.alternatives.map(a =>
                 obj("format" -> str(a.format.label), "identified" -> a.identified.toString)
               )
             )
           )) ++
        (if (i.notes.isEmpty) Nil else Seq("notes" -> arr(i.notes.map(str))))*
    )

  private def header(h: HeaderInfo): String =
    obj(
      Seq(
        "byte0" -> h.byte0.toString,
        "preambleSize" -> h.preambleSize.toString,
        "preambleUnit" -> str(h.preambleUnit.label)
      ) ++ opt("resizeFactor", h.resizeFactor.map(_.toString)) ++ Seq(
        "serVer" -> h.serVer.toString,
        "familyId" -> h.familyId.toString,
        "rawPrefixHex" -> hex(h.rawPrefix)
      )*
    )

  private def family(f: FamilyInfo): String =
    obj(
      Seq("id" -> f.id.toString, "name" -> str(f.name)) ++ opt("layout", f.layout.map(str)) ++
        Seq("supported" -> f.supported.toString)*
    )

  private def confidence(c: Confidence): String =
    obj(
      "level" -> str(c.level.label),
      "evidence" -> arr(
        c.evidence.map(e =>
          obj(
            Seq("check" -> str(e.check), "passed" -> e.passed.toString) ++
              (if (e.note.isEmpty) Nil else Seq("note" -> str(e.note)))*
          )
        )
      )
    )

  private def contents(c: Contents): String =
    obj(
      opt(
        "retainedHashes",
        c.retainedHashes.map(hs => arr(hs.map(h => str(java.lang.Long.toUnsignedString(h)))))
      ) ++
        opt("retainedItems", c.retainedItems.map(xs => arr(xs.map(value)))) ++
        opt(
          "frequentItems",
          c.frequentItems.map(xs =>
            arr(
              xs.map(i =>
                obj(
                  "item" -> value(i.item),
                  "estimate" -> i.estimate.toString,
                  "lowerBound" -> i.lowerBound.toString,
                  "upperBound" -> i.upperBound.toString
                )
              )
            )
          )
        ) ++ Seq("total" -> c.total.toString)*
    )

  private def warning(w: Warning): String =
    obj(
      Seq("code" -> str(w.code.label), "message" -> str(w.message)) ++ opt(
        "nextStep",
        w.nextStep.map(str)
      )*
    )

  private def diagnosis(d: Diagnosis, rawPrefix: Option[Vector[Byte]]): String =
    obj(
      Seq("code" -> str(d.code.label), "reason" -> str(d.reason)) ++ opt(
        "nextStep",
        d.nextStep.map(str)
      ) ++
        opt("rawPrefixHex", rawPrefix.map(hex))*
    )

  /** The one line printed for `--json`, without the trailing newline. */
  def render(r: Report): String = {
    // Fewer than 8 bytes have no header to speak of; the bytes go in the diagnosis instead.
    val hasHeader = r.input.bytes >= 8
    obj(
      Seq(
        "schemaVersion" -> SchemaVersion.toString,
        "status" -> r.status.toString,
        "input" -> input(r.input)
      ) ++ opt("header", r.header.filter(_ => hasHeader).map(header)) ++
        opt("family", r.family.map(family)) ++ Seq(
          "confidence" -> confidence(r.confidence),
          "summary" -> obj(r.summary.map { case (k, v) => k -> value(v) }*)
        ) ++ opt("contents", r.contents.map(contents)) ++
        opt(
          "recoverability",
          r.recoverability.map(x =>
            obj("kind" -> str(x.kind.label), "statement" -> str(x.statement))
          )
        ) ++ Seq("warnings" -> arr(r.warnings.map(warning))) ++
        opt(
          "diagnosis",
          r.diagnosis.map(d => diagnosis(d, r.header.filter(_ => !hasHeader).map(_.rawPrefix)))
        )*
    )
  }
}
