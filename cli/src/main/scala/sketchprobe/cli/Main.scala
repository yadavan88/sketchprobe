package sketchprobe.cli

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
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

  val PastePrompt: String =
    "Paste the sketch (hex, base64 or Mongo JSON), then press Enter twice:"

  /** Reads what is typed or pasted at a terminal: lines until a blank one (or the end of input),
    * never more than `limit` bytes. A pasted sketch may be wrapped over several lines, so one line
    * does not end it.
    */
  private def readPasted(stdin: InputStream, limit: Int): Array[Byte] = {
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(stdin, UTF_8))
    val text = new StringBuilder
    var seenContent = false
    var lineBlank = true
    var done = false
    // One character at a time, so that a single endless line cannot fill memory either.
    while (!done) {
      val c = reader.read()
      if (c < 0) done = true
      else if (c == '\n') {
        if (lineBlank && seenContent) done = true
        else {
          text.append('\n')
          lineBlank = true
        }
      } else {
        text.append(c.toChar)
        if (!Character.isWhitespace(c)) {
          lineBlank = false
          seenContent = true
        }
      }
      if (text.length > limit) done = true
    }
    text.toString.getBytes(UTF_8)
  }

  /** Reads the sketch text from the argument, a file, or standard input, never more than the cap
    * plus one byte, so that an endless or huge input cannot fill memory. At a terminal with no
    * argument it asks for a paste.
    */
  private def read(
      args: Args,
      stdin: InputStream,
      err: PrintStream,
      interactive: Boolean
  ): Either[Diagnosis, Array[Byte]] = {
    val limit = math.min(args.maxInputBytes, Int.MaxValue - 16L) + 1
    try
      Right(
        args.value
          .map(_.getBytes(UTF_8))
          .getOrElse(args.file.filterNot(_ == "-") match {
            case Some(f) =>
              val in = Files.newInputStream(Paths.get(f))
              try in.readNBytes(limit.toInt)
              finally in.close()
            case None if interactive && args.file.isEmpty =>
              err.println(PastePrompt)
              readPasted(stdin, limit.toInt)
            case None => stdin.readNBytes(limit.toInt)
          })
      )
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
              "Check the path. To decode a value you copied, leave out --file: sketchprobe 'VALUE' or pbpaste | sketchprobe."
            )
          )
        )
    }
  }

  /** A value that is not any known format may have been meant as a file name. */
  private def withFileHint(d: Diagnosis, args: Args): Diagnosis =
    if (args.value.isDefined && d.reason == Normalizer.OrdinaryText)
      d.copy(nextStep =
        Some(
          "If this was meant to be a file, use --file FILE. Otherwise copy the sketch value itself, or use --input-format to say how to read it."
        )
      )
    else d

  def run(
      argv: Array[String],
      stdin: InputStream,
      out: PrintStream,
      err: PrintStream,
      interactive: Boolean = false
  ): Int =
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
          raw <- read(args, stdin, err, interactive)
          blob <- Normalizer.normalize(raw, args.inputFormat, args.maxInputBytes)
        } yield blob
        val report = result match {
          case Left(d)     => failureReport(withFileHint(d, args))
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

/** The fat jar needs a JDK new enough for datasketches-java (Java 25 classes). On an older one the
  * library's classes fail to load with a stack trace, so the version is checked first.
  */
object JavaVersion {
  val Required = 25

  /** The failure text when `feature` (the running JDK's major version) is too old. */
  def problem(feature: Int): Option[String] =
    if (feature >= Required) None
    else
      Some(
        s"""sketchprobe: This needs Java $Required or newer, but this is Java $feature.
          |  next step:  Run it with a Java $Required runtime (java -version shows which one is used), or use the native executable, which needs no Java.""".stripMargin
      )
}

object Main {
  def main(args: Array[String]): Unit =
    JavaVersion.problem(Runtime.version().feature()) match {
      case Some(text) =>
        System.err.println(text)
        sys.exit(1)
      case None =>
        // JDK 22 and newer: true only when standard input and output are a terminal.
        val interactive = Option(System.console()).exists(_.isTerminal)
        sys.exit(Cli.run(args, System.in, System.out, System.err, interactive))
    }
}
