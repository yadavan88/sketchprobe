package sketchprobe.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** Runs the command line in process, with standard input given as bytes. */
object CliHarness {
  final case class Run(status: Int, out: String, err: String) {

    /** Standard output read as the one JSON object `--json` prints. */
    def json: ujson.Value = ujson.read(out)
  }

  def run(stdin: Array[Byte], args: String*): Run = runWith(stdin, false, args*)

  /** As `run`, but as if standard input were a terminal. */
  def runAtTerminal(stdin: Array[Byte], args: String*): Run = runWith(stdin, true, args*)

  private def runWith(stdin: Array[Byte], interactive: Boolean, args: String*): Run = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val status = Cli.run(
      args.toArray,
      new ByteArrayInputStream(stdin),
      new PrintStream(out, true, UTF_8),
      new PrintStream(err, true, UTF_8),
      interactive
    )
    Run(status, out.toString(UTF_8), err.toString(UTF_8))
  }

  /** The published schema, `docs/report.schema.json`, found by walking up from the working
    * directory.
    */
  lazy val schema: ujson.Value = {
    val file: Path = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("docs").resolve("report.schema.json"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new IllegalStateException("docs/report.schema.json not found"))
    ujson.read(Files.readString(file))
  }

  /** One corpus blob by family and name, written by `writer` (java-9.0.0 unless said otherwise). */
  def corpus(family: String, name: String, writer: String = "java-9.0.0"): sketchprobe.Fixture =
    sketchprobe.CorpusFixtures
      .family(family)
      .find(f => f.name == name && f.writer == writer)
      .getOrElse(throw new NoSuchElementException(s"$writer/$family/$name"))

  def normalize(s: String): String = s.split("\\s+").filter(_.nonEmpty).mkString(" ")
}
