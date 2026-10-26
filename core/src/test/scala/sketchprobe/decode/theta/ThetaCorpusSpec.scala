package sketchprobe.decode.theta

import sketchprobe.CorpusFixtures
import sketchprobe.adapter.ThetaAdapter
import sketchprobe.CorpusFixtures.closeTo

/** Every Theta blob in the corpus decodes to the values the writing library reported (spec FR-012,
  * FR-018).
  */
class ThetaCorpusSpec extends munit.FunSuite {
  private val fixtures = CorpusFixtures.family("theta")
  private def isCompressed(f: sketchprobe.Fixture): Boolean = f.header("serVer").num.toInt == 4

  private def decoded(f: sketchprobe.Fixture): ThetaData =
    ThetaAdapter.read(f.bytes) match {
      case Right(d)   => d
      case Left(diag) => fail(s"$f did not decode: ${diag.code.label}: ${diag.reason}")
    }

  test("the corpus has Theta blobs from every family 1 to 5 and from three writers") {
    assert(fixtures.size >= 70, s"only ${fixtures.size} Theta fixtures")
    assertEquals(fixtures.map(_.header("familyId").num.toInt).toSet, Set(1, 2, 3, 4, 5))
    assertEquals(fixtures.map(_.writer).toSet, Set("java-6.2.0", "java-9.0.0", "cpp-5.2.0"))
  }

  test(
    "estimate, bounds, theta, retained entries and flags match the manifest for every serVer 3 blob"
  ) {
    fixtures.filterNot(isCompressed).foreach { f =>
      val d = decoded(f)
      val e = f.expected
      assert(
        closeTo(d.estimate, e("estimate").num),
        s"estimate of $f: ours ${d.estimate} vs ${e("estimate").num}"
      )
      (1 to 3).foreach { sd =>
        val lo = e(s"lowerBound$sd").num
        val hi = e(s"upperBound$sd").num
        assert(
          closeTo(d.lowerBounds(sd - 1), lo),
          s"lowerBound$sd of $f: ours ${d.lowerBounds(sd - 1)} vs $lo"
        )
        assert(
          closeTo(d.upperBounds(sd - 1), hi),
          s"upperBound$sd of $f: ours ${d.upperBounds(sd - 1)} vs $hi"
        )
      }
      assert(closeTo(d.theta, e("theta").num), s"theta of $f: ours ${d.theta} vs ${e("theta").num}")
      assertEquals(d.retained, e("retainedValid").num.toInt, s"retained of $f")
      assertEquals(d.empty, e("empty").bool, s"empty of $f")
      assertEquals(d.estimationMode, e("estimationMode").bool, s"estimationMode of $f")
    }
  }

  test("the Java writers match bit for bit") {
    fixtures.filter(f => f.writer.startsWith("java-") && !isCompressed(f)).foreach { f =>
      val d = decoded(f)
      assertEquals(d.estimate, f.expected("estimate").num, s"estimate of $f")
      (1 to 3).foreach { sd =>
        assertEquals(
          d.lowerBounds(sd - 1),
          f.expected(s"lowerBound$sd").num,
          s"lowerBound$sd of $f"
        )
        assertEquals(
          d.upperBounds(sd - 1),
          f.expected(s"upperBound$sd").num,
          s"upperBound$sd of $f"
        )
      }
    }
  }

  test("ordered is reported for compact sketches, and the single-item flag is not needed") {
    fixtures.filter(f => f.header("familyId").num.toInt == 3 && !isCompressed(f)).foreach { f =>
      assertEquals(decoded(f).ordered, f.expected("ordered").bool, s"ordered of $f")
    }
    // C++ does not set the single-item flag. The shape must still be recognized.
    val single = fixtures.filter(_.name == "theta_compact_ordered_n1")
    assertEquals(single.map(_.writer).toSet, Set("java-6.2.0", "java-9.0.0", "cpp-5.2.0"))
    single.foreach(f => assertEquals(decoded(f).shape, "single item", s"shape of $f"))
  }

  test(
    "the seed hash in the blob matches the manifest, except for empty sketches (Java writes 0)"
  ) {
    fixtures.filterNot(isCompressed).foreach { f =>
      val d = decoded(f)
      if (!d.empty) {
        assertEquals(d.seedHash, f.expected("seedHash").num.toInt, s"seed hash of $f")
      }
    }
  }

