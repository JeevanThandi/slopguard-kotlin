# Changelog

All notable changes to slopguard-kotlin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and the project adheres to
semantic versioning.

## [0.1.0] — 2026-06-22

Initial alpha — the Kotlin / Android sibling of slopguard-swift,
slopguard-typescript and slopguard-go.

### Added
- wCRAP analyzer over the Kotlin compiler's PSI front-end: cyclomatic (McCabe)
  and cognitive (SonarSource 2023) complexity computed in a single tree walk,
  blended as `sqrt(cyclomatic × cognitive)`.
- Lexical-nesting type aggregation (a method belongs to its innermost enclosing
  type), matching the Swift/TypeScript ports.
- Coverage pipeline driving Gradle + **Kover (default) or JaCoCo**
  (`--coverage-tool`), with `AUTO` / `--coverage-file` (prebuilt XML) /
  `--no-coverage` modes. Kover's `koverXmlReport` emits XML by default (lowest
  config); both tools share the JaCoCo XML format so a single parser and
  `--coverage-file` handle either. The report finder scans the `reports/kover`,
  `reports/jacoco` and AGP-native `reports/coverage` directories. Coverage is an
  artifact of the analysis, never a user input.
- CLI: `analyze` (default) + `version`, with `--path`, `--threshold`,
  `--project-dir`, `--no-coverage`, `--coverage-file`, `--coverage-tool`,
  `--gradle-test-task`, `--gradle-report-task`, `--include`, `--exclude`,
  `--no-default-excludes`, `--json`, `--fail-over`, `--verbose`, `--quiet`. Exit
  codes 0 / 1 / 2.
- Stable schema-2 JSON report (alphabetically sorted keys, pretty-printed) and a
  human-readable text report, shared with the sibling ports.
- Default excludes for `build/`, `.gradle/`, generated code, `*Test.kt`,
  `src/test` / `src/androidTest`, and sample apps.
- `sample-apps/todolist` regression baseline (9 methods, 0 crappy, 100% coverage).
- One third-party runtime dependency (`kotlin-compiler-embeddable`, as a parser);
  no network, no telemetry, no source mutation.
