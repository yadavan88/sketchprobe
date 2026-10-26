package sketchprobe

class VersionSpec extends munit.FunSuite {
  test("tagline matches the spec (FR-024)") {
    assertEquals(Version.tagline, "inspect and decode Apache DataSketches binaries")
  }
}
