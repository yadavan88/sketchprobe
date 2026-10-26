package sketchprobe.cli

import sketchprobe.{CorpusFixtures, Fixture => CorpusFixture}
import sketchprobe.cli.CliHarness.{corpus, normalize, run}

/** Every fact the human report shows is also in the JSON, with the same meaning (spec FR-022). The
  * human report is read back row by row, so a fact added to one output and not the other fails
  * here.
  */
class ParitySpec extends munit.FunSuite {
  private val LabelWidth = 18
  private val labelToKey: Map[String, String] = HumanRenderer.labels.map(_.swap)
  private val Number = raw"(-?[0-9][0-9,]*(?:\.[0-9]+)?)".r

  private def number(text: String): Option[(Double, Int)] =
    Number.findPrefixOf(text.trim).map { t =>
      val plain = t.replace(",", "")
      (plain.toDouble, plain.indexOf('.') match { case -1 => 0; case i => plain.length - i - 1 })
    }

  /** The `label  value` rows of the summary block: from the Input row to the first blank line, with
    * wrapped values joined. (The title, the lists, the recoverability sentence and the warnings are
    * read separately.)
    */
  private def rows(text: String): Vector[(String, String)] =
    text.linesIterator
      .dropWhile(!_.startsWith("Input"))
      .takeWhile(_.trim.nonEmpty)
      .foldLeft(Vector.empty[(String, String)]) { (acc, line) =>
        if (line.startsWith(" " * LabelWidth) && acc.nonEmpty)
          acc.init :+ (acc.last._1 -> (acc.last._2 + " " + line.trim))
        else acc :+ (line.take(LabelWidth).trim -> line.drop(LabelWidth).trim)
      }

  private def close(human: Double, decimals: Int, json: Double): Boolean =
    math.abs(human - json) <= 0.5 * math.pow(10, -decimals) + 1e-9 * math.abs(json)

  private def sameValue(label: String, human: String, json: ujson.Value): Unit = json match {
    case ujson.Num(j) =>
      val (h, d) = number(human).getOrElse(fail(s"$label: '$human' is not a number"))
      assert(close(h, d, j), s"$label: human '$human' vs JSON $j")
    case ujson.Str(s)  => assert(human.startsWith(s), s"$label: human '$human' vs JSON '$s'")
    case ujson.Bool(b) => assertEquals(human, if (b) "yes" else "no", label)
    case ujson.Null    => ()
    case other         => fail(s"$label: unexpected JSON $other")
  }

  private def checkSuccess(label: String, bytes: Array[Byte], args: Seq[String]): Unit = {
    val h = run(bytes, args*)
    val j = run(bytes, ("--json" +: args)*)
    assertEquals(h.status, 0, s"$label: ${h.err}")
    assertEquals(j.status, 0, label)
    val json = j.json
    val lines = h.out.linesIterator.toVector

    // the title: family, layout, confidence
    val fam = json("family")
    val name = fam("name").str
    val title = lines.head
    val expectedTitle = fam.obj.get("layout").fold(name)(l => s"$name sketch (${l.str})")
    assert(title.startsWith(expectedTitle), s"$label: '$title' vs '$expectedTitle'")
    assert(title.endsWith("confidence: " + json("confidence")("level").str), s"$label: $title")

    // the input row
    val input = rows(h.out).find(_._1 == "Input").map(_._2).getOrElse(fail(s"$label: no Input row"))
    assert(input.startsWith(json("input")("format").str), s"$label: $input")
    assertEquals(
      number(input.dropWhile(_ != ',').drop(1)).map(_._1.toLong),
      Some(json("input")("bytes").num.toLong),
      label
    )

    // every labelled row is a summary fact with the same value
    val summary = json("summary")
    rows(h.out).foreach { case (l, v) =>
      if (l == "Input" || l == "Recoverability") ()
      else if (l.startsWith("Bounds, ")) {
        val sd = l.stripPrefix("Bounds, ").takeWhile(_.isDigit)
        val Array(lo, hi) = v.split(" to ", 2)
        sameValue(s"$label lowerBound$sd", lo, summary(s"lowerBound$sd"))
        sameValue(s"$label upperBound$sd", hi, summary(s"upperBound$sd"))
      } else {
        val key = labelToKey.getOrElse(l, fail(s"$label: the human row '$l' has no JSON key"))
        sameValue(
          s"$label $key",
          v,
          summary.obj.getOrElse(key, fail(s"$label: JSON summary has no '$key'"))
        )
      }
    }

    // quantiles
    lines.filter(_.startsWith("  rank ")).foreach { line =>
      val rest = line.stripPrefix("  rank ")
      val rank = rest.takeWhile(c => c.isDigit || c == '.').toDouble
      val value = rest.dropWhile(c => c.isDigit || c == '.').trim
      val q = summary("quantiles").arr
        .find(x => math.abs(x("rank").num - rank) < 1e-9)
        .getOrElse(fail(s"$label: JSON has no quantile at rank $rank"))
      sameValue(s"$label rank $rank", value, q("value"))
    }

    // the Frequency table
    json.obj.get("contents").flatMap(_.obj.get("frequentItems")).filter(_.arr.nonEmpty).foreach {
      items =>
        val from = lines.dropWhile(!_.startsWith("Items, largest first"))
        val columns =
          from(1).trim.split("\\s+").length - 1 // the header row: Item, then one or three counts
        val table = from.drop(2).takeWhile(l => l.startsWith("  ") && !l.startsWith("  ..."))
        assert(table.nonEmpty, s"$label: no item rows")
        val row = s"^(.*?)((?:\\s{2,}[0-9,]+){$columns})$$".r
        table.zip(items.arr).foreach { case (line, item) =>
          val (text, counts) = line.trim match {
            case row(t, c) => (t.trim, c)
            case other     => fail(s"$label: cannot read '$other'")
          }
          val shown = counts.trim.split("\\s+").map(_.replace(",", "").toLong).toVector
          val key = item("item") match {
            case ujson.Str(s) => s
            case ujson.Num(n) => n.toLong.toString
            case o            => o.toString
          }
          if (text.endsWith("..."))
            assert(key.startsWith(text.stripSuffix("...")), s"$label: '$text' vs '$key'")
          else if (text == "(empty string)") assertEquals(key, "", label)
          else assertEquals(text, key, s"$label item")
          val expected =
            if (shown.size == 1) Vector(item("estimate").num.toLong)
            else Vector(item("estimate"), item("lowerBound"), item("upperBound")).map(_.num.toLong)
          assertEquals(shown, expected, s"$label counts of '$text'")
        }
    }

    // the recoverability statement
    val rec = h.out.linesIterator
      .dropWhile(!_.startsWith("Recoverability"))
      .takeWhile(_.trim.nonEmpty)
      .mkString(" ")
    assertEquals(
      normalize(rec),
      "Recoverability " + normalize(json("recoverability")("statement").str),
      label
    )

    // warnings: each human warning is a JSON warning, message and next step
    val jsonWarnings = json("warnings").arr.map(w =>
      normalize(w("message").str + w.obj.get("nextStep").fold("")(" " + _.str))
    )
    val humanWarnings = normalize(
      h.out.linesIterator
        .dropWhile(l => !l.startsWith("Warnings") && !l.startsWith("!!"))
        .mkString(" ")
    )
    jsonWarnings.foreach(w =>
      assert(
        humanWarnings.contains(w.stripPrefix("!! ")),
        s"$label: warning '$w' not in the human report"
      )
    )
    assertEquals(
      h.out.linesIterator.count(l =>
        l.startsWith("  ") && l.drop(2).headOption.exists(_.isDigit) && l.contains(". ")
      ),
      jsonWarnings.count(!_.startsWith("Confidence is medium")),
      s"$label: warning count"
    )
  }

