ThisBuild / scalaVersion := "3.9.0" // Scala 3 LTS (released 2026-09-03)
ThisBuild / version := "0.1.0-SNAPSHOT"
// Placeholder: the publishing group id is still an open question (specs/001-sketch-inspector/research.md R10).
ThisBuild / organization := "sketchprobe"
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

// No -release flag: Scala 3.9 emits Java 17 bytecode by default, which runs on the JDK 25 the tool needs (research R13, Spike B).
// Constitution I: classical { } blocks only. -no-indent makes indentation-based code fail to compile.
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-no-indent")

val datasketchesVersion = "9.0.0" // needs JDK 25: it is compiled for Java 25 (research R1, R13)
val ujsonVersion = "4.4.3"
val munitVersion = "1.3.6"
val munitScalacheck = "1.3.1"

// FR-025: the tool is read-only and offline. It reads one input and writes only to standard output and standard
// error, so production code must not use the network, write or delete files, or start processes. The check
// runs before every compile of core and cli, so a violation cannot be built (cli's ReadOnlySpec checks the
// behaviour: nothing is written when the corpus is run).
lazy val checkReadOnlyOffline =
  taskKey[Unit]("Fails if production sources use the network, write files or run programs")

lazy val readOnlyOffline = Seq(
  checkReadOnlyOffline := {
    val forbidden = Seq(
      """java\.net\b|javax\.net\b|\bHttpClient\b|java\.nio\.channels\.(Socket|ServerSocket|Datagram)""".r -> "uses the network",
      """\bFiles\.(write|writeString|createFile|createDirectory|createDirectories|createTempFile|createTempDirectory|delete|deleteIfExists|move|copy|newOutputStream|newBufferedWriter)\b|\bFileOutputStream\b|\bFileWriter\b|\bRandomAccessFile\b""".r -> "writes or deletes files",
      """\bProcessBuilder\b|scala\.sys\.process|Runtime\.getRuntime\.exec""".r -> "starts a program"
    )
    val problems = (Compile / unmanagedSources).value.flatMap { f =>
      IO.readLines(f).zipWithIndex.flatMap { case (line, i) =>
        val code = line.trim
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) Nil
        else
          forbidden.collect {
            case (re, what) if re.findFirstIn(line).isDefined =>
              s"${f.getName}:${i + 1} $what: $code"
          }
      }
    }
    if (problems.nonEmpty)
      sys.error("FR-025 (read-only, offline) violated:\n  " + problems.mkString("\n  "))
  },
  Compile / compile := ((Compile / compile) dependsOn checkReadOnlyOffline).value
)

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitVersion % Test,
    "org.scalameta" %% "munit-scalacheck" % munitScalacheck % Test
  )
)

// No I/O. Sketches are read by datasketches-java, behind our own preflight checks (specs/001-sketch-inspector/plan.md).
lazy val core = (project in file("core"))
  .settings(
    name := "sketchprobe-core",
    libraryDependencies ++= Seq(
      "org.apache.datasketches" % "datasketches-java" % datasketchesVersion,
      "com.lihaoyi" %% "ujson" % ujsonVersion
    ),
    testDeps,
    readOnlyOffline,
    // The robustness sweep (SC-003) must show that a forged blob cannot exhaust memory, so core's tests run in
    // their own JVM with a small heap. RobustnessSpec checks that this setting is really in effect.
    Test / fork := true,
    Test / javaOptions += "-Xmx256m"
  )

lazy val cli = (project in file("cli"))
  .dependsOn(
    core % "compile->compile;test->test"
  ) // the cli tests reuse the corpus loader from core's tests
  .settings(
    name := "sketchprobe-cli",
    Compile / mainClass := Some("sketchprobe.cli.Main"),
    testDeps,
    readOnlyOffline,
    // The fat jar is the input to packaging/build-jlink.sh and packaging/build-native.sh.
    assembly / assemblyJarName := "sketchprobe.jar",
    assembly / mainClass := Some("sketchprobe.cli.Main"),
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", xs @ _*)
          if xs.lastOption
            .exists(n => n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")) =>
        MergeStrategy.discard
      case PathList("META-INF", _*) => MergeStrategy.first
      case "module-info.class"      => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )

// What the browser page calls (docs/web.md). Plain Scala on top of cli, so it is tested like the rest; the thin
// Java bridge that exports it to JavaScript is compiled by packaging/build-web.sh, because the Web Image API
// ships inside Oracle GraalVM and is not on Maven Central.
lazy val web = (project in file("web"))
  .dependsOn(cli)
  .settings(
    name := "sketchprobe-web",
    testDeps,
    readOnlyOffline
  )

// fixturegen is intentionally not an sbt module: it needs a different JDK and library version per
// generator (6.2.0 and 8.0.0 on JDK 21, 9.0.0 on JDK 25), so each generator has its own script
// under fixturegen/ (research R7).

lazy val root = (project in file("."))
  .aggregate(core, cli, web)
  .settings(
    name := "sketchprobe",
    publish / skip := true
  )
