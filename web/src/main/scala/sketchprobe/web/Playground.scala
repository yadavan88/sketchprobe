package sketchprobe.web

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import sketchprobe.cli.{ArgParser, Cli}
import sketchprobe.input.Normalizer

/** What the browser page calls: the command line, run in process on a value the user pasted. The
  * page passes the text and the options as it would to the command, and gets back the exit status
  * and the two output streams.
  *
  * The browser build is a WebAssembly compile of this code (docs/web.md). It has two limits the
  * command does not: no files, and no compressed Theta sketches.
  */
object Playground {
  final case class Result(status: Int, out: String, err: String)

  /** Exit status 3 is "recognized but not decoded", the same as for an unsupported family. */
  val NotInBrowserStatus = 3

  val CompressedThetaMessage: String =
    """sketchprobe: This is a compressed Theta sketch, which the browser version cannot read yet.
      |  next step:  Use the command line version, which reads it: https://github.com/yadavan88/sketchprobe#install""".stripMargin

  val FileMessage: String =
    """sketchprobe: --file is not available in the browser: there is no file to read.
      |  next step:  Paste the sketch value into the box.""".stripMargin

  /** A compact Theta sketch in the compressed layout starts with the bytes preLongs, 4, 3 (serial
    * version 4, family Compact). The Apache DataSketches library reads one wrongly when compiled to
    * WebAssembly: a byte read from a heap `MemorySegment` comes out signed.
    */
  private def isCompressedTheta(bytes: Array[Byte]): Boolean =
    bytes.length >= 3 && (bytes(1) & 0xff) == 4 && (bytes(2) & 0xff) == 3

  private def usageStatus = 64

  def run(value: String, options: Array[String]): Result =
    if (options.exists(o => o == "--file" || o.startsWith("--file=")))
      Result(usageStatus, "", FileMessage + "\n")
    else {
      // "--" so that a value that starts with a dash is still a value.
      val argv = options ++ Array("--", value)
      if (compressedTheta(argv)) Result(NotInBrowserStatus, "", CompressedThetaMessage + "\n")
      else inProcess(argv)
    }

  private def compressedTheta(argv: Array[String]): Boolean =
    ArgParser.parse(argv.toList) match {
      case Right(args) =>
        args.value.exists { v =>
          Normalizer.normalize(v.getBytes(UTF_8), args.inputFormat, args.maxInputBytes) match {
            case Right(blob) => isCompressedTheta(blob.bytes)
            case Left(_)     => false
          }
        }
      case Left(_) => false
    }

  private def inProcess(argv: Array[String]): Result = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val status = Cli.run(
      argv,
      new ByteArrayInputStream(Array.emptyByteArray),
      new PrintStream(out, true, UTF_8),
      new PrintStream(err, true, UTF_8)
    )
    Result(status, out.toString(UTF_8), err.toString(UTF_8))
  }

  /** The result as one JSON object, `{"status": 0, "out": "...", "err": "..."}`, which is how it
    * crosses into JavaScript: one string in, one string out.
    */
  def runJson(value: String, options: Array[String]): String = {
    val r = run(value, options)
    ujson.write(ujson.Obj("status" -> r.status, "out" -> r.out, "err" -> r.err))
  }
}
