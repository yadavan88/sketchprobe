package sketchprobe.adapter

import java.lang.foreign.MemorySegment
import java.nio.{ByteBuffer, ByteOrder}
import org.apache.datasketches.hll.HllSketch
import sketchprobe.decode.hll.{HllData, HllMode}
import sketchprobe.preflight.HllPreflight
import sketchprobe.report.Diagnosis

/** Reads an HLL blob: our preflight first, so a forged header never reaches the library, then the
  * library for the estimate and the bounds. The mode, register width and flags come from the
  * preflight, because the library keeps them hidden.
  */
object HllAdapter {
  private val HipAccumOffset = 8

  def read(bytes: Array[Byte]): Either[Diagnosis, HllData] =
    for {
      facts <- HllPreflight.check(bytes)
      sk <- Guarded.run("HLL sketch")(HllSketch.heapify(MemorySegment.ofArray(bytes)))
      bounds <- Guarded.run("HLL sketch")(
        (
          sk.getEstimate,
          Vector.tabulate(3)(i => sk.getLowerBound(i + 1)),
          Vector.tabulate(3)(i => sk.getUpperBound(i + 1))
        )
      )
    } yield {
      val (estimate, lower, upper) = bounds
      val hip =
        if (facts.mode == HllMode.Hll)
          ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getDouble(HipAccumOffset)
        else 0.0
      HllData(
        lgK = facts.lgK,
        mode = facts.mode,
        width = facts.width,
        empty = facts.empty,
        compactFlag = facts.compactFlag,
        outOfOrder = facts.outOfOrder,
        couponCount = facts.couponCount,
        curMin = facts.curMin,
        numAtCurMin = facts.numAtCurMin,
        hipAccum = hip,
        auxCount = facts.auxCount,
        estimate = estimate,
        lowerBounds = lower,
        upperBounds = upper,
        length = facts.length
      )
    }
}
