package sketchprobe.cli

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import sketchprobe.CorpusFixtures
import sketchprobe.cli.CliHarness.run

/** The tool is read-only (spec FR-025): it reads its input and writes only to standard output and
  * standard error. The build also refuses production code that uses the network, writes files or
  * starts programs (`checkReadOnlyOffline` in build.sbt).
  */
class ReadOnlySpec extends munit.FunSuite {
  private def digest(p: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(p))
      .map("%02x".format(_))
      .mkString

  /** Every file under `dir` with its size and checksum, and every directory. */
  private def snapshot(dir: Path): Map[String, String] = {
    val s = Files.walk(dir)
    try
      s.iterator()
        .asScala
        .map { p =>
          val rel = dir.relativize(p).toString
          rel -> (if (Files.isDirectory(p)) "dir" else s"${Files.size(p)} ${digest(p)}")
        }
        .toMap
    finally s.close()
  }

  private def names(dir: Path): Set[String] = {
    val s = Files.list(dir)
    try s.iterator().asScala.map(_.getFileName.toString).toSet
    finally s.close()
  }

  test(
    "running the CLI as a user does, on files in a directory, changes nothing: no file is created, changed or removed"
  ) {
    val dir = Files.createTempDirectory("sketchprobe-readonly")
    try {
      val files = CorpusFixtures.all.zipWithIndex.map { case (f, i) =>
        val p = dir.resolve(f"blob$i%04d.bin")
        Files.write(p, f.bytes)
        (p, f)
      }
      val before = snapshot(dir)
      val cwdBefore = names(java.nio.file.Paths.get("").toAbsolutePath)
      files.foreach { case (p, f) =>
        val typeArgs =
          if (Set("kll", "frequency").contains(f.family)) Seq("--type", f.params("itemType").str)
          else Nil
        val human = run(Array.emptyByteArray, (typeArgs ++ Seq("--file", p.toString))*)
        assertEquals(human.status, 0, s"$f: ${human.err}")
        val json = run(
          Array.emptyByteArray,
          (Seq("--json", "--show-hashes", "--show-items") ++ typeArgs ++ Seq("--file", p.toString))*
        )
        assertEquals(json.status, 0, s"$f: ${json.err}")
      }
      assertEquals(snapshot(dir), before, "the directory the files were read from changed")
      assertEquals(
        names(java.nio.file.Paths.get("").toAbsolutePath),
        cwdBefore,
        "a file appeared in the working directory"
      )
    } finally {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      finally s.close()
    }
  }

  test("failures write nothing either: a missing file, a directory, hostile input") {
    val dir = Files.createTempDirectory("sketchprobe-readonly-fail")
    try {
      val before = snapshot(dir)
      assertEquals(
        run(Array.emptyByteArray, "--file", dir.resolve("missing.bin").toString).status,
        1
      )
      assertEquals(run(Array.emptyByteArray, "--file", dir.toString).status, 1)
      assertEquals(run("not a sketch".getBytes, "--json").status, 1)
      assertEquals(snapshot(dir), before)
    } finally Files.deleteIfExists(dir)
  }

  test(
    "a success writes nothing to standard error, and a failure nothing to standard output unless it decoded part of the sketch"
  ) {
    val hll = CorpusFixtures.family("hll").find(_.name == "hll_HLL_8_k12_n1000_compact").get.bytes
    assertEquals(run(hll).err, "")
    assertEquals(run(Array[Byte](1, 2, 3)).out, "")
  }
}
