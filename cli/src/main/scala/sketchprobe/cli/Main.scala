package sketchprobe.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Files, Paths}
import sketchprobe.{InspectOptions, Inspector, Version}
import sketchprobe.input.Normalizer
import sketchprobe.report._

/** Entry point: arguments, input, inspection, then the human or the JSON report. Reports go to
  * standard output, failures to standard error, and with `--json` also as an object on standard
  * output so that a script can read either outcome from one stream (contracts/cli.md).
  */
object Cli {
  private def failureReport(d: Diagnosis): Report =
    Report(
      InputInfo(InputFormat.Unknown, FormatSource.Detected, 0),
      None,
      None,
      Confidence.none,
      Nil,
      None,
      None,
      Nil,
      Some(d)
    )

  /** Prints a report and returns the exit status. */
  private def emit(report: Report, json: Boolean, out: PrintStream, err: PrintStream): Int = {
    if (json) out.println(JsonRenderer.render(report))
    else if (report.summary.nonEmpty || report.diagnosis.isEmpty)
      out.println(HumanRenderer.render(report))
    if (report.diagnosis.isDefined) err.println(HumanRenderer.renderFailure(report))
    report.status
  }

  /** Reads the file or standard input, never more than the cap plus one byte, so that an endless or
    * huge input cannot fill memory.
    */
  private def read(args: Args, stdin: InputStream): Either[Diagnosis, Array[Byte]] = {
    val limit = math.min(args.maxInputBytes, Int.MaxValue - 16L) + 1
    try
      Right(args.file.filterNot(_ == "-") match {
        case Some(f) =>
          val in = Files.newInputStream(Paths.get(f))
          try in.readNBytes(limit.toInt)
          finally in.close()
        case None => stdin.readNBytes(limit.toInt)
      })
    catch {
      case e: java.io.IOException =>
        val what = args.file.fold("standard input")(f => s"file '$f'")
        val detail = e match {
          case _: java.nio.file.NoSuchFileException => "no such file"
          case _                                    => Option(e.getMessage).getOrElse("read error")
        }
        Left(
          Diagnosis(
            DiagnosisCode.InputUnreadable,
            s"Cannot read $what: $detail.",
            Some(
              "To decode a value you copied, pipe it in (pbpaste | sketchprobe) instead of passing it as an argument."
            )
          )
        )
    }
  }

  def run(argv: Array[String], stdin: InputStream, out: PrintStream, err: PrintStream): Int =
    ArgParser.parse(argv.toList) match {
      // A usage error is reported as JSON too if --json was given anywhere on the line.
      case Left(d)                  => emit(failureReport(d), argv.contains("--json"), out, err)
      case Right(args) if args.help =>
        out.println(ArgParser.Help)
        0
      case Right(args) if args.version =>
        out.println(s"sketchprobe ${Version.current}")
        0
      case Right(args) =>
        val result = for {
          raw <- read(args, stdin)
          blob <- Normalizer.normalize(raw, args.inputFormat, args.maxInputBytes)
        } yield blob
        val report = result match {
          case Left(d)     => failureReport(d)
          case Right(blob) =>
            val input = InputInfo(
              blob.format,
              blob.source,
              blob.bytes.length,
              blob.alternatives,
              blob.notes
            )
            Inspector.inspect(
              blob.bytes,
              input,
              InspectOptions(
                args.itemTypeName,
                args.ranks,
                args.showHashes,
                args.showItems,
                args.seed,
                args.headerOnly
              )
            )
        }
        emit(report, args.json, out, err)
    }
}

object Main {
  def main(args: Array[String]): Unit = sys.exit(Cli.run(args, System.in, System.out, System.err))
}
