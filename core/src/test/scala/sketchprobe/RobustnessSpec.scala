package sketchprobe

import scala.concurrent.duration._
import scala.util.Random
import sketchprobe.input.Normalizer
import sketchprobe.{Fixture => CorpusFixture}
import sketchprobe.report.{FormatSource, InputFormat, InputInfo}

/** No input makes the tool crash, hang or print an internal error: random, truncated, bit-flipped
  * and forged blobs, end to end (input, preflight, library, report), in a small heap, each within 5
  * seconds (spec SC-003, FR-019).
  */
class RobustnessSpec extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val LimitMs = 5000L
  private var checked = 0
  private var slowestMs = 0L

  private val typeNames = Vector(None, Some("float"), Some("double"), Some("long"), Some("string"))

  /** No line that looks like a stack trace or names an internal class. */
  private def traceFree(s: String): Boolean =
    !s.contains("Exception") && !s.contains("\tat ") && !s.contains("java.lang") &&
      !s.contains("org.apache.datasketches") && !s.contains("Error:")

  private def inspect(label: => String, bytes: Array[Byte], typeName: Option[String]): Unit = {
    val started = System.nanoTime()
    val report = Inspector.inspect(
      bytes,
      InputInfo(InputFormat.Raw, FormatSource.Detected, bytes.length),
      InspectOptions(itemTypeName = typeName, showHashes = true, showItems = true)
    )
    val ms = (System.nanoTime() - started) / 1000000
    slowestMs = math.max(slowestMs, ms)
    assert(ms < LimitMs, s"$label took $ms ms")
    report.diagnosis match {
      case None    => assert(report.summary.nonEmpty, s"$label: success without a summary")
      case Some(d) =>
        assert(Set(1, 2, 3, 64).contains(d.exitStatus), s"$label: status ${d.exitStatus}")
        assert(d.reason.nonEmpty, s"$label: no reason")
        assert(traceFree(d.reason + d.nextStep.getOrElse("")), s"$label: ${d.reason}")
    }
    checked += 1
  }

  test("the tests run in a small heap, so that a forged size that is trusted would be fatal") {
    val max = Runtime.getRuntime.maxMemory
    assert(
      max <= 400L * 1024 * 1024,
      s"max heap is ${max / 1024 / 1024} MB; Test / javaOptions -Xmx256m is not in effect"
    )
  }

  test("random blobs, half of them with a header that fits a supported family") {
    val rnd = new Random(2026)
    val families = Vector(1, 2, 3, 4, 5, 7, 10, 15)
    (0 until 6000).foreach { i =>
      val b = new Array[Byte](rnd.nextInt(3000))
      rnd.nextBytes(b)
      if (i % 2 == 0 && b.length > 8) {
        b(2) = families(rnd.nextInt(families.size)).toByte
        b(1) = rnd.nextInt(5).toByte
        b(0) = rnd.nextInt(12).toByte
      }
      inspect(s"random $i", b, typeNames(rnd.nextInt(typeNames.size)))
    }
  }

  private def typeOf(f: CorpusFixture): Option[String] =
    if (Set("kll", "frequency").contains(f.family)) Some(f.params("itemType").str) else None

  test("every corpus blob cut short at nine points, including inside the header") {
    CorpusFixtures.all.foreach { f =>
      val n = f.bytes.length
      Set(0, 1, 7, 8, 9, n / 4, n / 2, (3 * n) / 4, n - 1).filter(c => c >= 0 && c < n).foreach {
        cut =>
          inspect(s"$f cut to $cut", f.bytes.take(cut), typeOf(f))
      }
    }
  }

  test("every corpus blob with one, then three, bits flipped") {
    val rnd = new Random(7)
    CorpusFixtures.all.foreach { f =>
      val n = f.bytes.length
      (0 until 8).foreach { k =>
        val b = f.bytes.clone()
        val flips = if (k < 5) 1 else 3
        (0 until flips).foreach { _ =>
          // half of the flips land in the header, where they matter most
          val at = if (rnd.nextBoolean()) rnd.nextInt(math.min(n, 40)) else rnd.nextInt(n)
          b(at) = (b(at) ^ (1 << rnd.nextInt(8))).toByte
        }
        inspect(s"$f bit flips #$k", b, typeOf(f))
      }
    }
  }

  test("the whole forged set, and its blobs cut in half") {
    ForgedFixtures.all.foreach { c =>
      val t = if (c.itemType.isEmpty) None else Some(c.itemType)
      inspect(c.toString, c.bytes, t)
      inspect(s"$c cut", c.bytes.take(c.bytes.length / 2), t)
    }
  }

  test("random text and random bytes through the input stage too") {
    val rnd = new Random(11)
    val alphabet = "0123456789abcdefABCDEF+/=-_ \n\t\"'{}[]$:,\\xX"
    (0 until 2000).foreach { i =>
      val text = Array.fill(rnd.nextInt(600))(alphabet(rnd.nextInt(alphabet.length)).toByte)
      val started = System.nanoTime()
      Normalizer.normalize(text) match {
        case Right(blob) => inspect(s"text $i", blob.bytes, None)
        case Left(d)     => assert(traceFree(d.reason), s"text $i: ${d.reason}")
      }
      assert((System.nanoTime() - started) / 1000000 < LimitMs, s"text $i too slow")
      checked += 1
    }
  }

  test("at least 10,000 inputs were checked, none over 5 seconds") {
    println(s"RobustnessSpec: $checked inputs, slowest $slowestMs ms")
    assert(checked >= 10000, s"only $checked inputs")
    assert(slowestMs < LimitMs)
  }
}
