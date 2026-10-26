package sketchprobe.adapter

import java.lang.foreign.MemorySegment
import java.nio.{ByteBuffer, ByteOrder}
import org.apache.datasketches.common.Util
import org.apache.datasketches.theta.{ThetaIntersection, ThetaSketch, ThetaUnion}
import sketchprobe.decode.theta.{ThetaData, ThetaKind}
import sketchprobe.preflight.{ThetaFacts, ThetaPreflight}
import sketchprobe.report.Diagnosis

/** Reads a Theta blob (families 1 to 5, including the compressed layout): preflight first, then the
  * library for the estimate, bounds, theta and retained hashes.
  *
  * The library refuses a blob whose seed hash is not the one of the seed it is given, and the blob
  * does not say which seed it used. We report the blob's seed hash as it is and ask the library
  * about the data with the seed hash replaced by the default one (a copy; the input is not
  * changed). The retained hashes do not depend on the seed hash, so the answers are the ones the
  * writing library gave.
  */
object ThetaAdapter {

  /** The seed every Apache DataSketches Theta sketch uses unless told otherwise. */
  val DefaultSeed = 9001L

  /** The 16-bit seed hash the library stores in a blob for `seed`. */
  def seedHash(seed: Long): Int = Util.computeSeedHash(seed) & 0xffff

  private val SeedHashOffset = 6
  private val POffset = 12

  private def libraryView(bytes: Array[Byte], facts: ThetaFacts): Array[Byte] = {
    val defaultHash = Util.computeSeedHash(DefaultSeed).toShort
    if (facts.empty || facts.seedHash == (defaultHash & 0xffff)) bytes
    else {
      val copy = bytes.clone()
      ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putShort(SeedHashOffset, defaultHash)
      copy
    }
  }

  private def open(kind: ThetaKind, seg: MemorySegment): ThetaSketch = kind match {
    case ThetaKind.Union        => ThetaUnion.wrap(seg, DefaultSeed).getResult()
    case ThetaKind.Intersection => ThetaIntersection.wrap(seg, DefaultSeed).getResult()
    case _                      => ThetaSketch.heapify(seg, DefaultSeed)
  }

  def read(bytes: Array[Byte]): Either[Diagnosis, ThetaData] =
    for {
      facts <- ThetaPreflight.check(bytes)
      sk <- Guarded.run("Theta sketch")(
        open(facts.kind, MemorySegment.ofArray(libraryView(bytes, facts)))
      )
      numbers <- Guarded.run("Theta sketch")(
        (
          sk.getEstimate,
          Vector.tabulate(3)(i => sk.getLowerBound(i + 1)),
          Vector.tabulate(3)(i => sk.getUpperBound(i + 1)),
          sk.getRetainedEntries(true),
          sk.getThetaLong,
          sk.isEstimationMode
        )
      )
    } yield {
      val (estimate, lower, upper, retained, thetaLong, estimating) = numbers
      val p = facts.kind match {
        case ThetaKind.Alpha | ThetaKind.QuickSelect | ThetaKind.Union =>
          Some(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(POffset))
        case _ => None
      }
      ThetaData(
        kind = facts.kind,
        serVer = facts.serVer,
        preambleLongs = facts.preambleLongs,
        shape = facts.shape,
        empty = facts.empty,
        ordered = facts.ordered,
        compactFlag = facts.compactFlag,
        lgNomLongs = facts.lgNomLongs,
        p = p,
        thetaLong = thetaLong,
        seedHash = facts.seedHash,
        retained = retained,
        estimationMode = estimating,
        estimate = estimate,
        lowerBounds = lower,
        upperBounds = upper,
        length = facts.length,
        hashes = () => hashesOf(sk, thetaLong)
      )
    }

  /** The retained hashes: those below theta, which is what `retained` counts. */
  private def hashesOf(sk: ThetaSketch, thetaLong: Long): Vector[Long] = {
    val it = sk.iterator()
    val out = Vector.newBuilder[Long]
    while (it.next()) {
      val h = it.get()
      if (h > 0L && h < thetaLong) out += h
    }
    out.result()
  }
}
