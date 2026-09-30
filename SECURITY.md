# Security Policy

## Threat model & posture

`slopguard-kotlin` is a static analyzer (`analyze`) and a mutation tester (`mutate`). Its design keeps the attack surface small:

- **No network access.** The tool never opens a socket. It does not phone home,
  fetch rules, or upload reports.
- **No telemetry.** Nothing about your code or usage is collected or transmitted.
- `analyze` never writes to your sources. It only reads `.kt` files.
- `mutate` writes to your sources and always puts them back. It writes one
  mutant at a time into a source file, runs the tests, and writes the original
  bytes and timestamps back. It truncates and rewrites the file, so the inode,
  the mode and hard links stay. It restores the file on normal completion, on
  errors, and on SIGINT, SIGTERM and SIGHUP through a JVM shutdown hook.
  `--dry-run` writes nothing.
- A guard directory in the OS temp dir, `$TMPDIR/slopguard-mutate/<hash>/`,
  protects each `mutate` run. It is never inside the project. It holds a lock
  (one run per project), a journal that names the mutated file and the
  mutant's sha256, and a backup of the original. After a hard kill (for
  example SIGKILL), the next run restores the file only when it still holds
  exactly the recorded mutant. Otherwise it keeps the backup and reports where it
  is. After a power loss, recovery works only if the OS temp directory survives
  the restart. If it does not, check the files under `--path` against version
  control.
- Subprocesses start only on demand. In the default `AUTO` coverage mode,
  `analyze` starts the project's own Gradle test run (`./gradlew <test-task>
  <report-task>`). With `--coverage-file` or `--no-coverage` it starts nothing.
  `mutate` starts the same Gradle build for its two baselines and once per
  mutant. The plain baseline and the mutant runs add a temporary init script in
  the OS temp dir, which switches off coverage reports and verification. A
  timed-out run is killed together with every process it started. Running
  Gradle executes your build's tests and plugins. Only point the tool at
  projects you trust, exactly as you would when running `./gradlew test`
  yourself.
- **Minimal dependencies.** One third-party runtime dependency,
  `kotlin-compiler-embeddable` (Apache-2.0), used purely as a PSI parser. JSON
  serialization and CLI parsing are hand-rolled on the Kotlin/Java standard
  library. The JaCoCo XML reader disables external DTD/entity loading.

## Supported versions

This is alpha software (v0.2.x). Security fixes are applied to the latest minor
release only.

## Reporting a vulnerability

Please open a private security advisory on the GitHub repository, or email the
maintainers. Do not file public issues for security reports. We aim to
acknowledge reports within a few business days.
