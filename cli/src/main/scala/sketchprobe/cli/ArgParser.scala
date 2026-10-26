package sketchprobe.cli

import sketchprobe.Version
import sketchprobe.adapter.KllAdapter
import sketchprobe.input.Normalizer
import sketchprobe.report.{Diagnosis, DiagnosisCode, InputFormat}

/** What the command line asked for. */
final case class Args(
    file: Option[String] = None,
    itemTypeName: Option[String] = None,
    ranks: List[Double] = KllAdapter.DefaultRanks,
    showHashes: Boolean = false,
    showItems: Boolean = false,
    inputFormat: Option[InputFormat] = None,
    maxInputBytes: Long = Normalizer.DefaultMaxBytes,
    seed: Option[Long] = None,
    json: Boolean = false,
    headerOnly: Boolean = false,
    help: Boolean = false,
    version: Boolean = false
)

/** The command line of contracts/cli.md. Every mistake is a usage diagnosis (exit status 64) that
  * names the option and says what is accepted.
  */
object ArgParser {
  val Usage: String =
    "usage: sketchprobe [OPTIONS] [FILE]   (run sketchprobe --help for the options)"

  val Help: String =
    s"""sketchprobe ${Version.current}: ${Version.tagline}
       |
       |$Usage
       |
       |Reads one serialized sketch from FILE, or from standard input when there is no
       |FILE or it is '-', and prints a report: the family, how sure the tool is, the
       |values the sketch holds, and what can be recovered from it.
       |
       |Options:
       |  --json                 print the report as one JSON object (see docs/)
       |  --input-format F       auto (default), mongo, hex, base64, base64url, raw
       |  --type T               item type: float, double or long (KLL); long or string
       |                         (Frequency). The blob does not record it.
       |  --seed N               the Theta seed for the seed-hash check (default 9001)
       |  --ranks R,R,...        KLL quantile ranks between 0 and 1
       |  --show-hashes          list the retained Theta hashes
       |  --show-items           list the retained KLL items, or all Frequency items
       |  --header-only          identify the sketch and stop: header and confidence
       |  --max-input-bytes N    refuse larger input (default 67108864)
       |  --version              print the version
       |  --help                 print this help
       |
       |Exit status: 0 decoded; 1 unreadable input; 2 not a recognizable sketch;
       |3 recognized but not decoded; 64 usage error.""".stripMargin

  private val NeedsValue =
    Set("--type", "--input-format", "--max-input-bytes", "--seed", "--ranks")

  private def usage(reason: String, next: String): Left[Diagnosis, Nothing] =
    Left(Diagnosis(DiagnosisCode.Usage, reason, Some(next)))

  def parse(args: List[String], acc: Args = Args()): Either[Diagnosis, Args] =
    args match {
      case Nil                           => Right(acc)
      case opt :: Nil if NeedsValue(opt) =>
        usage(s"The option $opt needs a value.", "Run sketchprobe --help to see what it takes.")
      case "--type" :: v :: rest =>
        itemTypeName(v).flatMap(n => parse(rest, acc.copy(itemTypeName = Some(n))))
      case "--ranks" :: v :: rest =>
        val parsed = v.split(",").toList.map(_.trim.toDoubleOption)
        if (parsed.nonEmpty && parsed.forall(_.exists(r => r >= 0.0 && r <= 1.0)))
          parse(rest, acc.copy(ranks = parsed.flatten))
        else
          usage(
            s"Invalid --ranks value '$v'.",
            "Use numbers between 0 and 1, for example 0.5,0.99."
          )
      case "--input-format" :: v :: rest =>
        inputFormat(v).flatMap(f => parse(rest, acc.copy(inputFormat = f)))
      case "--max-input-bytes" :: v :: rest =>
        v.toLongOption.filter(_ > 0) match {
          case Some(n) => parse(rest, acc.copy(maxInputBytes = n))
          case None    =>
            usage(s"Invalid --max-input-bytes value '$v'.", "Use a positive whole number of bytes.")
        }
      case "--seed" :: v :: rest =>
        v.trim.toLongOption match {
          case Some(n) => parse(rest, acc.copy(seed = Some(n)))
          case None    =>
            usage(
              s"Invalid --seed value '$v'.",
              "Use the whole number the Theta sketch was built with, for example --seed 12345."
            )
        }
      case "--json" :: rest             => parse(rest, acc.copy(json = true))
      case "--header-only" :: rest      => parse(rest, acc.copy(headerOnly = true))
      case "--show-hashes" :: rest      => parse(rest, acc.copy(showHashes = true))
      case "--show-items" :: rest       => parse(rest, acc.copy(showItems = true))
      case ("--help" | "-h") :: rest    => parse(rest, acc.copy(help = true))
      case ("--version" | "-V") :: rest => parse(rest, acc.copy(version = true))
      case flag :: _ if flag.startsWith("-") && flag != "-" =>
        usage(s"Unknown option '$flag'.", "Run sketchprobe --help to see the options.")
      case file :: rest =>
        if (acc.file.isDefined)
          usage(
            s"More than one input was named ('${acc.file.get}' and '$file').",
            "Give one FILE, or pipe the value in."
          )
        else parse(rest, acc.copy(file = Some(file)))
    }

  /** The item type names a user may give. Which one a sketch accepts depends on its family (KLL:
    * float, double, long; Frequency: long, string), which is only known once the blob is read.
    */
  private def itemTypeName(v: String): Either[Diagnosis, String] = v.trim.toLowerCase match {
    case n @ ("float" | "double" | "long" | "string") => Right(n)
    case other                                        =>
      usage(
        s"Unknown item type '$other'.",
        "Use --type float, double or long (KLL), or long or string (Frequency)."
      )
  }

  /** `auto` means detect; anything else forces that format. */
  private def inputFormat(v: String): Either[Diagnosis, Option[InputFormat]] =
    v.trim.toLowerCase match {
      case "auto"      => Right(None)
      case "mongo"     => Right(Some(InputFormat.MongoJson))
      case "hex"       => Right(Some(InputFormat.Hex))
      case "base64"    => Right(Some(InputFormat.Base64))
      case "base64url" => Right(Some(InputFormat.Base64Url))
      case "raw"       => Right(Some(InputFormat.Raw))
      case other       =>
        usage(s"Unknown input format '$other'.", "Use auto, mongo, hex, base64, base64url or raw.")
    }
}
