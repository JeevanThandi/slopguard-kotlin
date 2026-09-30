# slopguard-kotlin

[![CI](https://github.com/JeevanThandi/slopguard-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/JeevanThandi/slopguard-kotlin/actions/workflows/ci.yml)

> **CRAP (Change Risk Anti-Patterns) guardrail for Kotlin & Android.**

> ⚠️ **Alpha (v0.2.x).** The analyzer is stable and self-tested, but the CLI surface and JSON schema may still change before v1.0.

`slopguard-kotlin` measures **complex, undertested code** in Kotlin and Android projects. It computes a weighted CRAP score combining cyclomatic and cognitive complexity with line coverage, and prints a structured report you can pipe into `jq` or fail CI on. Its `mutate` command is a mutation tester: it changes the code one small step at a time and reports the changes your tests do not catch. It is the Kotlin sibling of [slopguard-swift](https://github.com/JeevanThandi/SlopGuard-Swift), [slopguard-typescript](https://github.com/JeevanThandi/slopguard-typescript), [slopguard-go](https://github.com/JeevanThandi/slopguard-go) and [slopguard-python](https://github.com/JeevanThandi/slopguard-python): same formula, same schema, same UX.

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
| `mutate`  | Change the source one mutant at a time, run the Gradle tests against each mutant, and report the mutants no test catches (text or JSON). See [Mutation testing](#mutation-testing). |
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

## Mutation testing

### Why

Line coverage shows that a test executed a line. It does not show that a test checked the result. A mutation tester makes one small change to the code (a *mutant*), runs the tests, and puts the code back. When the tests still pass, no test checks that behaviour: the mutant *survived*. Robert C. Martin makes this argument in the clip on [slopguard.dev](https://slopguard.dev). Cover the code first. Then use a mutation tester to prove that the tests check it, and write a test for every mutant that survives.

`mutate` reports every surviving mutant with its file, line, column, original text, replacement and enclosing method. You or a coding agent can then write the test that kills it. It complements `analyze`: `analyze` finds complex code without tests, and `mutate` finds tested code whose tests assert too little.

### Quickstart

```bash
# Mutate a module's sources and run its tests against every mutant
slopguard-kotlin mutate --path src/main/kotlin

# List the mutants without running any test or changing any file
slopguard-kotlin mutate --path src/main/kotlin --dry-run

# One file, comparison operators only
slopguard-kotlin mutate --path src/main/kotlin/com/example/Store.kt --operators boundary,negate_conditional

# The surviving mutants, as an agent work queue
slopguard-kotlin mutate --path src/main/kotlin --json --quiet \
  | jq '[.mutants[] | select(.status == "survived") | {id, original, replacement, method}]'

# Fail CI when the mutation score is below 90
slopguard-kotlin mutate --path src/main/kotlin --fail-under 90

# Multi-module build: run only the module's tests and its own coverage report
slopguard-kotlin mutate --path core/src/main/kotlin --project-dir . \
  --gradle-test-task :core:test --gradle-report-task :core:koverXmlReport
```

Each mutant costs one Gradle test run: a compile plus the tests. Start with one file or one package. `--dry-run` shows how many mutants a path yields.

### Flags

| Flag | Meaning |
|------|---------|
| `-p, --path <path>` | Directory or single `.kt` file to mutate. Default `.`. |
| `--include <glob>` | Only mutate files that match the glob (relative to `--path`). Repeatable. |
| `--exclude <glob>` | Extra globs to skip, added to the default excludes. Repeatable. |
| `--no-default-excludes` | Drop the built-in excludes (the same list as `analyze`, so test files are never mutated by default). |
| `--operators <ids>` | Comma-separated operator ids (see below). Repeatable. Default: all. |
| `--project-dir <dir>` | The Gradle project the tests run in. Default: the nearest Gradle root above `--path`. |
| `--gradle-test-task <t>` | Test task for the baselines and every mutant run. Default `test`. |
| `--coverage-tool <t>` | `kover` (default) or `jacoco`, for the coverage baseline. |
| `--gradle-report-task <t>` | Report task for the coverage baseline. Default: `koverXmlReport` / `jacocoTestReport`. |
| `--no-coverage` | Skip the coverage baseline and run every mutant. |
| `--timeout <seconds>` | Per-mutant timeout, a positive number of seconds. Default: 3 × the plain baseline's run time, rounded up, + 60 s. |
| `--dry-run` | List the mutants. Run no test and change no file. |
| `--json` | Print the JSON report on stdout (default: text). |
| `--fail-under <score>` | Exit 2 when the mutation score is below this number (0–100). A run without a score never fails. |
| `-v, --verbose` | Stream Gradle output to stderr. |
| `--quiet` | Print no progress on stderr. Wins over `--verbose`. |

Exit codes: `0` success, `1` error (the error envelope goes to stderr, as JSON with `--json`), `2` score below `--fail-under`, `130` / `143` / `129` when SIGINT / SIGTERM / SIGHUP stops the run.

### Operators

| id | Change | Kotlin details |
|----|--------|----------------|
| `arithmetic` | `+`↔`-`, `*`↔`/`, `%`→`*`, `+=`↔`-=`, `*=`↔`/=`, `%=`→`*=` | Mutates binary operators only. A `+` or `+=` is skipped when an operand is a string literal or template, or (through parentheses) another `+` with a string operand. Every `+` in `"a" + b + c` is skipped. A `+` between two String variables is still mutated and is reported as `compile_error`. |
| `boolean_literal` | `true`↔`false` | |
| `boundary` | `<`→`<=`, `<=`→`<`, `>`→`>=`, `>=`→`>` | |
| `increment` | `++`↔`--` | Mutates the prefix and the postfix forms. |
| `invert_negative` | `-x` → `x` | Removes the unary minus. |
| `logical` | `&&`↔`\|\|` | |
| `negate_conditional` | `==`↔`!=`, `===`↔`!==`, `<`→`>=`, `<=`→`>`, `>`→`<=`, `>=`→`<` | `in` / `is` checks are not mutated. |
| `remove_call` | Removes a call statement | Removes only a statement that is a plain call (`f()`, `a.b()`, `a?.b()`) directly inside a braced block. It keeps the last statement of a lambda, and of an `if`, `when` or `try` branch whose value is used, because removing it would change the block's type. `println` and `print` calls (bare, or on `System.out`, `System.err` or `kotlin.io`) and calls on `logger`, `log` or `Log` are never removed. |
| `remove_not` | `!x` → `x` | The `!!` force unwrap is never touched. |

Mutants come only from real syntax nodes of the Kotlin PSI tree: comments, string text and annotation arguments are never mutated. Each mutant replaces one contiguous span of the file. `line` and `column` are 1-based, and `column` counts Unicode code points. A mutant's id is `<file>:<line>:<column>:<operator>`, where `<file>` is the path relative to the source root, the same path `analyze` reports.

### Statuses

| Status | Meaning | Score |
|--------|---------|-------|
| `killed` | A test failed with the mutant in place. | detected |
| `timeout` | The test run exceeded the timeout. | detected |
| `survived` | Every test passed with the mutant in place. | missed |
| `no_coverage` | The coverage baseline shows that no test executes the mutated line, so the mutant was not run. | missed |
| `compile_error` | The mutant does not compile (a failed `compile…` task or a Kotlin `e:` diagnostic). | excluded |
| `ignored` | An ignore marker switched the mutant off. | excluded |
| `pending` | Not run: a dry run, or the file changed while `mutate` was running. | excluded |

`mutationScore = (killed + timeout) / (killed + timeout + survived + no_coverage) × 100`. It is `null` (`n/a` in text) when no mutant counts.

### Ignore marker

Some mutants are *equivalent*: the change has no observable effect, so no test can kill them. Switch them off with a comment:

```kotlin
if (value > max) max = value // slopguard-ignore-mutant(boundary): equal values give the same max

// slopguard-ignore-mutant
val limit = retries * 2
```

* `slopguard-ignore-mutant` ignores every mutant on its line.
* `slopguard-ignore-mutant(boundary,negate_conditional)` ignores only the listed operators. Unknown ids are dropped.
* A marker on a line that holds only a comment applies to the next line. Such a line starts with `//`, `/*`, or `*` followed by a space, `/` or the end of the line. A spread argument such as `*args` is code, so a marker on its line applies to that line.

Ignored mutants are never run and do not count in the score. Add the reason after the marker, so a reviewer can check that the mutant really is equivalent.

### JSON

`--json` prints a report with `reportType: "mutation"` and its own `schemaVersion: "1"`, shared with every sibling port. Keys are sorted at every level. This excerpt comes from the sample app:

```json
{
  "coverageAvailable": true,
  "generatedAt": "2026-09-30T13:36:10.533Z",
  "mutants": [
    {
      "column": 19,
      "file": "com/example/todo/TodoStore.kt",
      "id": "com/example/todo/TodoStore.kt:19:19:boundary",
      "line": 19,
      "method": "TodoStore.toggle",
      "operator": "boundary",
      "original": "<",
      "replacement": "<=",
      "status": "killed"
    }
  ],
  "notes": [],
  "operators": ["arithmetic", "boolean_literal", "boundary", "increment", "invert_negative", "logical", "negate_conditional", "remove_call", "remove_not"],
  "projectRoot": "/work/slopguard-kotlin/sample-apps/todolist",
  "reportType": "mutation",
  "runner": "gradle",
  "schemaVersion": "1",
  "sourceRoot": "/work/slopguard-kotlin/sample-apps/todolist/src/main/kotlin",
  "summary": {
    "compileErrors": 0,
    "fileCount": 2,
    "ignored": 0,
    "killed": 15,
    "mutantCount": 15,
    "mutationScore": 100,
    "noCoverage": 0,
    "pending": 0,
    "survived": 0,
    "timedOut": 0
  },
  "timeoutSeconds": 65,
  "tool": "slopguard-kotlin",
  "toolVersion": "0.2.0"
}
```

`method` is the qualified name of the innermost method around the mutant, the same name that `analyze` uses. It is `null` for code outside any method, such as a property initialiser or a primary-constructor default argument. `runner`, `projectRoot` and `timeoutSeconds` are `null` when no test ran (a dry run, or no mutant to run).

### How a run works

1. `mutate` walks `--path` exactly as `analyze` does and parses every file with the Kotlin PSI parser. It generates the mutants, applies the ignore markers and `--operators`, and sorts the mutants by file, line, column and operator. A dry run stops here. So does a run where no mutant is left to run.
2. It takes the workspace guard for the project (see [Workspace safety](#workspace-safety)).
3. It runs the plain baseline: the exact mutant command, once, with no mutant and no timeout. The command is `./gradlew <test-task> --fail-fast --console=plain --init-script <script>`. The baseline must pass, or the run stops with `baseline_failed`. A broken command would otherwise fail every mutant and fake a 100% score. The baseline's run time sets the default timeout.
4. Unless `--no-coverage` is given, it runs the coverage baseline. This is the `analyze` coverage command, `./gradlew <test-task> <report-task> --continue`, and it produces a Kover or JaCoCo XML report. A mutant on a line with exactly 0% coverage gets `no_coverage` and is not run. This step never stops the run. Without usable coverage data, a note says so and every mutant runs.
5. For each mutant, it writes the mutant into the file, runs the mutant command with the timeout, writes the original back and classifies the result.

The init script switches off every Kover and JaCoCo report and verification task in the plain baseline and the mutant runs. It also switches off the JaCoCo test agent. A coverage threshold in your build cannot fail a mutant run and fake a kill.

### Workspace safety

`mutate` changes your source files in place, one at a time. It is built to never leave a mutant behind:

* It reads each file's bytes and timestamps before the file's first mutant. It writes a mutant by truncating and rewriting the file, so the inode, the mode and hard links stay. After the test run it writes the original bytes back and restores the modification and access times.
* It restores the file on every exit path: normal completion, an error, and SIGINT, SIGTERM or SIGHUP. On a signal, a JVM shutdown hook kills the test run, restores the file and prints `slopguard: interrupted — restored <file>` (unless `--quiet` is set).
* A guard directory outside the project, `$TMPDIR/slopguard-mutate/<first 16 hex characters of sha256(project path)>/`, holds three files. `lock` allows one run per project, and a second run fails with `mutation_in_progress`. `journal.json` names the file under mutation and the mutant's sha256. `original` is a backup of the original bytes.
* If a run dies without cleaning up (SIGKILL, a power cut), the next run finds the stale lock. It restores the file when the file still holds exactly the recorded mutant. When the file has changed since, it keeps the backup and a note says where.
* A timeout kills the Gradle client and every process it started. The Gradle daemon then cancels the build and stops the test JVM. `mutate` also stops any test JVM of the project that is still running.
* The last Gradle run compiled a mutant, so the next build of the project recompiles the restored file. Gradle detects the change by itself.

Do not edit the files under `--path` while `mutate` runs. Before each mutant write, `mutate` compares the file with the bytes it planned the mutants from. If the file has changed, `mutate` writes nothing more to it and keeps your edit. The file's remaining mutants are not run (`pending`), and a note names the file.

## What counts as a method

Top-level **functions**, **member functions** (in classes, objects, interfaces, enums), **secondary constructors**, **`init` blocks**, and **custom property accessors** (`get`/`set` with a body). Lambdas and local functions don't get their own entry — their branches count toward the enclosing function, with a cognitive nesting bump, per the Sonar spec. Abstract / `expect` functions with no body are skipped.

Types are gathered by **lexical nesting**: `Outer.Inner.bar` rolls up into the `Inner` type, not `Outer`.

Default excludes keep noise out: `build/`, `.gradle/`, generated code, `*Test.kt` / `*Tests.kt`, `src/test` and `src/androidTest` source sets, `testFixtures/`, and the sample apps. Analyze excluded code with `--no-default-excludes`.

## Posture

* **One third-party runtime dependency** — `kotlin-compiler-embeddable`, used purely as a PSI parser (the Kotlin analogue of SwiftSyntax / the TypeScript compiler API). JSON and CLI parsing are hand-rolled on the standard library.
* **The only subprocess slopguard-kotlin spawns is the project's own Gradle test run.**
* `analyze` starts that test run once. `mutate` starts it twice for the baselines and once per mutant.
* `analyze` never writes to your sources. `mutate` writes one mutant at a time into a source file and always writes the original back. See [Workspace safety](#workspace-safety).
* **No network, no telemetry.** See [`SECURITY.md`](SECURITY.md) for the full threat model.
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

Mutation testing lives in the `mutation` module: `MutationPipeline`, `WorkspaceGuard` and `GradleMutantRunner`. Mutant generation, the report models and the formatter are pure and live in `core`. This example runs two operators and prints the JSON report:

```kotlin
import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.formatting.MutationReportFormatter
import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.mutation.MutationArgs
import dev.slopguard.mutation.MutationPipeline

val report = MutationPipeline().run(
    MutationArgs(
        sourcePath = "src/main/kotlin",
        operators = listOf(MutationOperator.BOUNDARY, MutationOperator.NEGATE_CONDITIONAL),
        reporter = ProgressReporter(Verbosity.NORMAL),
    ),
)
println(MutationReportFormatter.jsonReport(report))
```

`MutationPlanner.planPath(...)` lists the mutants of a path without running anything. This is what `--dry-run` prints.
