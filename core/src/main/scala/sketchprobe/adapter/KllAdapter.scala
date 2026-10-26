package sketchprobe.adapter

import java.lang.foreign.MemorySegment
import org.apache.datasketches.kll.{KllDoublesSketch, KllFloatsSketch, KllLongsSketch}
import sketchprobe.decode.kll.{KllData, KllHeader, KllItemType, KllShape}
import sketchprobe.preflight.{KllFacts, KllForm, KllPreflight}
import sketchprobe.report.{Diagnosis, Value}

/** Reads a KLL blob, compact or working (updatable) layout, for one item type. The blob does not
  * say whether its items are float, double or long, so the caller supplies the type. Preflight
  * first, then the library for min, max, quantiles and the retained items.
  */
object KllAdapter {
  val DefaultRanks: List[Double] = List(0.0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1.0)

  /** What the three library classes have in common, with values already in report form. */
  private final case class Opened(
      min: () => Value,
      max: () => Value,
      quantile: Double => Value,
      items: () => Vector[Value]
  )

  private def open(t: KllItemType, seg: MemorySegment): Opened = t match {
    case KllItemType.Float32 =>
      val k = KllFloatsSketch.heapify(seg)
      Opened(
        () => Value.Num(k.getMinItem.toDouble),
        () => Value.Num(k.getMaxItem.toDouble),
        r => Value.Num(k.getQuantile(r).toDouble),
        () => {
          val it = k.iterator(); val b = Vector.newBuilder[Value]
          while (it.next()) b += Value.Num(it.getQuantile.toDouble)
          b.result()
        }
      )
    case KllItemType.Float64 =>
      val k = KllDoublesSketch.heapify(seg)
      Opened(
        () => Value.Num(k.getMinItem),
        () => Value.Num(k.getMaxItem),
        r => Value.Num(k.getQuantile(r)),
        () => {
          val it = k.iterator(); val b = Vector.newBuilder[Value]
          while (it.next()) b += Value.Num(it.getQuantile)
          b.result()
        }
      )
    case KllItemType.Int64 =>
      val k = KllLongsSketch.heapify(seg)
      Opened(
        () => Value.Int64(k.getMinItem),
        () => Value.Int64(k.getMaxItem),
        r => Value.Int64(k.getQuantile(r)),
        () => {
          val it = k.iterator(); val b = Vector.newBuilder[Value]
          while (it.next()) b += Value.Int64(it.getQuantile)
          b.result()
        }
      )
  }

  private def shape(form: KllForm): KllShape = form match {
    case KllForm.CompactEmpty  => KllShape.Empty
    case KllForm.CompactSingle => KllShape.SingleItem
    case KllForm.CompactFull   => KllShape.CompactFull
    case KllForm.UpdatableFull => KllShape.Updatable
  }

  def header(f: KllFacts): KllHeader =
    KllHeader(
      shape(f.form),
      f.k,
      f.m,
      f.n,
      f.minK,
      f.numLevels,
      f.levels,
      f.totalCapacity,
      f.retained,
      f.dataStart,
      f.weightsConsistent
    )

  def read(
      bytes: Array[Byte],
      itemType: KllItemType,
      ranks: List[Double] = DefaultRanks
  ): Either[Diagnosis, KllData] =
    for {
      facts <- KllPreflight.check(bytes, Some(itemType.size))
      ok <- Guarded.run("KLL sketch")(open(itemType, MemorySegment.ofArray(bytes)))
      numbers <- Guarded.run("KLL sketch") {
        if (facts.n == 0L) (None, None, Nil)
        else (Some(ok.min()), Some(ok.max()), ranks.map(r => r -> ok.quantile(r)))
      }
    } yield {
      val (min, max, quantiles) = numbers
      KllData(itemType, header(facts), min, max, quantiles, facts.length(itemType.size), ok.items)
    }
}
