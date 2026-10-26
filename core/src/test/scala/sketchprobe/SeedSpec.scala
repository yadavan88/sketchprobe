package sketchprobe

import org.apache.datasketches.common.Util
import sketchprobe.{Fixture => CorpusFixture}
import sketchprobe.report._

/** A Theta sketch built with a seed other than the default still decodes to the same values, with a
  * warning that says how to supply the seed; the matching seed removes the warning (spec FR-017,
  * SC-005). The seed hash is the library's own, `Util.computeSeedHash`.
  */
class SeedSpec extends munit.FunSuite {
  private val DefaultSeed = 9001L
  private val thetas = CorpusFixtures.family("theta")

  private def seedOf(f: CorpusFixture): Long =
    f.params.obj.get("seed").map(_.str.toLong).getOrElse(DefaultSeed)
  private def inspect(f: CorpusFixture, seed: Option[Long]): Report =
    Inspector.inspect(
      f.bytes,
      InputInfo(InputFormat.Raw, FormatSource.Detected, f.bytes.length),
      InspectOptions(seed = seed)
    )
  private def seedWarnings(r: Report): List[Warning] =
    r.warnings.filter(_.code == WarningCode.SeedMismatch)
  private def isEmpty(f: CorpusFixture): Boolean = f.expected("empty").bool
  private val custom = thetas.filter(f => seedOf(f) != DefaultSeed)

  test("the corpus has Theta sketches with a non-default seed, from more than one writer") {
    assert(custom.size >= 5, s"only ${custom.size}")
    assert(custom.map(_.writer).toSet.size >= 2, custom.map(_.writer).toSet.toString)
    assert(custom.exists(!isEmpty(_)))
  }

  test("a non-default seed warns, still decodes, and exits 0") {
    custom.filterNot(isEmpty).foreach { f =>
      val r = inspect(f, None)
      assertEquals(r.status, 0, s"$f")
      assertEquals(seedWarnings(r).size, 1, s"warnings of $f")
    }
  }

  test("the warning says what is wrong and how to supply the seed") {
    val w = seedWarnings(inspect(custom.find(!isEmpty(_)).get, None)).head
    assert(w.message.toLowerCase.contains("seed"), w.message)
    assert(w.nextStep.exists(_.contains("--seed")), w.nextStep.toString)
  }

  test("the decoded values are the same with or without the seed") {
    custom.filterNot(isEmpty).foreach { f =>
      assertEquals(inspect(f, None).summary, inspect(f, Some(seedOf(f))).summary, s"$f")
    }
  }

  test("the matching seed removes the warning") {
    custom.foreach { f =>
      assertEquals(seedWarnings(inspect(f, Some(seedOf(f)))), Nil, s"$f")
    }
  }

  test("a seed that matches neither the blob nor the default warns, whatever the blob") {
    thetas.filterNot(isEmpty).take(20).foreach { f =>
      assertEquals(seedWarnings(inspect(f, Some(424242L))).size, 1, s"$f")
    }
  }

  test("default-seed sketches never warn, with no seed or with the default one") {
    thetas.filter(f => seedOf(f) == DefaultSeed).foreach { f =>
      assertEquals(seedWarnings(inspect(f, None)), Nil, s"$f")
      assertEquals(seedWarnings(inspect(f, Some(DefaultSeed))), Nil, s"$f")
    }
  }

  test("empty sketches never warn, because they carry no usable seed hash") {
    thetas.filter(isEmpty).foreach { f =>
      assertEquals(seedWarnings(inspect(f, None)), Nil, s"$f")
      assertEquals(seedWarnings(inspect(f, Some(424242L))), Nil, s"$f")
    }
  }

  test(
    "the stored seed hash is the library's hash of the blob's seed, for every non-empty sketch"
  ) {
    thetas.filterNot(isEmpty).foreach { f =>
      val hash = Util.computeSeedHash(seedOf(f)) & 0xffff
      assertEquals(f.expected("seedHash").num.toInt & 0xffff, hash, s"$f")
    }
  }

  test("a wrong seed never changes the decoded values either") {
    thetas.filterNot(isEmpty).take(20).foreach { f =>
      assertEquals(inspect(f, Some(424242L)).summary, inspect(f, None).summary, s"$f")
    }
  }
}
