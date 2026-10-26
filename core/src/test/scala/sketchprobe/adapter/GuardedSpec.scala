package sketchprobe.adapter

import sketchprobe.report.DiagnosisCode

class GuardedSpec extends munit.FunSuite {

  private def code[A](body: => A): Option[DiagnosisCode] =
    Guarded.run("test sketch")(body).left.toOption.map(_.code)

  test("a value that is computed normally is returned unchanged") {
    assertEquals(Guarded.run("test sketch")(40 + 2), Right(42))
  }

  test("reading past the end of a blob means the blob is incomplete") {
    assertEquals(
      code(throw new IndexOutOfBoundsException("offset 28")),
      Some(DiagnosisCode.Incomplete)
    )
    assertEquals(code(throw new ArrayIndexOutOfBoundsException(5)), Some(DiagnosisCode.Incomplete))
  }

  test("running out of memory means the header claimed more data than the blob holds") {
    assertEquals(
      code(throw new OutOfMemoryError("Java heap space")),
      Some(DiagnosisCode.Incomplete)
    )
  }

  test("a library check that rejects the content means it is not a valid sketch") {
    assertEquals(code(throw new IllegalArgumentException("bad k")), Some(DiagnosisCode.NotASketch))
    assertEquals(code(throw new IllegalStateException("corrupt")), Some(DiagnosisCode.NotASketch))
    assertEquals(code(throw new ArithmeticException("overflow")), Some(DiagnosisCode.NotASketch))
    assertEquals(code(throw new NegativeArraySizeException("-5")), Some(DiagnosisCode.NotASketch))
  }

  test("any other runtime failure is still a diagnosis, never an exception") {
    assertEquals(
      code(throw new RuntimeException("something unexpected")),
      Some(DiagnosisCode.NotASketch)
    )
    assertEquals(code(throw new StackOverflowError()), Some(DiagnosisCode.NotASketch))
  }

  test(
    "the diagnosis names what was being read and gives a next step, and never shows a stack trace"
  ) {
    val d = Guarded
      .run("HLL sketch")(throw new IndexOutOfBoundsException("Out of bound access on segment"))
      .left
      .toOption
      .get
    assert(d.reason.contains("HLL sketch"), d.reason)
    assert(d.nextStep.isDefined)
    assert(!d.reason.contains("\tat ") && !d.reason.contains("Exception"), d.reason)
  }

  // munit's intercept treats these as fatal and will not catch them, so check with a plain try/catch.
  private def propagated(body: => Any): Option[Throwable] =
    try {
      Guarded.run("test sketch")(body)
      None
    } catch {
      case e: Throwable => Some(e)
    }

  test(
    "a class-loading problem is an environment error, not a verdict on the blob, and is not swallowed"
  ) {
    // Running on a JDK that is too old for the library is a setup mistake. Hiding it behind "not a sketch" would mislead.
    val e = propagated(throw new UnsupportedClassVersionError("class file version 69"))
    assert(e.exists(_.isInstanceOf[UnsupportedClassVersionError]), e.toString)
  }

  test("interrupting the thread is not swallowed") {
    val e = propagated(throw new InterruptedException())
    assert(e.exists(_.isInstanceOf[InterruptedException]), e.toString)
  }
}
