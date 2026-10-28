package sketchprobe

class ForgedFixturesSpec extends munit.FunSuite {
  test(
    "the set has the 23 forged and 52 truncated blobs from Spike B, plus the Frequency and Quantiles ones"
  ) {
    val original =
      ForgedFixtures.all.filterNot(c => c.family == "frequency" || c.family == "quantiles")
    assertEquals(original.count(_.isForged), 23)
    assertEquals(original.count(_.isTruncated), 52)
  }

  test("every family is covered, and every case says what it expects and what was done to it") {
    assertEquals(
      ForgedFixtures.all.map(_.family).toSet,
      Set("hll", "theta", "kll", "frequency", "quantiles")
    )
    ForgedFixtures.all.foreach { c =>
      assert(
        Set("incomplete", "not-a-sketch", "accepted").contains(c.expect),
        s"$c expects '${c.expect}'"
      )
      assert(c.what.nonEmpty, s"$c has no description")
      assert(c.bytes.nonEmpty, s"$c is empty")
    }
  }

  test(
    "a truncation always expects incomplete, and exactly one forged case is a soft inconsistency that still decodes"
  ) {
    assert(ForgedFixtures.all.filter(_.isTruncated).forall(_.expect == "incomplete"))
    assertEquals(
      ForgedFixtures.all.filter(_.mustBeAccepted).map(_.name),
      Vector("kll_minK_above_k")
    )
  }

  test("the forged blobs include the five that made the library run out of memory in Spike B") {
    val names = ForgedFixtures.all.map(_.name).toSet
    Seq(
      "theta_compact_exact_count_2^31-1",
      "theta_compact_estimating_count_2^31-1",
      "theta_intersection_lgArr26_count_1000",
      "theta_compressed_count_2^31-1_bits64",
      "kll_updatable_k65535_numLevels61"
    ).foreach(n => assert(names.contains(n), s"missing $n"))
  }
}