  test(
    "the preamble size is read with the resize factor masked out (update sketches have byte 0 = 195)"
  ) {
    val updates = fixtures.filter(f => Set(1, 2).contains(f.header("familyId").num.toInt))
    assert(updates.nonEmpty && updates.forall(_.header("byte0").num.toInt == 195))
    updates.foreach(f => assertEquals(decoded(f).preambleLongs, 3, s"preamble of $f"))
  }

  test("every blob is complete, and the size is exact for every blob") {
    fixtures.filterNot(isCompressed).foreach { f =>
      val d = decoded(f)
      assert(d.length.complete, s"$f: ${d.length}")
      assert(d.length.matches, s"$f has trailing or missing bytes: ${d.length}")
    }
  }

  test("the retained hashes are listed on demand, below theta, and as many as the count") {
    fixtures.filterNot(isCompressed).foreach { f =>
      val d = decoded(f)
      val hashes = d.hashes()
      assertEquals(hashes.size, d.retained, s"hash count of $f")
      assert(
        hashes.forall(h => h > 0 && h < d.thetaLong || d.thetaLong == Long.MaxValue),
        s"hash range of $f"
      )
      assertEquals(hashes.distinct.size, hashes.size, s"duplicate hashes in $f")
    }
  }

  test("compressed (serVer 4) blobs match the manifest, bit for bit for Java") {
    val compressed = fixtures.filter(isCompressed)
    assertEquals(compressed.size, 7, "expected the seven 9.0.0 compressed blobs")
    compressed.foreach { f =>
      val d = decoded(f)
      val e = f.expected
      assertEquals(d.estimate, e("estimate").num, s"estimate of $f")
      (1 to 3).foreach { sd =>
        assertEquals(d.lowerBounds(sd - 1), e(s"lowerBound$sd").num, s"lowerBound$sd of $f")
        assertEquals(d.upperBounds(sd - 1), e(s"upperBound$sd").num, s"upperBound$sd of $f")
      }
      assert(closeTo(d.theta, e("theta").num), s"theta of $f")
      assertEquals(d.retained, e("retainedValid").num.toInt, s"retained of $f")
      assertEquals(d.seedHash, e("seedHash").num.toInt, s"seed hash of $f")
      assertEquals(d.estimationMode, e("estimationMode").bool, s"estimationMode of $f")
      assert(!d.empty && d.ordered, s"$f is never empty and always ordered")
      assert(d.length.matches, s"$f has trailing or missing bytes: ${d.length}")
    }
  }

  test(
    "compressed hashes are strictly ascending and below theta, and there are as many as the count"
  ) {
    fixtures.filter(isCompressed).foreach { f =>
      val d = decoded(f)
      val hashes = d.hashes()
      assertEquals(hashes.size, d.retained, s"hash count of $f")
      assert(
        hashes.zip(hashes.drop(1)).forall { case (a, b) => a < b },
        s"$f is not strictly ascending"
      )
      assert(hashes.forall(h => h > 0 && h < d.thetaLong), s"$f has hashes outside (0, theta)")
    }
  }

  test("compressed hashes equal the hashes of the uncompressed blob written from the same input") {
    // Independent of the manifests: the same writer, the same input, two layouts.
    val pairs = List(
      "theta_compressed_ordered_n100" -> "theta_compact_ordered_n100",
      "theta_compressed_ordered_n5000" -> "theta_compact_ordered_n5000",
      "theta_compressed_ordered_n100000" -> "theta_compact_ordered_k4096_n100000",
      "theta_compressed_ordered_p05_n1000" -> "theta_compact_ordered_p05_n1000",
      "theta_compressed_ordered_seed12345_n5000" -> "theta_compact_ordered_n5000_seed12345"
    )
    pairs.foreach { case (comp, plain) =>
      val c = fixtures.find(f => f.writer == "java-9.0.0" && f.name == comp).get
      val p = fixtures.find(f => f.writer == "java-9.0.0" && f.name == plain).get
      assertEquals(decoded(c).hashes(), decoded(p).hashes(), s"$comp vs $plain")
      assertEquals(decoded(c).thetaLong, decoded(p).thetaLong, s"theta of $comp vs $plain")
    }
  }

  test("truncated compressed blobs are reported as incomplete, never an exception") {
    fixtures.filter(isCompressed).foreach { f =>
      (0 until f.bytes.length by math.max(1, f.bytes.length / 40)).foreach { n =>
        ThetaAdapter.read(f.bytes.take(n)) match {
          case Left(_)  => ()
          case Right(d) => fail(s"$f cut to $n bytes decoded unexpectedly: ${d.length}")
        }
      }
    }
  }
}
