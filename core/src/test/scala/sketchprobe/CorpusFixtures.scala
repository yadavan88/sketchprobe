package sketchprobe

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** One blob from fixtures/corpus together with its manifest. */
final case class Fixture(
    writer: String,
    family: String,
    name: String,
    bytes: Array[Byte],
    manifest: ujson.Value
) {
  def expected: ujson.Value = manifest("expected")
  def header: ujson.Value = manifest("header")
  def params: ujson.Value = manifest("params")
  override def toString: String = s"$writer/$family/$name"
}

/** Loads every blob and manifest under fixtures/corpus (fixtures/README.md). */
object CorpusFixtures {

  /** Walk up from the working directory until fixtures/corpus exists (sbt may run from a
    * sub-project).
    */
  private def corpusRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("fixtures").resolve("corpus"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "fixtures/corpus not found above " + Paths.get("").toAbsolutePath
        )
      )

  private def dirs(p: Path): Vector[Path] = {
    val s = Files.list(p)
    try s.iterator().asScala.filter(Files.isDirectory(_)).toVector.sortBy(_.getFileName.toString)
    finally s.close()
  }

  /** Families the tool decodes today. The corpus also holds `frequency` blobs (BF1), which the
    * specs for the other families must not see until the Frequency adapter exists (task T075).
    */
  val Decodable: Set[String] = Set("hll", "theta", "kll", "frequency", "quantiles")

  lazy val everything: Vector[Fixture] =
    for {
      writer <- dirs(corpusRoot)
      family <- dirs(writer)
      manifest <- Files
        .list(family)
        .iterator()
        .asScala
        .toVector
        .filter(_.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)
    } yield {
      val name = manifest.getFileName.toString.stripSuffix(".json")
      val bin = family.resolve(name + ".bin")
      Fixture(
        writer.getFileName.toString,
        family.getFileName.toString,
        name,
        Files.readAllBytes(bin),
        ujson.read(Files.readString(manifest))
      )
    }

  /** Every corpus blob the tool can decode. */
  lazy val all: Vector[Fixture] = everything.filter(f => Decodable.contains(f.family))

  def family(f: String): Vector[Fixture] = everything.filter(_.family == f)

  /** Relative comparison. Exact equality does not hold across implementations (research R12). */
  def closeTo(actual: Double, expected: Double, relTol: Double = 1e-12): Boolean =
    if actual == expected then true
    else {
      val scale = math.max(math.abs(actual), math.abs(expected))
      math.abs(actual - expected) <= relTol * math.max(1.0, scale)
    }
}
