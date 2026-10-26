package sketchprobe.decode

/** How the blob's length compares with what its layout needs. Both layouts are checked, because the
  * writers disagree about the compact flag (fixtures/README.md, "Writer differences"), so the flag
  * cannot decide.
  */
final case class LengthCheck(actual: Int, expectedCompact: Long, expectedUpdatable: Long) {

  /** The blob has at least the data the layout needs. */
  def complete: Boolean = actual >= expectedCompact

  /** The blob is exactly the compact size or exactly the updatable size. */
  def matches: Boolean = actual == expectedCompact || actual == expectedUpdatable
}
