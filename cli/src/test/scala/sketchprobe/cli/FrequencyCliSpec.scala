package sketchprobe.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import sketchprobe.CorpusFixtures

/** Frequency sketches through the command line: the item type is chosen from the blob's size or
  * given with `--type`, and a type that does not fit is refused as the user's to fix (spec FR-026,
  * FR-027).
  */
class FrequencyCliSpec extends munit.FunSuite {
  private final case class Run(status: Int, out: String, err: String)

  private def run(bytes: Array[Byte], args: String*): Run = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val status = Cli.run(
      args.toArray,
      new ByteArrayInputStream(bytes),
      new PrintStream(out, true, UTF_8),
      new PrintStream(err, true, UTF_8)
    )
    Run(status, out.toString(UTF_8), err.toString(UTF_8))
  }

  private def blob(name: String): Array[Byte] =
    CorpusFixtures.family("frequency").find(_.name == name).get.bytes

  private val platforms = blob("frequency_strings_platforms")
  private val longs = blob("freq_long_m64_exact7")

  test("the type is chosen from the blob's size, and the report says so") {
    val r = run(platforms)
    assertEquals(r.status, 0, r.err)
    assert(r.out.contains("Item type         string (chosen from the blob's size)"), r.out)
    assert(r.out.contains("Frequency sketch (string items)"), r.out)
  }

  test("a given type that fits is used and not described as chosen") {
    val r = run(platforms, "--type", "string")
    assertEquals(r.status, 0, r.err)
    assert(r.out.contains("Item type         string\n"), r.out)
    assert(!r.out.contains("chosen"), r.out)
  }

  test("the top 20 are listed by default, with the number left out; --show-items lists all 29") {
    val top = run(platforms)
    assert(top.out.contains("top 20 of 29"), top.out)
    assert(top.out.contains("... and 9 more (use --show-items to list all)"), top.out)
    val all = run(platforms, "--show-items")
    assert(all.out.contains("Items, largest first (29)"), all.out)
    assert(all.out.contains("android-17"), all.out)
    assert(!all.out.contains("more ("), all.out)
  }

  test("the wrong type is refused as needs-item-type (status 3), naming the right one") {
    val r = run(platforms, "--type", "long")
    assertEquals(r.status, 3)
    assert(r.err.contains("--type string"), r.err)
    assertEquals(r.out, "")
  }

  test("a type that is not a Frequency type is a usage error (status 64)") {
    val r = run(longs, "--type", "double")
    assertEquals(r.status, 64)
    assert(r.err.contains("long or string"), r.err)
  }

  test("an unknown type name is a usage error, whatever the sketch") {
    assertEquals(run(longs, "--type", "bogus").status, 64)
  }

  test("--type string on a KLL sketch is still recognized but unsupported (status 3)") {
    val kll = CorpusFixtures.family("kll").find(_.name == "kll_double_k100_n10000_updatable").get
    val r = run(kll.bytes, "--type", "string")
    assertEquals(r.status, 3)
    assert(r.err.contains("KLL sketches of text items"), r.err)
  }

  test(
    "trailing bytes: refused without a type, read with medium confidence when the type is given"
  ) {
    val padded = longs ++ Array.fill[Byte](5)('x'.toByte)
    val without = run(padded)
    assertEquals(without.status, 3)
    assert(without.err.contains("--type long or --type string"), without.err)
    val typed = run(padded, "--type", "long")
    assertEquals(typed.status, 0, typed.err)
    assert(typed.out.contains("confidence: medium"), typed.out)
  }

  test("an empty sketch reads as empty, with no item type to choose") {
    val r = run(blob("freq_long_m8_empty"))
    assertEquals(r.status, 0, r.err)
    assert(r.out.contains("Items tracked     0"), r.out)
    assert(!r.out.contains("Item type"), r.out)
  }

  test(
    "items are shown safely: non-ASCII kept, an empty string named, a long one cut, no line over 80 columns"
  ) {
    val r = run(blob("freq_string_m64_exact7"), "--show-items")
    assert(r.out.contains("naïve-日本語-✓"), r.out)
    assert(r.out.contains("(empty string)"), r.out)
    assert(r.out.contains("xxx..."), r.out)
    assert(!r.out.contains("x" * 100), "the 300-character item must be cut")
    assert(r.out.linesIterator.forall(_.length <= 80), r.out)
  }

  test("a purged sketch shows the estimate and both bounds, and says how far counts can be off") {
    val r = run(blob("freq_long_m64_purged"))
    assert(r.out.contains("Estimate  Lower  Upper"), r.out)
    assert(r.out.contains("Maximum error     386"), r.out)
  }
}
