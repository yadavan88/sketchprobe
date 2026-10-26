package sketchprobe.decode

/** A read ran past the end of the blob. Decoders turn this into an "incomplete" diagnosis. */
final case class OutOfBounds(offset: Int, length: Int, available: Int) {
  def message: String =
    s"needed $length bytes at offset $offset but the blob has only $available bytes"
}

/** Bounds-checked little-endian reads. Never throws: every read returns an Either (FR-019). */
final class ByteReader(val bytes: Array[Byte]) {
  def size: Int = bytes.length

  def has(offset: Int, length: Int): Boolean =
    offset >= 0 && length >= 0 && offset.toLong + length <= bytes.length.toLong

  private def need(offset: Int, length: Int): Either[OutOfBounds, Unit] =
    if has(offset, length) then Right(()) else Left(OutOfBounds(offset, length, bytes.length))

  def u8(offset: Int): Either[OutOfBounds, Int] =
    need(offset, 1).map(_ => bytes(offset) & 0xff)

  def u16(offset: Int): Either[OutOfBounds, Int] =
    need(offset, 2).map(_ => (bytes(offset) & 0xff) | ((bytes(offset + 1) & 0xff) << 8))

  def i32(offset: Int): Either[OutOfBounds, Int] =
    need(offset, 4).map { _ =>
      (bytes(offset) & 0xff) | ((bytes(offset + 1) & 0xff) << 8) |
        ((bytes(offset + 2) & 0xff) << 16) | ((bytes(offset + 3) & 0xff) << 24)
    }

  def i64(offset: Int): Either[OutOfBounds, Long] =
    need(offset, 8).map { _ =>
      var v = 0L
      var i = 7
      while i >= 0 do {
        v = (v << 8) | (bytes(offset + i) & 0xffL)
        i -= 1
      }
      v
    }

  def f32(offset: Int): Either[OutOfBounds, Float] =
    i32(offset).map(java.lang.Float.intBitsToFloat)

  def f64(offset: Int): Either[OutOfBounds, Double] =
    i64(offset).map(java.lang.Double.longBitsToDouble)

  /** Up to `max` bytes from the start, for the raw-header display. Never fails. */
  def prefix(max: Int): Vector[Byte] = bytes.take(max).toVector
}
