package sketchprobe

class CorpusFixturesSpec extends munit.FunSuite {
  test("the corpus loads and every manifest matches its blob") {
    val all = CorpusFixtures.all
    assert(all.nonEmpty, "no fixtures found")
    all.foreach { f =>
      assertEquals(f.header("lengthBytes").num.toInt, f.bytes.length, s"length of $f")
      assertEquals(f.manifest("name").str, f.name, s"name of $f")
      assertEquals(f.manifest("family").str, f.family, s"family of $f")
    }
  }

  test("the corpus covers all five supported sketch types and every writer") {
    assertEquals(
      CorpusFixtures.all.map(_.family).toSet,
      Set("hll", "theta", "kll", "frequency", "quantiles")
    )
    assertEquals(
      CorpusFixtures.all.map(_.writer).toSet,
      Set(
        "java-6.2.0",
        "java-9.0.0",
        "cpp-5.2.0",
        "java-8.0.0",
        "java-5.0.2",
        "java-3.3.0",
        "field"
      )
    )
  }
}
