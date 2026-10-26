package sketchprobe.decode

class ByteReaderSpec extends munit.FunSuite {
  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  test("little-endian reads") {
    val r = new ByteReader(bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x88))
    assertEquals(r.u8(0), Right(1))
    assertEquals(r.u8(7), Right(0x88)) // unsigned
    assertEquals(r.u16(0), Right(0x0201))
    assertEquals(r.i32(0), Right(0x04030201))
    assertEquals(r.i64(0), Right(0x8807060504030201L))
  }

  test("floating point reads use IEEE bit patterns") {
    val f = java.lang.Float.floatToIntBits(1.0f)
    val rf = new ByteReader(bytes(f & 0xff, (f >> 8) & 0xff, (f >> 16) & 0xff, (f >> 24) & 0xff))
    assertEquals(rf.f32(0), Right(1.0f))
    val d = java.lang.Double.doubleToLongBits(996.18)
    val rd =
      new ByteReader((0 until 8).map(i => ((d >> (8 * i)) & 0xff).toInt).map(_.toByte).toArray)
    assertEquals(rd.f64(0), Right(996.18))
  }

  test("reads past the end return an error and never throw") {
    val r = new ByteReader(bytes(1, 2, 3))
    assertEquals(r.u8(3), Left(OutOfBounds(3, 1, 3)))
    assertEquals(r.u16(2), Left(OutOfBounds(2, 2, 3)))
    assertEquals(r.i32(0), Left(OutOfBounds(0, 4, 3)))
    assertEquals(r.i64(0), Left(OutOfBounds(0, 8, 3)))
  }

  test("negative and overflowing offsets are errors, not exceptions") {
    val r = new ByteReader(bytes(1, 2, 3, 4))
    assert(r.u8(-1).isLeft)
    assert(r.i32(Int.MaxValue).isLeft)
    assert(r.i64(Int.MaxValue - 2).isLeft)
  }

  test("empty input") {
    val r = new ByteReader(Array.emptyByteArray)
    assert(r.u8(0).isLeft)
    assertEquals(r.prefix(24), Vector.empty[Byte])
  }
}
