# Building and packaging

You need **JDK 25**: the Apache DataSketches library the tool uses is compiled for Java 25. Point `JAVA_HOME` at it before running sbt:

```bash
export JAVA_HOME=/path/to/jdk-25     # for example a Temurin 25
sbt test
```

On an older JDK the build compiles, but the tests that load the library fail with `UnsupportedClassVersionError`.

## Running it from a checkout

```bash
sbt cli/assembly                                   # the fat jar: cli/target/scala-3.9.0/sketchprobe.jar
pbpaste | java -jar cli/target/scala-3.9.0/sketchprobe.jar
```

## The two distributions (`packaging/`)

Both start from the fat jar, and `packaging/smoke-test.sh` checks either one: a decode per family compared with the reports the tests hold as golden output, the version, the failure statuses, and all the hostile blobs in `fixtures/forged/` (none may crash or hang).

| | Native executable | `jlink` runtime |
|---|---|---|
| Needs to run | nothing | nothing (it carries its own minimal Java) |
| Size | about 19 MB, one file | about 78 MB directory (35 MB of it the runtime) |
| Start-up (one decode, Apple silicon) | about 8 ms | about 100 ms (170 ms for a plain `java -jar`) |
| Build needs | GraalVM 25 | JDK 25 |

```bash
sbt cli/assembly

# native executable, in dist/native/ (point GRAALVM_HOME at a GraalVM 25, for example GraalVM CE 25.0.2)
GRAALVM_HOME=/path/to/graalvm-25 ./packaging/build-native.sh
./packaging/smoke-test.sh dist/native/sketchprobe

# jlink runtime, in dist/jlink/ (run dist/jlink/bin/sketchprobe; the directory can be moved)
JAVA_HOME=/path/to/jdk-25 ./packaging/build-jlink.sh
./packaging/smoke-test.sh dist/jlink/bin/sketchprobe
```

Notes:
- Run the scripts as `./packaging/build-native.sh`, not as `bash packaging/build-native.sh`. They use the system `/bin/bash`; on an Apple silicon Mac an Intel Homebrew `bash` that comes first on `PATH` makes every child process run as x86_64, and the arm64 GraalVM's toolchain check then fails ("missing compatible architecture"). The script says so and stops.
- The native image is built with `-H:-InstallExitHandlers` (no SIGINT or SIGTERM handler: the tool has nothing to shut down). Without it, the image needs a named POSIX semaphore at start-up, which some sandboxes forbid, and it crashes in them with `CSunMiscSignal.open() failed`. With it, the same executable runs everywhere we tried, including such a sandbox.
- The heap is capped at 512 MB in both (`-R:MaxHeapSize` and `-Xmx512m`), so a hostile input cannot take the machine's memory.
- `-march=compatibility` makes the native executable run on any CPU of its architecture, not only the one that built it.
- The jlink launcher uses a class-data archive (`lib/sketchprobe.jsa`) to start faster; if the directory is moved it is ignored and rebuilt when the directory is writable.

## Releases

A tag `vX.Y.Z` that matches `Version.current` in `core/src/main/scala/sketchprobe/Version.scala` starts `.github/workflows/release.yml`. It builds the native executable (static musl on Linux, so it runs on any x86-64 Linux), the `jlink` runtime and the fat jar, smoke-tests each, and publishes the archives, `SHA256SUMS` and the Homebrew formula. The `Native` workflow runs the same build and checks on every push, and compares the native output with the JVM's on every corpus, forged and variant input.
