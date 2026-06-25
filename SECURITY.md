# Security Policy

## Threat model & posture

`slopguard-kotlin` is a static analyzer. Its design keeps the attack surface small:

- **No network access.** The tool never opens a socket. It does not phone home,
  fetch rules, or upload reports.
- **No telemetry.** Nothing about your code or usage is collected or transmitted.
- **No source mutation.** The tool only **reads** `.kt` files. It never writes to
  your sources.
- **One subprocess, and only on demand.** In the default `AUTO` coverage mode the
  tool spawns the project's own Gradle test run (`./gradlew <test-task>
  <report-task>`). With `--coverage-file` or `--no-coverage` it spawns nothing.
  Running `AUTO` executes your build's tests and plugins — only point it at
  projects you trust, exactly as you would when running `./gradlew test` yourself.
- **Minimal dependencies.** One third-party runtime dependency,
  `kotlin-compiler-embeddable` (Apache-2.0), used purely as a PSI parser. JSON
  serialization and CLI parsing are hand-rolled on the Kotlin/Java standard
  library. The JaCoCo XML reader disables external DTD/entity loading.

## Supported versions

This is alpha software (v0.1.x). Security fixes are applied to the latest minor
release only.

## Reporting a vulnerability

Please open a private security advisory on the GitHub repository, or email the
maintainers. Do not file public issues for security reports. We aim to
acknowledge reports within a few business days.
