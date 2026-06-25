# slopguard-kotlin

[![CI](https://github.com/JeevanThandi/slopguard-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/JeevanThandi/slopguard-kotlin/actions/workflows/ci.yml)

> **CRAP (Change Risk Anti-Patterns) guardrail for Kotlin & Android.**

> ⚠️ **Alpha (v0.1.x).** The analyzer is stable and self-tested, but the CLI surface and JSON schema may still change before v1.0.

`slopguard-kotlin` measures **complex, undertested code** in Kotlin and Android projects. It computes a weighted CRAP score combining cyclomatic and cognitive complexity with line coverage, and prints a structured report you can pipe into `jq` or fail CI on. It is the Kotlin sibling of [slopguard-swift](https://github.com/JeevanThandi/SlopGuard-Swift), [slopguard-typescript](https://github.com/JeevanThandi/slopguard-typescript) and [slopguard-go](https://github.com/JeevanThandi/slopguard-go) — same formula, same schema, same UX.

```
wCRAP(m) = (cyc × cog) × (1 − cov/100)³ + sqrt(cyc × cog)
```

* `cyc` — cyclomatic complexity (McCabe), parsed via the Kotlin compiler's PSI front-end. Counts `if`, `for`, `while`, `do`, each non-`else` `when` branch, `catch`, the elvis `?:`, and each `&&` / `||`.
* `cog` — cognitive complexity per the [SonarSource 2023 spec](https://www.sonarsource.com/resources/cognitive-complexity/) — penalises nesting, charges a whole `when` once, ignores early-exit shapes (plain `return`/`break`/`continue`).
* `wt`  — `sqrt(cyc × cog)`, the geometric blend fed into the formula. A flat 50-branch `when` (cyc≈50, cog=1) scores like a small method; a deeply nested 3-branch tangle (cyc=3, cog=12) scores like medium-complex code.
* `cov` — line coverage gathered by slopguard-kotlin itself, by driving the project's own Gradle test run with **JaCoCo or Kover**. Never user-supplied.
* Default crappy threshold: **30** (on wCRAP).

## Install

Build from source (requires JDK 17+):

```bash
git clone https://github.com/JeevanThandi/slopguard-kotlin.git
cd slopguard-kotlin
./gradlew :app:installDist
# the launcher lands at app/build/install/slopguard-kotlin/bin/slopguard-kotlin
cp -r app/build/install/slopguard-kotlin /usr/local/      # optional
export PATH="/usr/local/slopguard-kotlin/bin:$PATH"
```

## Quickstart

```bash
# Zero-config: analyze the current module (drives gradle test + Kover for coverage)
slopguard-kotlin analyze --path src/main/kotlin

# Scan a directory and print the top crappy methods
slopguard-kotlin analyze --path src/main/kotlin --threshold 30

# Android with Kover (the default): scope to a variant report task
slopguard-kotlin analyze --path app/src/main/kotlin \
  --gradle-test-task testDebugUnitTest --gradle-report-task koverXmlReportDebug

# Prefer JaCoCo? Opt in with --coverage-tool jacoco
slopguard-kotlin analyze --path src/main/kotlin --coverage-tool jacoco

# Android with JaCoCo: run the unit-test variant and your JaCoCo report task
slopguard-kotlin analyze --path app/src/main/kotlin \
  --coverage-tool jacoco --gradle-test-task testDebugUnitTest --gradle-report-task jacocoTestReport

# Multi-module: point at the module whose tests should run
slopguard-kotlin analyze --path feature/login/src/main/kotlin --project-dir feature/login

# Full JSON for CI / downstream tooling
slopguard-kotlin analyze --path src/main/kotlin --json | jq '.methods | sort_by(-.crap)[:10]'

# Fail CI when any method's CRAP exceeds 50
slopguard-kotlin analyze --path src/main/kotlin --fail-over 50

# Complexity only (skip the test run — every method shows 0% coverage)
slopguard-kotlin analyze --path src/main/kotlin --no-coverage

# Join a JaCoCo report CI already produced
slopguard-kotlin analyze --path src/main/kotlin \
  --coverage-file build/reports/jacoco/test/jacocoTestReport.xml
```

Progress markers (`slopguard: running gradle test with coverage…`) go to **stderr**, so piped stdout stays clean. `--verbose` streams the underlying Gradle output through; `--quiet` silences progress entirely.

## How coverage works

Coverage is an *artifact of the analysis*, not an input — mirroring how slopguard-swift drives `xcodebuild test`, slopguard-typescript drives vitest/jest, and slopguard-go drives `go test`:

1. **Project discovery.** Walk up from `--path` to the nearest Gradle root (`settings.gradle[.kts]`, `gradlew`, or `build.gradle[.kts]`). Override with `--project-dir`.
2. **Test run.** Run the project's own `./gradlew <test-task> <report-task> --continue`. `--continue` means failing tests don't abort — partial coverage is still useful (a note is attached). Pick your coverage tool with `--coverage-tool`:
   * **Kover** (default) — report task `koverXmlReport`, which emits `build/reports/kover/report.xml` **by default** (no `xml.required` opt-in needed). Just apply `org.jetbrains.kotlinx.kover` — the Kotlin-first, lowest-config path.
   * **JaCoCo** — `--coverage-tool jacoco` uses report task `jacocoTestReport`. Apply the `jacoco` plugin with **XML output enabled**: `tasks.jacocoTestReport { reports { xml.required.set(true) } }`.

   Override either default with `--gradle-test-task` / `--gradle-report-task` (e.g. Android variant tasks).
3. **Join.** Parse the XML report into a per-line index, resolve its package-qualified file names against the analyzed paths (longest-suffix + basename fallback for CI-vs-local path mismatches), and join per-method line coverage onto the parsed declarations. The report finder scans the `reports/jacoco`, `reports/kover`, and `reports/coverage` (AGP-native) directories.

Both JaCoCo and Kover emit the **same JaCoCo XML interchange format** — Kover does so deliberately for ecosystem compatibility — so one parser handles either, and a report your CI already generated is supported via `--coverage-file <report.xml>` regardless of which tool produced it.

## Subcommands

| Command   | Purpose |
|-----------|---------|
| `analyze` | Walk a directory of Kotlin sources, drive `gradle test` + JaCoCo for coverage, emit a wCRAP report (text or JSON). |
| `version` | Print version metadata as JSON. |

`analyze` is the default subcommand and `--path` defaults to the current directory — a bare `slopguard-kotlin` in your module root just works.

## JSON output

`--json` emits a stable, versioned (`schemaVersion: "2"`, shared with every sibling port) report with:

* `summary` — file/type/method counts, average + max wCRAP, weighted coverage.
* `methods[]` — every analyzed function/method with `complexity`, `cognitiveComplexity`, `weightedComplexity`, `coverage`, `crap`, `isCrappy`, and a stable `id`.
* `types[]` — per-class / interface / object / enum aggregation: `aggregatedCrap` (formula applied to type totals) and `maxCrap` (worst single-method offender).

Keys are emitted alphabetically and pretty-printed, so the output is diff-stable in CI.

Slice with `jq`:

```bash
# Top 10 worst methods
slopguard-kotlin analyze --path src/main/kotlin --json | jq '.methods | sort_by(-.crap)[:10]'

# Only crappy types
slopguard-kotlin analyze --path src/main/kotlin --json | jq '.types[] | select(.isCrappy)'

# Coverage gaps: high complexity, low coverage
slopguard-kotlin analyze --path src/main/kotlin --json \
  | jq '.methods[] | select(.complexity >= 5 and .coverage <= 50)'
```

### Build an agent work queue

```bash
slopguard-kotlin analyze --path src/main/kotlin --json --quiet \
  | jq '[.methods[] | select(.isCrappy)] | sort_by(-.crap)
         | map({id, crap, coverage, file, line})'
```

Drop this into `CLAUDE.md` / `AGENTS.md` so your coding agent gates on slop and refactors the worst offenders first:

> Use `slopguard-kotlin` to analyze this module and find the method with the highest wCRAP score. Show me its file and line, then add tests or refactor until its score is under 30.

## Why it exists

Test coverage alone says "this code ran in a test"; complexity alone says "this code has many paths." Neither tells you whether the *risky* code is tested. CRAP combines them: a method with 20 branches and 0% coverage scores 420; the same method at 100% coverage scores 20 (just its complexity). The score lights up the code most likely to break under a refactor *and* be the hardest to verify the fix for — exactly the code your coding agents trip over.

## What counts as a method

Top-level **functions**, **member functions** (in classes, objects, interfaces, enums), **secondary constructors**, **`init` blocks**, and **custom property accessors** (`get`/`set` with a body). Lambdas and local functions don't get their own entry — their branches count toward the enclosing function, with a cognitive nesting bump, per the Sonar spec. Abstract / `expect` functions with no body are skipped.

Types are gathered by **lexical nesting**: `Outer.Inner.bar` rolls up into the `Inner` type, not `Outer`.

Default excludes keep noise out: `build/`, `.gradle/`, generated code, `*Test.kt` / `*Tests.kt`, `src/test` and `src/androidTest` source sets, `testFixtures/`, and the sample apps. Analyze excluded code with `--no-default-excludes`.

## Posture

* **One third-party runtime dependency** — `kotlin-compiler-embeddable`, used purely as a PSI parser (the Kotlin analogue of SwiftSyntax / the TypeScript compiler API). JSON and CLI parsing are hand-rolled on the standard library.
* **The only subprocess slopguard-kotlin spawns is the project's own Gradle test run.**
* **No network, no telemetry, no source mutation.** See [`SECURITY.md`](SECURITY.md) for the full threat model.
* **MIT licensed** ([`LICENSE`](LICENSE)).

## Library use

Everything the CLI does is exported. The pipeline lives in the `coverage` module, the pure analyzer in `core`:

```kotlin
import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.coverage.AnalysisPipeline
import dev.slopguard.coverage.CoverageMode
import dev.slopguard.coverage.PipelineArgs

val report = AnalysisPipeline.run(
    PipelineArgs(
        sourcePath = "src/main/kotlin",
        analysisOptions = AnalysisOptions(),
        coverageMode = CoverageMode.AUTO,
        threshold = 30.0,
        reporter = ProgressReporter(Verbosity.NORMAL),
    ),
)
```

For complexity-only analysis with no I/O, parse with `KotlinParser` and walk with `ComplexityVisitor` directly.
