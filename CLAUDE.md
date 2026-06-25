# CLAUDE.md — slopguard-kotlin

Guidance for Claude when working in this repo. Read this first.

## What this is

`slopguard-kotlin` is the **Kotlin / Android port** of slopguard — a CRAP (Change
Risk Anti-Patterns) guardrail. It scores every function/method by **complexity ×
lack-of-coverage** (wCRAP) and emits a text or JSON report you can gate CI on.

**Parity mandate:** `slopguard-kotlin` is one of four sibling ports that must stay
**behaviourally aligned**. The wCRAP formula, the schema-2 JSON shape, the CLI UX
(flags, exit codes, stderr/stdout split), and the error-envelope shape are a
shared contract — don't change them unilaterally here, or you break cross-tool
consumers and drift from the siblings:

- TypeScript (the reference port for intent): https://github.com/JeevanThandi/slopguard-typescript
- Swift: https://github.com/JeevanThandi/SlopGuard-Swift
- Go: https://github.com/JeevanThandi/slopguard-go

## Environment gotcha (important)

There is **no JDK, Gradle or Kotlin on PATH** in the default dev sandbox. A
Temurin 21 JDK was bootstrapped to `~/.local/java-sdk`. Export it before any
Gradle command (the Gradle wrapper downloads the distribution itself):

```bash
export JAVA_HOME="$HOME/.local/java-sdk" && export PATH="$JAVA_HOME/bin:$PATH"
```

## Build / test / run

```bash
./gradlew build               # compile everything + run all tests
./gradlew test                # tests only
./gradlew :app:installDist    # -> app/build/install/slopguard-kotlin/bin/slopguard-kotlin
```

Run it against itself:

```bash
BIN=app/build/install/slopguard-kotlin/bin/slopguard-kotlin
$BIN analyze --path core/src/main/kotlin --no-coverage      # fast, complexity-only
$BIN analyze --path . --project-dir .                       # dogfood whole tool w/ Kover coverage
$BIN analyze --path sample-apps/todolist/src/main/kotlin \
     --project-dir sample-apps/todolist                     # the regression baseline (Kover)
$BIN analyze --path core/src/main/kotlin --no-coverage --json | jq '.methods | sort_by(-.crap)[:10]'
```

Dogfooding runs the project's own `./gradlew test koverXmlReport` and joins the
**aggregated** root report (`build/reports/kover/report.xml`) — Kover also writes
a per-module `report.xml` in each module, so `GradleRunner.pickReportXml` prefers
the shallowest (aggregate) one. Kover aggregation is wired in the root
`build.gradle.kts` via `kover(project(":core"))` etc.

## Architecture

A Gradle multi-module build mirroring the Swift port's target layout. The only
third-party runtime dependency is `kotlin-compiler-embeddable` (a PSI parser).

- **`core/`** — pure analysis, no subprocesses. `Crap.kt` (the formula),
  `Models.kt` (data classes + schema), `analysis/` (the `KotlinParser` PSI
  front-end, `ComplexityVisitor` single-pass cyclomatic+cognitive analyzer,
  `DirectoryAnalyzer`, `Glob`), `aggregation/` (`CoverageProvider`,
  `CrapAggregator`), `formatting/` (hand-rolled `Json` writer + `CrapReportFormatter`),
  `errors/`, `ProgressReporter`, `Version`.
- **`coverage/`** — drives Gradle + JaCoCo **or Kover** (`CoverageTool`).
  `ProjectRootDiscovery`, `GradleRunner` (spawns `./gradlew test <reportTask>
  --continue`; `findReportXml` scans `reports/{jacoco,kover,coverage}`),
  `JacocoReport` (XML parse — Kover emits the same JaCoCo XML format, so one
  parser serves both), `CoverageIndex` (per-line lookup + suffix path
  resolution), `AnalysisPipeline` (orchestrator with AUTO / PREBUILT / NONE).
- **`cli/`** — `Cli.run(args, stdout, stderr): Int`; `analyze` (default) + `version`.
- **`app/`** — `exitProcess(Cli.run(...))` shim, wired to the `application` plugin.
- **`sample-apps/todolist/`** — a standalone Gradle project (its own settings +
  wrapper + Kover) used as a CI regression baseline (9 methods, 0 crappy, 100%
  coverage). Excluded from scans via `**/sample-apps/**`.

## Key invariants — don't break these

- **wCRAP formula** (`core/Crap.kt`): `score(comp, cov) = comp²(1−cov/100)³ + comp`,
  fed `comp = sqrt(cyclomatic × cognitive)`. Default threshold 30.
- **Cyclomatic** counting (base 1): `if`, `for`, `while`, `do`, each non-`else`
  `when` entry, `catch`, elvis `?:`, and each `&&` / `||`. **Cognitive** follows
  the SonarSource 2023 spec (whole `when` = one increment, nesting-amplified,
  boolean-run collapse, labelled jumps fundamental, lambdas/local functions bump
  nesting but get no entry, early exits free). `ComplexityVisitorTest` pins exact
  expected numbers — if you touch the analyzer, those tests are the contract.
- **Lexical type aggregation.** A method belongs to its innermost enclosing type
  (`aggregateTypes` keyed by `(file, qualifiedName)`), matching the Swift/TS ports
  — **not** the receiver-based rollup that is unique to the Go port.
- **JSON keys are emitted alphabetically** (`formatting/Json.kt` sorts every
  object's keys) for diff-stable output mirroring the siblings.
- **`typeName` is nullable** → `null` for free functions, enclosing type name for
  methods. `generatedAt` uses `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC, ms).
- **Coverage is an artifact, never an input.** AUTO mode runs the project's own
  Gradle tests; failing tests don't abort (a note is attached, `--continue`), but
  a run that produces no coverage XML aborts with `coverage_data_missing`.
  `--coverage-tool kover|jacoco` (default **kover**) selects the report task; both
  produce JaCoCo-format XML so `JacocoReport` parses either, and `--coverage-file`
  accepts either. The committed `sample-apps/todolist` uses Kover, so the bare
  baseline command exercises the default path.

## Conventions

- Errors are `SlopguardError` with a stable `code`; surface them via `envelopeFor`.
  Exit codes: 0 ok, 1 error, 2 `--fail-over` exceeded.
- Progress/chatter goes to **stderr** via `ProgressReporter`; the report goes to
  **stdout**. Keep that split so piped JSON stays clean.
- Keep the dependency surface minimal — only `kotlin-compiler-embeddable` at
  runtime. JSON and arg parsing are deliberately hand-rolled on the stdlib.

## When verifying a change

```bash
export JAVA_HOME="$HOME/.local/java-sdk" && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew build
./gradlew :app:installDist
BIN=app/build/install/slopguard-kotlin/bin/slopguard-kotlin
$BIN analyze --path sample-apps/todolist/src/main/kotlin --project-dir sample-apps/todolist \
  --json --quiet | jq '{methods:.summary.methodCount, crappy:.summary.crappyMethodCount}'
# expect {"methods":9,"crappy":0} — the regression baseline
```
