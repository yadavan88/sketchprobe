# Spike tooling

Programs used for the three spikes in `specs/001-sketch-inspector/research.md` (R13 and the "Spike A, B, C" notes). They were first kept in a scratch directory, which would be lost, so they live here. They are not part of the build and not shipped.

| File | What it does | Run with |
|---|---|---|
| `ReadAll.java` | Reads every corpus blob with datasketches-java 9.0.0 and prints estimate, bounds, counts and quantiles, one line per blob. Compare with the manifests, or with a native build of itself. | JDK 25, library jar on the classpath, a list file (`path`, `family`, `item type`, `seed`, `name`, tab separated) |
| `Retained.java` | Prints the retained Theta hashes and KLL items the library reports, for comparison with our decoder. | same |
| `Forge.java` | Runs hostile blobs through the library in a thread each, with a 5 second limit, and prints outcome, time and allocation. Use `-Xmx256m`. | same |
| `forge_cases.py` | Writes the 75 hostile blobs (23 forged headers, 52 truncations) and the list file that `Forge.java` reads. | `python3 forge_cases.py <out-dir>` |
| `semtest.c` | Shows whether the OS allows named POSIX semaphores, pipes and socket pairs. In the Claude Code tool environment `sem_open` fails with EPERM, which is why native images cannot start there. | `clang semtest.c -o semtest && ./semtest` |

Results and what they decided are in research.md. Task T007 turns `forge_cases.py` into `fixturegen/python/gen_forged.py`, which also records the diagnosis each blob must produce.
