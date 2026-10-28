package sketchprobe.adapter

import sketchprobe.ForgedFixtures
import sketchprobe.decode.kll.KllItemType
import sketchprobe.preflight.FrequencyItemType

/** The adapters end to end on the hostile set: the answer is the manifest's diagnosis (or a decoded
  * result for the soft case), never an exception, and quickly.
  */
class AdaptersForgedSpec extends munit.FunSuite {
  private def run(c: sketchprobe.ForgedCase): Either[String, Unit] = c.family match {
    case "hll"       => HllAdapter.read(c.bytes).left.map(_.code.label).map(_ => ())
    case "theta"     => ThetaAdapter.read(c.bytes).left.map(_.code.label).map(_ => ())
    case "quantiles" => QuantilesAdapter.read(c.bytes).left.map(_.code.label).map(_ => ())
    case "frequency" =>
      val t = if (c.itemType == "long") FrequencyItemType.Int64 else FrequencyItemType.Text
      FrequencyAdapter.read(c.bytes, Some(t)).left.map(_.code.label).map(_ => ())
    case _ =>
      KllAdapter
        .read(c.bytes, KllItemType.parse(c.itemType).toOption.getOrElse(KllItemType.Float32))
        .left
        .map(_.code.label)
        .map(_ => ())
  }

  test(
    "all 160 hostile blobs get the manifest's diagnosis through the adapters, within 5 seconds"
  ) {
    assertEquals(ForgedFixtures.all.size, 160)
    ForgedFixtures.all.foreach { c =>
      val started = System.nanoTime()
      val result = run(c)
      assert((System.nanoTime() - started) / 1000000 < 5000, s"$c too slow")
      if (c.mustBeAccepted) assert(result.isRight, s"$c should decode")
      else assertEquals(result, Left(c.expect), s"$c (${c.what})")
    }
  }
}
