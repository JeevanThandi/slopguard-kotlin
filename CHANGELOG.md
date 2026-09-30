# Changelog

All notable changes to slopguard-kotlin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and the project adheres to
semantic versioning.

## [Unreleased]

## [0.2.0] — 2026-09-30

### Added
- The new `mutate` subcommand is a mutation tester. It changes the source one
  mutant at a time, runs the project's Gradle tests against each mutant and
  reports the mutants that no test catches. It follows the shared slopguard
  `mutate` contract with the sibling ports. The shared flags, operator ids,
  statuses, schema-1 `reportType: "mutation"` JSON report and exit codes (0,
  1, 2, 130, 143 and 129) are the same in every port. Each port adds its own
  runner flags. This port's are `--gradle-test-task`, `--gradle-report-task`
  and `--coverage-tool`.
  - Nine operators work on the Kotlin PSI tree: `arithmetic`,
    `boolean_literal`, `boundary`, `increment`, `invert_negative`, `logical`,
    `negate_conditional`, `remove_call` and `remove_not`. A `+` or `+=` with
    a string literal or template operand is not mutated. Annotation
    arguments, comments and string text are never mutated.
  - Two baselines run before the mutants. The first runs the exact mutant
    command without a mutant, and it must pass. The second is the `analyze`
    coverage run with Kover or JaCoCo. A mutant on a line that no test
    executes gets `no_coverage` and is not run.
  - Mutant runs use `./gradlew <test-task> --fail-fast --console=plain` with
    an init script. The script switches off coverage reports, coverage
    verification and the JaCoCo agent, so a coverage threshold cannot fake a
    kill. A failed compile task or a Kotlin `e:` diagnostic marks the mutant
    `compile_error`.
  - Each mutant run has a timeout. The default is three times the plain
    baseline time plus 60 seconds. A timeout kills the Gradle client, all its
    descendants and any test JVM of the project that keeps running.
  - A workspace guard protects the sources. Each mutant is written in place,
    so the inode stays. The original bytes and timestamps come back on every
    exit path, including SIGINT, SIGTERM and SIGHUP through a JVM shutdown
    hook. A lock, a journal and a backup in `$TMPDIR/slopguard-mutate/<hash>/`
    let the next run recover a file that a killed run left mutated.
  - Before each mutant write, `mutate` compares the file with the bytes it
    planned the mutants from. When someone has edited the file during the
    run, `mutate` writes nothing more to it, reports the file's remaining
    mutants as `pending` and adds a note that names the file.
  - `slopguard-ignore-mutant` and `slopguard-ignore-mutant(<ids>)` comments
    switch off equivalent mutants.
  - `--fail-under <score>` gates CI on the mutation score. `--dry-run` lists
    the mutants without running them.
- A new `mutation` Gradle module holds the runner, the guard and the pipeline.
  Mutant generation, planning, the report models and the formatter are pure
  and live in `core`.
- The error envelope has four new stable codes: `runner_unavailable`,
  `baseline_failed`, `mutation_in_progress` and `restore_failed`.
- `core` and `coverage` export the pieces that `mutate` reuses: the directory
  walk (`DirectoryAnalyzer.listSources` and `readBytesOrThrow`), the Gradle
  coverage command and report lookup (`GradleRunner`), line-separator
  normalisation (`KotlinParser.normalizeLineSeparators`) and the `generatedAt`
  format (`AnalysisPipeline.TIMESTAMP`).
- CI pins the mutation baseline of `sample-apps/todolist` next to the
  `analyze` baseline: 15 mutants, 15 killed, 0 survived. The CI step also
  checks that the run leaves the sample app's sources unchanged. Two
  sample-app tests were added so that every mutant is killed. The sample
  app's source is unchanged.

### Changed
- `--path` and `--project-dir` expand a leading `~` to the home directory in
  `analyze` and `mutate`, as in the sibling ports.
- `mutate` writes to source files, one mutant at a time, and always restores
  them. `analyze` still never writes to sources. `SECURITY.md` describes the
  new threat model.

### Fixed
- The parser now normalises `\r\n` and `\r` line separators before it parses
  a file, as the compiler does. The PSI lexer treats `\r` as a bad character,
  so `analyze` could mis-parse files with Windows line endings.
- `analyze` reports `sourceRoot` as a normalised path. `--path .` no longer
  adds a trailing `/.`, and `..` segments are resolved.
- `-h` and `--help` now work after `analyze` or `mutate`: they print the usage
  and exit 0. Before, `analyze --help` failed with `invalid_argument`.

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

[Unreleased]: https://github.com/JeevanThandi/slopguard-kotlin/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/JeevanThandi/slopguard-kotlin/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/JeevanThandi/slopguard-kotlin/releases/tag/v0.1.0
