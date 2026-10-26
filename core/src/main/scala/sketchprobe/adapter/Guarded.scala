package sketchprobe.adapter

import scala.util.control.ControlThrowable
import sketchprobe.report.{Diagnosis, DiagnosisCode}

/** The single place a call into the sketch library is made. The library was written for data its
  * own code produced and throws on anything else, so every failure is turned into a [[Diagnosis]]:
  * no blob can make the tool crash or print a stack trace (FR-019).
  *
  * Two kinds of failure are deliberately not turned into a verdict on the blob, because they say
  * something about the environment or the program, not about the data: a class that cannot be
  * loaded (for example a JDK older than the library needs) and an interrupted thread.
  */
object Guarded {
  private val MaxMessage = 120

  def run[A](what: String)(body: => A): Either[Diagnosis, A] =
    try Right(body)
    catch {
      case e: InterruptedException => throw e
      case e: LinkageError         => throw e
      case e: ControlThrowable     => throw e
      // Reading past the end: the blob is shorter than its header claims.
      case _: IndexOutOfBoundsException => Left(incomplete(what))
      // Allocating from a forged size. The preflight checks should stop these first; this is the safety net.
      case _: OutOfMemoryError => Left(incomplete(what))
      case e: Throwable        => Left(corrupt(what, e))
    }

  private def incomplete(what: String): Diagnosis =
    Diagnosis(
      DiagnosisCode.Incomplete,
      s"The $what is cut off, or its header claims more data than the blob holds.",
      Some("Copy the whole value again; if it was copied completely, the value is damaged.")
    )

  private def corrupt(what: String, e: Throwable): Diagnosis = {
    val detail = Option(e.getMessage)
      .map(_.linesIterator.nextOption().getOrElse("").trim)
      .filter(_.nonEmpty)
      .map(_.take(MaxMessage))
    Diagnosis(
      DiagnosisCode.NotASketch,
      s"The $what is not valid: the Apache DataSketches library rejected it${detail
          .fold("")(d => s" ($d)")}.",
      Some("Check that the input is a serialized sketch of the family the header names.")
    )
  }
}
