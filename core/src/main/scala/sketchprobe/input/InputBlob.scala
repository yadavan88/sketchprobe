package sketchprobe.input

import sketchprobe.report.{FormatSource, InputAlternative, InputFormat}

/** What the user supplied, after normalization (data-model.md, InputBlob). */
final case class InputBlob(
    bytes: Array[Byte],
    format: InputFormat,
    source: FormatSource,
    alternatives: List[InputAlternative] = Nil,
    notes: List[String] = Nil
)
