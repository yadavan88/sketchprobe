package sketchprobe.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import sketchprobe.cli.CliHarness.{corpus, run, runAtTerminal}

/** At a terminal with no argument the tool asks for a paste; pipes and arguments are untouched. */
class PastePromptSpec extends munit.FunSuite {
  private val blob = corpus("hll", "hll_HLL_8_k12_n50_compact")
  private val base64 = Base64.getEncoder.encodeToString(blob.bytes)
  private def bytes(s: String) = s.getBytes(UTF_8)
  private val piped = run(bytes(base64))

  test("at a terminal the prompt goes to standard error and a blank line ends the paste") {
    val r = runAtTerminal(bytes(s"$base64\n\nanything after the blank line is ignored\n"))
    assertEquals(r.status, 0, r.err)
    assertEquals(r.out, piped.out)
    assertEquals(r.err.trim, Paste.Prompt)
  }

  test("the end of input also ends the paste, and a wrapped value over several lines is one blob") {
    assertEquals(runAtTerminal(bytes(base64)).out, piped.out)
    val wrapped = base64.grouped(60).mkString("\n")
    assertEquals(runAtTerminal(bytes(wrapped + "\n\n")).out, piped.out)
  }

  test("leading blank lines are skipped, and an empty paste is the usual empty-input failure") {
    assertEquals(runAtTerminal(bytes(s"\n\n$base64\n\n")).out, piped.out)
    val empty = runAtTerminal(bytes("\n"))
    assertEquals(empty.status, 1)
  }

  test("the prompt is not shown for a pipe, a value argument or --file -") {
    assertEquals(run(bytes(base64)).err, "")
    assertEquals(runAtTerminal(Array.emptyByteArray, base64).err, "")
    assertEquals(runAtTerminal(bytes(base64), "--file", "-").err, "")
    assertEquals(runAtTerminal(bytes(base64), "--file", "-").out, piped.out)
  }

  test("a huge paste is cut at the size cap, not read to the end") {
    val r = runAtTerminal(bytes("A" * 10000 + "\n"), "--max-input-bytes", "100")
    assertEquals(r.status, 1)
    assert(r.err.contains("larger than the limit of 100 bytes"), r.err)
  }

  test("the prompt says a terminal cuts a long line and names the alternative") {
    assert(Paste.Prompt.contains("pbpaste | sketchprobe"), Paste.Prompt)
    assert(Paste.Prompt.contains("1,000"), Paste.Prompt)
  }

  test("a pasted line as long as the terminal allows is refused, with the other ways to give it") {
    val line = "A" * Paste.lineCap
    val r = runAtTerminal(bytes(line + "\n\n"))
    assertEquals(r.status, 1)
    assert(r.err.contains(s"${Paste.lineCap} characters"), r.err)
    assert(r.err.contains("pbpaste | sketchprobe"), r.err)
    assert(r.err.contains("--file"), r.err)
    assert(!r.err.contains("Exception"), r.err)
  }

  test("a line one character shorter than the limit is read as usual") {
    val r = runAtTerminal(bytes("A" * (Paste.lineCap - 1) + "\n\n"))
    assert(!r.err.contains("most a terminal accepts"), r.err)
  }

  test("a wrapped value over many short lines is fine however long it is in all") {
    val big = corpus("hll", "hll_HLL_8_k12_n1000_compact")
    val text = Base64.getEncoder.encodeToString(big.bytes).grouped(76).mkString("\n")
    assert(text.length > Paste.lineCap)
    val r = runAtTerminal(bytes(text + "\n\n"))
    assertEquals(r.status, 0, r.err)
  }

  test("the terminal's bracketed-paste markers around a paste are not part of the value") {
    val r = runAtTerminal(bytes("\u001b[200~" + base64 + "\u001b[201~\n\n"))
    assertEquals(r.status, 0, r.err)
    assertEquals(r.out, piped.out)
  }

  test("a long silence at the prompt gets a hint, once, and the read still completes later") {
    val in = new java.io.PipedInputStream
    val out = new java.io.PipedOutputStream(in)
    val err = new java.io.ByteArrayOutputStream
    val ps = new java.io.PrintStream(err, true, UTF_8)
    @volatile var result: Option[Either[sketchprobe.report.Diagnosis, Array[Byte]]] = None
    val reader = new Thread(() => result = Some(Paste.read(in, 1000, ps, Paste.lineCap, 200L)))
    reader.start()
    Thread.sleep(700)
    assertEquals(err.toString(UTF_8).split("Still waiting").length - 1, 1, err.toString(UTF_8))
    out.write(bytes("abc\n\n"))
    out.flush()
    reader.join(5000)
    assertEquals(result.map(_.isRight), Some(true))
  }

  test("no hint is printed when the paste arrives at once") {
    val err = new java.io.ByteArrayOutputStream
    Paste.read(
      new java.io.ByteArrayInputStream(bytes("abc\n\n")),
      1000,
      new java.io.PrintStream(err, true, UTF_8),
      Paste.lineCap,
      100L
    )
    Thread.sleep(300)
    assert(!err.toString(UTF_8).contains("Still waiting"), err.toString(UTF_8))
  }
}
