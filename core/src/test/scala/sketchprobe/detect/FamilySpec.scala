package sketchprobe.detect

import sketchprobe.CorpusFixtures
import sketchprobe.report.PreambleUnit

class FamilySpec extends munit.FunSuite {
  test("there are 22 families with the IDs 1 to 22, each named once") {
    assertEquals(Family.all.map(_.id), (1 to 22).toVector)
    assertEquals(Family.all.map(_.name).distinct.size, 22)
  }

  test("supported families are exactly Theta 1 to 5, HLL, Frequency and KLL") {
    assertEquals(Family.all.filter(_.supported).map(_.id).toSet, Set(1, 2, 3, 4, 5, 7, 10, 15))
  }

  test("AnotB is recognized but unsupported") {
    assert(!Family.byId(6).get.supported)
  }

  test("the preamble units are Theta longs, HLL and KLL ints") {
    assertEquals(Family.byId(3).get.unit, PreambleUnit.Longs)
    assertEquals(Family.byId(7).get.unit, PreambleUnit.Ints)
    assertEquals(Family.byId(15).get.unit, PreambleUnit.Ints)
  }

  test(
    "every blob in the corpus is valid under the family table (serVer and byte 0 in the family's unit)"
  ) {
    CorpusFixtures.all.foreach { f =>
      val h = SketchHeader.parse(f.bytes).toOption.get
      val fam = Family.byId(h.familyId).getOrElse(fail(s"unknown family ${h.familyId} in $f"))
      assert(fam.supported, s"$f has unsupported family ${fam.name}")
      val sizes = fam.layouts.getOrElse(
        h.serVer,
        fail(s"$f: serVer ${h.serVer} not in the table for ${fam.name}")
      )
      assert(
        sizes.contains(h.preambleSize),
        s"$f: preamble size ${h.preambleSize} (byte 0 = ${h.byte0}) not valid for ${fam.name} serVer ${h.serVer}"
      )
    }
  }

  test("the table is tight: every combination it allows appears in the corpus") {
    val seen = CorpusFixtures.all.map { f =>
      val h = SketchHeader.parse(f.bytes).toOption.get
      (h.familyId, h.serVer, h.preambleSize)
    }.toSet
    val allowed = for {
      fam <- Family.all.filter(_.supported)
      (serVer, sizes) <- fam.layouts.toSeq
      size <- sizes
    } yield (fam.id, serVer, size)
    // Union and Intersection exist in Java only, and Alpha too; all have fixtures. Nothing should be unexplained.
    val unseen = allowed.filterNot(seen.contains)
    assertEquals(
      unseen,
      Seq.empty[(Int, Int, Int)],
      "combinations allowed by the table but never seen in the corpus"
    )
  }
}
