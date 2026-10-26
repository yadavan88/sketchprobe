package sketchprobe.cli

import java.io.{BufferedReader, InputStream, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import sketchprobe.report.{Diagnosis, DiagnosisCode}

/** Reading a sketch that is pasted at a terminal (no argument, no file, standard input is a
  * terminal).
  *
  * A terminal in its normal line mode accepts only so many characters on one line (1,023 on macOS,
  * 4,095 on Linux) and silently drops the rest, together with the Enter that would end the line. A
  * program cannot see that, and cannot switch the mode off from Java without starting another
  * program (which this tool never does). Most real sketches are longer than that once they are
  * text, so this reading is a convenience for small values, and two checks tell the user what to do
  * instead: a line as long as the terminal allows is refused, and a long silence gets a hint.
  */
object Paste {
  val Prompt: String =
    """Paste the sketch (hex, base64 or Mongo JSON), then press Enter twice.
      |A terminal cuts a pasted line at about 1,000 characters. For a larger
      |sketch press Ctrl-C and use: pbpaste | sketchprobe""".stripMargin

  val StillWaiting: String =
    """Still waiting. A paste of more than about 1,000 characters is dropped by
      |the terminal, and Enter then does nothing. Press Ctrl-C and use:
      |pbpaste | sketchprobe     or     sketchprobe --file value.txt""".stripMargin

  /** The longest line a terminal delivers in line mode. */
  def lineCap: Int =
    if (System.getProperty("os.name", "").toLowerCase.contains("mac")) 1023 else 4095

  private val HintAfterMillis = 8000L

  private def tooLong(length: Int): Diagnosis =
    Diagnosis(
      DiagnosisCode.InputUnreadable,
      s"The pasted line is $length characters, the most a terminal accepts on one line, so the rest was probably cut off.",
      Some(
        "Give the value another way: pbpaste | sketchprobe, or sketchprobe \"$(pbpaste)\", or save it and use --file value.txt."
      )
    )

  /** The pasted text: lines until a blank one or the end of input, never more than `limit` bytes. A
    * wrapped paste over several lines is one value, so one line does not end it. The terminal's own
    * paste markers (ESC[200~ and ESC[201~) are not part of it.
    */
  def read(
      stdin: InputStream,
      limit: Int,
      err: PrintStream,
      cap: Int = lineCap,
      hintAfterMillis: Long = HintAfterMillis
  ): Either[Diagnosis, Array[Byte]] = {
    err.println(Prompt)
    @volatile var received = false
    val watchdog = new Thread(() => {
      try {
        Thread.sleep(hintAfterMillis)
        if (!received) err.println(StillWaiting)
      } catch { case _: InterruptedException => () }
    })
    watchdog.setDaemon(true)
    watchdog.start()
    try {
      val reader = new BufferedReader(new InputStreamReader(stdin, UTF_8))
      val text = new StringBuilder
      var seenContent = false
      var lineBlank = true
      var lineLength = 0
      var longest = 0
      var done = false
      // One character at a time, so that a single endless line cannot fill memory either.
      while (!done) {
        val c = reader.read()
        received = true
        if (c < 0) done = true
        else if (c == '\n') {
          if (lineBlank && seenContent) done = true
          else {
            text.append('\n')
            lineBlank = true
            lineLength = 0
          }
        } else {
          text.append(c.toChar)
          lineLength += 1
          longest = math.max(longest, lineLength)
          if (!Character.isWhitespace(c)) {
            lineBlank = false
            seenContent = true
          }
        }
        if (text.length > limit) done = true
      }
      val pasted = text.toString.replace("\u001b[200~", "").replace("\u001b[201~", "")
      if (longest >= cap) Left(tooLong(longest)) else Right(pasted.getBytes(UTF_8))
    } finally watchdog.interrupt()
  }
}
