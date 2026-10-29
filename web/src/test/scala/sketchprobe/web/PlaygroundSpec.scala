package sketchprobe.web

import java.nio.file.{Files, Path, Paths}
import java.util.Base64

class PlaygroundSpec extends munit.FunSuite {
  private val corpus: Path = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve("fixtures").resolve("corpus").resolve("java-9.0.0"))
    .find(Files.isDirectory(_))
    .getOrElse(fail("fixtures/corpus/java-9.0.0 not found"))

  private def base64Of(fixture: String): String =
    Base64.getEncoder.encodeToString(Files.readAllBytes(corpus.resolve(fixture)))

  test("an HLL sketch is reported like the command does") {
    val r = Playground.run(base64Of("hll/hll_HLL_4_k12_n1000_compact.bin"), Array())
    assertEquals(r.status, 0)
    assert(r.out.startsWith("HLL sketch"), r.out)
    assertEquals(r.err, "")
  }

  test("options are passed on: KLL with its item type") {
    val r = Playground.run(
      base64Of("kll/kll_double_k200_n10000.bin"),
      Array("--type", "double", "--json")
    )
    assertEquals(r.status, 0)
    assertEquals(ujson.read(r.out)("summary")("n").num, 10000.0)
  }

  test("a Theta sketch in the normal compact layout is read") {
    val r = Playground.run(base64Of("theta/theta_compact_ordered_p05_n1000.bin"), Array())
    assertEquals(r.status, 0)
    assert(r.out.startsWith("Theta Compact sketch"), r.out)
  }

  test("a compressed Theta sketch gets a clear message, not a wrong answer") {
    val r = Playground.run(base64Of("theta/theta_compressed_ordered_n1000.bin"), Array())
    assertEquals(r.status, Playground.NotInBrowserStatus)
    assertEquals(r.out, "")
    assert(r.err.contains("compressed Theta sketch"), r.err)
  }

  test("the same bytes as hex are caught too") {
    val bytes = Files.readAllBytes(corpus.resolve("theta/theta_compressed_ordered_n1000.bin"))
    val hex = bytes.map(b => f"${b & 0xff}%02x").mkString
    val r = Playground.run(hex, Array("--input-format", "hex"))
    assertEquals(r.status, Playground.NotInBrowserStatus)
  }

  test("--file is refused, there is no file to read") {
    val r = Playground.run("x", Array("--file", "/etc/passwd"))
    assertEquals(r.status, 64)
    assert(r.err.contains("--file is not available"), r.err)
  }

  test("a value that starts with a dash is still a value") {
    val r = Playground.run("-abc", Array())
    assertNotEquals(r.status, 64)
  }

  test("garbage is a clear failure, not a crash") {
    val r = Playground.run("AAAAAAAAAAAAAAAAAAAA", Array())
    assertEquals(r.status, 1)
    assert(r.err.nonEmpty)
  }

  test("an empty value is a failure, not a crash") {
    val r = Playground.run("", Array())
    assert(r.status != 0)
  }

  test("runJson returns one object with status, out and err") {
    val j = ujson.read(Playground.runJson("AAAAAAAAAAAAAAAAAAAA", Array()))
    assertEquals(j("status").num, 1.0)
    assertEquals(j("out").str, "")
    assert(j("err").str.nonEmpty)
  }
}
