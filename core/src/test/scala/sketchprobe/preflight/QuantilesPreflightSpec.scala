package sketchprobe.preflight

import sketchprobe.CorpusFixtures

class QuantilesPreflightSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("quantiles")
  private def blob(name: String): Array[Byte] = fixtures.find(_.name == name).get.bytes.clone()

  private def label(b: Array[Byte]): Either[String, QuantilesFacts] =
    QuantilesPreflight.check(b).left.map(_.code.label)

  test(
    "every corpus blob is accepted with the form its flags say and the retained count the library reports"
  ) {
    fixtures.foreach { f =>
      val facts = QuantilesPreflight.check(f.bytes).fold(d => fail(s"$f: ${d.reason}"), identity)
      assertEquals(facts.n, f.expected("n").num.toLong, s"$f")
      assertEquals(facts.retained, f.expected("retained").num.toLong, s"$f")
      val form =
        if (f.expected("empty").bool) QuantilesForm.Empty
        else if (f.params("layout").str == "compact") QuantilesForm.Compact
        else QuantilesForm.Updatable
      assertEquals(facts.form, form, s"$f")
    }
  }

  test("serial versions 1 and 2 are recognized as legacy layouts, not decoded") {
    Seq(1, 2).foreach { v =>
      val b = blob("quantiles_k128_n10000_compact")
      b(1) = v.toByte
      assertEquals(label(b), Left("unsupported-layout"), s"serVer $v")
    }
  }

  test("an unknown serial version is not a sketch") {
    val b = blob("quantiles_k128_n10000_compact")
    b(1) = 9
    assertEquals(label(b), Left("not-a-sketch"))
  }

  test("trailing bytes are accepted: the layout needs at least its own length") {
    val b = blob("quantiles_k128_n10000_compact") ++ Array.fill[Byte](16)(0)
    val facts = label(b).toOption.get
    assert(facts.length.complete)
    assert(!facts.length.matches)
  }

  test("min above max is a soft issue, and the blob is still accepted") {
    val b = blob("quantiles_k128_n10_compact")
    // swap min (16-23) and max (24-31)
    val min = b.slice(16, 24); val max = b.slice(24, 32)
    max.copyToArray(b, 16); min.copyToArray(b, 24)
    assertEquals(label(b).toOption.get.softIssues.size, 1)
  }

  test("fewer than 8 bytes is too short") {
    assertEquals(label(Array[Byte](1, 3, 8)), Left("too-short"))
  }
}
