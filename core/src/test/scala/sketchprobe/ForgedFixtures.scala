package sketchprobe

import java.nio.file.{Files, Path, Paths}

/** One hostile blob from fixtures/forged and what the tool must do with it. */
final case class ForgedCase(
    name: String,
    family: String,
    itemType: String,
    seed: String,
    group: String,
    expect: String,
    what: String,
    bytes: Array[Byte]
) {
  def isForged: Boolean = group == "forged"
  def isTruncated: Boolean = group == "truncated"

  /** `expect` is a diagnosis code label, or `accepted` for a soft inconsistency that must still
    * decode.
    */
  def mustBeAccepted: Boolean = expect == "accepted"
  override def toString: String = s"forged/$name"
}

/** Loads the hostile-blob set written by fixturegen/python/gen_forged.py. */
object ForgedFixtures {
  private def root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("fixtures").resolve("forged"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "fixtures/forged not found above " + Paths.get("").toAbsolutePath
        )
      )

  lazy val everything: Vector[ForgedCase] = {
    val manifest = ujson.read(Files.readString(root.resolve("manifest.json")))
    manifest("cases").arr.toVector.map { c =>
      ForgedCase(
        name = c("name").str,
        family = c("family").str,
        itemType = c("itemType").str,
        seed = c("seed").str,
        group = c("group").str,
        expect = c("expect").str,
        what = c("what").str,
        bytes = Files.readAllBytes(root.resolve(c("file").str))
      )
    }
  }

  /** The hostile blobs for the families the tool decodes today (see CorpusFixtures.Decodable). */
  lazy val all: Vector[ForgedCase] =
    everything.filter(c => CorpusFixtures.Decodable.contains(c.family))

  def family(f: String): Vector[ForgedCase] = everything.filter(_.family == f)
}
