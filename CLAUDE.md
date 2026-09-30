# CLAUDE.md — slopguard-kotlin

Guidance for Claude when working in this repo. Read this first.

## What this is

`slopguard-kotlin` is the **Kotlin / Android port** of slopguard — a CRAP (Change
Risk Anti-Patterns) guardrail. It scores every function/method by **complexity ×
lack-of-coverage** (wCRAP) and emits a text or JSON report you can gate CI on.

It also ships **`mutate`**, a mutation tester: it writes one small change (a
mutant) at a time into the source, runs the project's Gradle tests, restores the
file, and reports the mutants no test catches.

**Parity mandate:** `slopguard-kotlin` is one of the sibling ports that must stay
**behaviourally aligned**. The wCRAP formula, the schema-2 JSON shape, the CLI UX
(flags, exit codes, stderr/stdout split), and the error-envelope shape are a
shared contract — don't change them unilaterally here, or you break cross-tool
consumers and drift from the siblings. `mutate` follows its own shared contract
with all five ports (TypeScript, Go, Python, Kotlin, Swift): the same shared
flags, operator ids, statuses, schema-1 `reportType: "mutation"` JSON, notes,
exit codes and error codes. Each port adds its own runner flags (here
`--gradle-test-task`, `--gradle-report-task` and `--coverage-tool`). The
guard-directory layout is shared with the other in-place ports (TypeScript,
Python, Swift). Go mutates through `go test -overlay` and has no guard directory.
The TypeScript port (`src/mutation/`) is the reference:

- TypeScript (the reference port for intent): https://github.com/JeevanThandi/slopguard-typescript
- Swift: https://github.com/JeevanThandi/SlopGuard-Swift
- Go: https://github.com/JeevanThandi/slopguard-go
- Python: https://github.com/JeevanThandi/slopguard-python

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
$BIN mutate --path sample-apps/todolist/src/main/kotlin \
     --project-dir sample-apps/todolist                     # the mutation baseline (15 killed)
$BIN mutate --path core/src/main/kotlin/dev/slopguard/core/Crap.kt --project-dir . \
     --gradle-test-task :core:test --gradle-report-task :core:koverXmlReport   # dogfood one file
```

`mutate` runs one Gradle test run per mutant, so dogfood it on one file with a
module-scoped test task, as above. `--dry-run` lists the mutants for free.

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
- **`core/mutation/`** — pure mutant planning, no subprocesses: `MutationOperator`
  (the nine shared ids), `MutantGenerator` (PSI walk → `MutantSite`s),
  `IgnoreMarkers`, `MutationPlanner` (walks with `DirectoryAnalyzer.listSources`,
  names each mutant's method with `ComplexityVisitor`, sorts), `MutationModels`
  (statuses, `MutationSummary` score maths, `MutationReport`) and `OffsetMap`
  (parsed-text → original-text offsets). `formatting/MutationReportFormatter`
  renders text and JSON.
- **`mutation/`** — the only module that writes to user sources. `WorkspaceGuard`
  (lock + journal + backup in `$TMPDIR/slopguard-mutate/<hash>/`, in-place write,
  restore, stale-lock recovery), `CommandRunner` (timeouts, process-tree kill,
  `CI=1`), `GradleMutantRunner` (mutant command + coverage-disabling init script,
  compile-error detection, coverage baseline via `GradleRunner`, lingering
  test-worker cleanup) and `MutationPipeline` (plan → guard → plain baseline →
  coverage baseline → mutants → report, with a JVM shutdown hook for signals).
- **`cli/`** — `Cli.run(args, stdout, stderr): Int`; `analyze` (default), `mutate`
  (`MutateCommand`) + `version`.
- **`app/`** — `exitProcess(Cli.run(...))` shim, wired to the `application` plugin.
- **`sample-apps/todolist/`** — a standalone Gradle project (its own settings +
  wrapper + Kover) used as a CI regression baseline (`analyze`: 9 methods, 0
  crappy, 100% coverage; `mutate`: 15 mutants, 15 killed, 0 survived). Excluded
  from scans via `**/sample-apps/**`. Tests that mutate it must use a temporary
  copy (`SampleAppEndToEndTest` does): mutants are written in place, and other
  builds may use the checked-in fixture at the same time.

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
- **`mutate` never leaves a mutant behind.** Every in-place write goes through
  `WorkspaceGuard.withMutant` (journal first, truncate + write, restore bytes and
  timestamps in `finally`); the shutdown hook covers SIGINT / SIGTERM / SIGHUP
  (exit 130 / 143 / 129). Keep the guard layout (`lock`, `journal.json`,
  `original`). The TypeScript, Python and Swift ports use the same layout.
- **The changed-file guard runs before every mutant write**, not only before a
  file's first mutant. `withMutant` compares the file's current bytes with the
  bytes the mutants were planned from (`begin` for the first mutant, a fresh
  read for each later one). On a mismatch it writes nothing and throws
  `SourceChangedException`; `MutationPipeline` marks that mutant and the file's
  remaining mutants `pending` and adds the changed-file note. An edit made
  between two mutants must survive (`aFileEditedBetweenTwoMutantsIsLeftAlone`).
- **`mutate` baselines.** The plain baseline runs the exact mutant command and
  must pass (`baseline_failed` otherwise). The coverage baseline is never fatal:
  without usable data it adds the no-coverage note and every mutant runs.
- **Mutant ids and order.** `id = <file>:<line>:<column>:<operator>`; `column`
  counts code points; mutants sort by file (byte-wise), line, column, operator.
  `KotlinParser` normalises `\r\n` / `\r` before parsing; `OffsetMap` maps
  mutant offsets back to the original text.
- **Classification.** Exit 0 → `survived`; timeout → `timeout`; a failed
  `compile…` task or a Kotlin `e: <file>` diagnostic → `compile_error`; any
  other failure → `killed`. `mutationScore = (killed + timeout) / (killed +
  timeout + survived + no_coverage) × 100`, `null` when nothing counts.
- **Compile-failure patterns are anchored at line start.**
  `GradleMutantRunner.isCompileFailure` matches `^> Task …compile… FAILED`,
  `^Execution failed for task '…compile…'` and `^e: <file>.kt` (or `.kts`) only
  at the start of an output line. Test output that quotes those strings is
  indented, so a failing test that prints one stays `killed`, not
  `compile_error`. `compileFailureDetection` pins both sides.

## Conventions

- Errors are `SlopguardError` with a stable `code`; surface them via `envelopeFor`.
  Exit codes: 0 ok, 1 error, 2 `--fail-over` exceeded (`analyze`) or `--fail-under`
  not met (`mutate`); 130 / 143 / 129 when a signal stops `mutate`.
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
$BIN mutate --path sample-apps/todolist/src/main/kotlin --project-dir sample-apps/todolist \
  --json --quiet | jq '{mutants:.summary.mutantCount, killed:.summary.killed, survived:.summary.survived}'
# expect {"mutants":15,"killed":15,"survived":0} — the mutation baseline
```
