package sketchprobe.cli

/** The checker used by the JSON specs must catch what it claims to, or a passing schema spec means
  * nothing.
  */
class MiniJsonSchemaSpec extends munit.FunSuite {
  private def schema(s: String): ujson.Value = ujson.read(s)
  private def errors(s: String, instance: String): List[String] =
    MiniJsonSchema.validate(schema(s), ujson.read(instance))

  test("type, required, enum, const, minimum, maximum and pattern each catch a violation") {
    val s =
      """{"type":"object","required":["a"],"properties":{
        |"a":{"type":"integer","minimum":1,"maximum":3},
        |"b":{"enum":["x","y"]},"c":{"const":1},"d":{"type":"string","pattern":"^[0-9a-f]*$"}}}""".stripMargin
    assertEquals(errors(s, """{"a":2,"b":"x","c":1,"d":"ff"}"""), Nil)
    assert(errors(s, """{"b":"x"}""").exists(_.contains("missing 'a'")))
    assert(errors(s, """{"a":"two"}""").exists(_.contains("expected integer")))
    assert(errors(s, """{"a":0}""").exists(_.contains("below the minimum")))
    assert(errors(s, """{"a":9}""").exists(_.contains("above the maximum")))
    assert(errors(s, """{"a":1,"b":"z"}""").exists(_.contains("not one of")))
    assert(errors(s, """{"a":1,"c":2}""").exists(_.contains("constant")))
    assert(errors(s, """{"a":1,"d":"xyz"}""").exists(_.contains("does not match")))
  }

  test("a whole number is an integer and a number; a fraction is only a number") {
    assertEquals(errors("""{"type":"integer"}""", "3"), Nil)
    assertEquals(errors("""{"type":"integer"}""", "3.0"), Nil)
    assert(errors("""{"type":"integer"}""", "3.5").nonEmpty)
    assertEquals(errors("""{"type":["number","string"]}""", "\"x\""), Nil)
  }

  test("items are checked one by one, with the path of the bad one") {
    val e = errors("""{"type":"array","items":{"type":"string"}}""", """["a",1,"c"]""")
    assertEquals(e.size, 1)
    assert(e.head.startsWith("$[1]"), e.head)
  }

  test("if/then/else picks the branch the condition selects") {
    val s =
      """{"allOf":[{"if":{"properties":{"status":{"const":0}}},"then":{"required":["summary"]},"else":{"required":["diagnosis"]}}]}"""
    assertEquals(errors(s, """{"status":0,"summary":{}}"""), Nil)
    assert(errors(s, """{"status":0}""").exists(_.contains("summary")))
    assertEquals(errors(s, """{"status":2,"diagnosis":{}}"""), Nil)
    assert(errors(s, """{"status":2}""").exists(_.contains("diagnosis")))
  }

  test("a keyword it does not implement is reported, not skipped") {
    assert(
      errors("""{"oneOf":[{"type":"string"}]}""", "\"x\"").exists(_.contains("unsupported keyword"))
    )
  }
}
