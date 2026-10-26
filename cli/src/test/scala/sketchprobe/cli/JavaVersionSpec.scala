package sketchprobe.cli

class JavaVersionSpec extends munit.FunSuite {
  test("Java 25 and newer pass") {
    assertEquals(JavaVersion.problem(25), None)
    assertEquals(JavaVersion.problem(26), None)
  }

  test("an older Java gets one reason and one next step, with no stack trace") {
    val text = JavaVersion.problem(21).getOrElse(fail("expected a problem"))
    assert(text.startsWith("sketchprobe: This needs Java 25 or newer, but this is Java 21."), text)
    assert(text.contains("next step:"), text)
    assert(!text.contains("Exception"), text)
  }
}
