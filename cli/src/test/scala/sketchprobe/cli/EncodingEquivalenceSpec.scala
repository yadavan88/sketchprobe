package sketchprobe.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import sketchprobe.{Variant, VariantFixtures, VariantSet}

/** The same sketch in every pasted form gives the same report, apart from the input line (spec
  * SC-004, quickstart Check 2).
  */
class EncodingEquivalenceSpec extends munit.FunSuite {
  private final case class Run(status: Int, out: String, err: String)

  private def run(set: VariantSet, v: Variant): Run = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val status =
      Cli.run(
        set.args.toArray,
        new ByteArrayInputStream(v.bytes),
        new PrintStream(out, true, UTF_8),
        new PrintStream(err, true, UTF_8)
      )
    Run(status, out.toString(UTF_8), err.toString(UTF_8))
  }

  private def withoutInputLine(s: String): String =
    s.linesIterator.filterNot(_.startsWith("Input ")).mkString("\n")

  private def inputLine(s: String): String =
    s.linesIterator.find(_.startsWith("Input ")).getOrElse("")

  VariantFixtures.all.foreach { set =>
    test(s"${set.name}: every variant prints the same report as the plain base64 one") {
      val baseline = run(set, set.variant("base64.txt"))
      assertEquals(baseline.status, 0, baseline.err)
      set.variants.foreach { v =>
        val r = run(set, v)
        assertEquals(r.status, 0, s"${v.file}: ${r.err}")
        assertEquals(withoutInputLine(r.out), withoutInputLine(baseline.out), v.file)
        assertEquals(r.err, "", v.file)
      }
    }

    test(s"${set.name}: the input line names the format each variant was read as") {
      set.variants.foreach { v =>
        val line = inputLine(run(set, v).out)
        assert(line.contains(v.format), s"${v.file}: '$line' should mention ${v.format}")
      }
    }
  }
}
