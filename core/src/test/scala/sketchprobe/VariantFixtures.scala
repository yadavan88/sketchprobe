package sketchprobe

import java.nio.file.{Files, Path, Paths}

/** One text form of a sketch from fixtures/variants, and the input format the tool must report. */
final case class Variant(file: String, format: String, bytes: Array[Byte]) {
  def text: String = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
}

/** A corpus blob with every pasted form of it, as written by fixturegen/python/encode_variants.py.
  */
final case class VariantSet(
    name: String,
    source: Array[Byte],
    args: List[String],
    variants: Vector[Variant]
) {
  def variant(file: String): Variant =
    variants.find(_.file == file).getOrElse(throw new NoSuchElementException(s"$name/$file"))
}

object VariantFixtures {
  private def root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("fixtures").resolve("variants"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "fixtures/variants not found above " + Paths.get("").toAbsolutePath
        )
      )

  private def corpusRoot: Path = root.getParent.resolve("corpus")

  lazy val all: Vector[VariantSet] = {
    val manifest = ujson.read(Files.readString(root.resolve("manifest.json")))
    manifest.arr.toVector.map { m =>
      val name = m("name").str
      VariantSet(
        name,
        Files.readAllBytes(corpusRoot.resolve(m("source").str)),
        m("args").arr.toList.map(_.str),
        m("variants").arr.toVector.map { v =>
          Variant(
            v("file").str,
            v("format").str,
            Files.readAllBytes(root.resolve(name).resolve(v("file").str))
          )
        }
      )
    }
  }

  def named(name: String): VariantSet =
    all.find(_.name == name).getOrElse(throw new NoSuchElementException(name))
}