  private def fixture(name: String): CorpusFixture =
    CorpusFixtures.family(name.takeWhile(_ != '_')).find(_.name == name).get
  private def typed(f: CorpusFixture) = Seq("--type", f.params("itemType").str)

  private val named = Seq(
    "hll_HLL_8_k12_n1000_compact",
    "hll_HLL_4_k12_n100000_compact",
    "hll_HLL_6_k12_n5_compact",
    "theta_compact_ordered_n5000",
    "theta_compact_ordered_n100",
    "theta_quickselect_n5000",
    "theta_compressed_ordered_n1000",
    "theta_union_state_n5000"
  )

  named.foreach { n =>
    test(s"$n: the human report's facts are in the JSON") {
      val f = CorpusFixtures
        .family(n.takeWhile(_ != '_'))
        .filter(_.writer == "java-9.0.0")
        .find(_.name == n)
        .get
      checkSuccess(n, f.bytes, if (f.family == "theta") Seq("--show-hashes") else Nil)
    }
  }

  Seq(
    "kll_float_k200_n10000",
    "kll_double_k100_n10000_updatable",
    "kll_long_k200_n10000",
    "kll_float_k200_n1",
    "kll_double_k200_n0_updatable"
  ).foreach { n =>
    test(s"$n: the human report's facts are in the JSON, with items listed") {
      val f = CorpusFixtures
        .family("kll")
        .filter(_.writer == "java-9.0.0")
        .find(_.name == n)
        .orElse(CorpusFixtures.family("kll").find(_.name == n))
        .getOrElse(fail(s"no fixture $n"))
      checkSuccess(n, f.bytes, typed(f) :+ "--show-items")
    }
  }

  Seq(
    "frequency_strings_platforms",
    "freq_string_m64_exact7",
    "freq_long_m64_purged",
    "freq_long_m64_exact40",
    "freq_string_m8_empty"
  ).foreach { n =>
    test(s"$n: the human report's facts are in the JSON") {
      val f = CorpusFixtures.family("frequency").find(_.name == n).get
      checkSuccess(n, f.bytes, Nil)
    }
  }

  test("a Theta seed warning and a medium-confidence warning are in both, with the same words") {
    val t =
      CorpusFixtures.family("theta").find(_.name == "theta_compact_ordered_n5000_seed12345").get
    checkSuccess("seed", t.bytes, Nil)
    val hll = corpus("hll", "hll_HLL_8_k12_n1000_compact")
    checkSuccess("medium", hll.bytes ++ Array.fill[Byte](4)(0), Nil)
  }

  test("a failure has the same reason, next step and header bytes in both") {
    val hll = corpus("hll", "hll_HLL_8_k12_n1000_compact").bytes
    Seq(
      hll.take(30),
      hll.updated(1, 9.toByte),
      Array.tabulate[Byte](40)(i => if (i == 2) 99 else (i + 3).toByte),
      Array[Byte](1, 2, 3)
    ).foreach { b =>
      val h = run(b)
      val json = run(b, "--json").json
      val diag = json("diagnosis")
      val text = normalize(h.err)
      assert(text.contains(normalize(diag("reason").str)), s"reason: ${h.err}")
      diag.obj
        .get("nextStep")
        .foreach(n => assert(text.contains(normalize(n.str)), s"next step: ${h.err}"))
      val hex = json.obj
        .get("header")
        .map(_("rawPrefixHex").str)
        .orElse(diag.obj.get("rawPrefixHex").map(_.str))
        .getOrElse("")
      val shown = h.err.linesIterator
        .dropWhile(!_.contains("header bytes"))
        .drop(1)
        .map(_.trim.replace(" ", ""))
        .mkString
      assertEquals(shown, hex, "header bytes")
    }
  }
}
