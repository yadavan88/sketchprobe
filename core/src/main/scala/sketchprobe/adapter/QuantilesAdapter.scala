package sketchprobe.adapter

import java.lang.foreign.MemorySegment
import org.apache.datasketches.quantiles.QuantilesDoublesSketch
import org.apache.datasketches.quantilescommon.QuantileSearchCriteria
import sketchprobe.decode.quantiles.{QuantilesData, QuantilesHeader, QuantilesShape}
import sketchprobe.preflight.{QuantilesFacts, QuantilesForm, QuantilesPreflight}
import sketchprobe.report.{Diagnosis, Value}

/** Reads a classic Quantiles (doubles) blob, compact or working layout. Preflight first, then the
  * library for min, max, quantiles and the retained items.
  */
object QuantilesAdapter {
  val DefaultRanks: List[Double] = KllAdapter.DefaultRanks

  private def shape(form: QuantilesForm): QuantilesShape = form match {
    case QuantilesForm.Empty     => QuantilesShape.Empty
    case QuantilesForm.Compact   => QuantilesShape.Compact
    case QuantilesForm.Updatable => QuantilesShape.Updatable
  }

  def header(f: QuantilesFacts): QuantilesHeader =
    QuantilesHeader(shape(f.form), f.k, f.n, f.retained)

  def read(
      bytes: Array[Byte],
      ranks: List[Double] = DefaultRanks
  ): Either[Diagnosis, QuantilesData] =
    for {
      facts <- QuantilesPreflight.check(bytes)
      sketch <- Guarded.run("Quantiles sketch")(
        QuantilesDoublesSketch.heapify(MemorySegment.ofArray(bytes))
      )
      numbers <- Guarded.run("Quantiles sketch") {
        if (facts.n == 0L) (None, None, Nil)
        else
          (
            Some(Value.Num(sketch.getMinItem)),
            Some(Value.Num(sketch.getMaxItem)),
            ranks.map(r => r -> Value.Num(sketch.getQuantile(r, QuantileSearchCriteria.INCLUSIVE)))
          )
      }
    } yield {
      val (min, max, quantiles) = numbers
      QuantilesData(header(facts), min, max, quantiles, facts.length, () => items(sketch))
    }

  /** The retained items with their weights collapsed: the sorted view lists each item once. */
  private def items(sketch: QuantilesDoublesSketch): Vector[Value] = {
    val b = Vector.newBuilder[Value]
    val it = sketch.iterator()
    while (it.next()) b += Value.Num(it.getQuantile)
    b.result().sortBy { case Value.Num(d) => d; case _ => 0.0 }
  }
}
