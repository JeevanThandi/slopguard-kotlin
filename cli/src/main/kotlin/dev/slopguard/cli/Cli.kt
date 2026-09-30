package dev.slopguard.cli

import java.io.PrintStream

/**
 * Entry point shared by the executable and tests. Returns the process exit code
 * (0 ok, 1 error, 2 --fail-over exceeded / --fail-under not met) instead of
 * calling exit, so it stays testable — mirroring `cli.Run` in slopguard-go.
 */
object Cli {
    fun run(args: Array<String>, stdout: PrintStream, stderr: PrintStream): Int {
        val first = args.firstOrNull()
        return when {
            first == "version" -> VersionCommand.run(stdout)
            first == "--help" || first == "-h" || first == "help" -> {
                stdout.println(usage())
                0
            }
            first == "analyze" -> AnalyzeCommand.run(args.drop(1), stdout, stderr)
            first == "mutate" -> MutateCommand.run(args.drop(1), stdout, stderr)
            // No subcommand (or a leading flag) defaults to `analyze`.
            else -> AnalyzeCommand.run(args.toList(), stdout, stderr)
        }
    }

    /** `~` and `~/…` in a path flag resolve against the user's home directory, as in the sibling ports. */
    internal fun expandTilde(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") -> System.getProperty("user.home") + path.substring(1)
        else -> path
    }

    fun usage(): String = """
        slopguard-kotlin — CRAP (Change Risk Anti-Patterns) guardrail for Kotlin / Android.

        USAGE:
          slopguard-kotlin [analyze] [options]
          slopguard-kotlin mutate [options]
          slopguard-kotlin version

        COMMANDS:
          analyze   Walk Kotlin sources, drive `gradle test` + JaCoCo for coverage,
                    emit a wCRAP report (text or JSON). The default command.
          mutate    Change the source one mutant at a time, run the Gradle tests
                    against each mutant, and report the mutants no test catches.
          version   Print version metadata as JSON.

        ANALYZE OPTIONS:
          -p, --path <path>          Directory or single .kt file to analyze (default: .)
          -t, --threshold <number>   wCRAP cutoff for "crappy" (default: 30)
              --project-dir <dir>    Gradle project root (default: discovered above --path)
              --no-coverage          Skip the test run; report complexity only (0% coverage)
              --coverage-file <xml>  Join an existing JaCoCo/Kover XML report instead of running tests
              --coverage-tool <t>    Coverage tool to drive: kover | jacoco (default: kover)
              --gradle-test-task <t> Gradle test task to run (default: test)
              --gradle-report-task <t> Gradle report task (default: per --coverage-tool;
                                       koverXmlReport / jacocoTestReport)
              --include <glob>       Glob of files to include (repeatable)
              --exclude <glob>       Extra glob of files/dirs to exclude (repeatable)
              --no-default-excludes  Skip built-in excludes (build/, *Test.kt, generated, …)
              --json                 Emit JSON to stdout (default: pretty text)
              --fail-over <number>   Exit 2 if any method's wCRAP exceeds this value
          -v, --verbose              Stream gradle output to stderr
              --quiet                Suppress all progress chatter on stderr

        MUTATE OPTIONS:
          -p, --path <path>          Directory or single .kt file to mutate (default: .)
              --include <glob>       Only mutate files matching the glob (repeatable)
              --exclude <glob>       Extra glob of files/dirs to skip (repeatable)
              --no-default-excludes  Skip built-in excludes (build/, *Test.kt, generated, …)
              --operators <ids>      Comma-separated operator ids (repeatable; default: all):
                                       arithmetic, boolean_literal, boundary, increment,
                                       invert_negative, logical, negate_conditional,
                                       remove_call, remove_not
              --project-dir <dir>    Gradle project the tests run in (default: discovered above --path)
              --coverage-tool <t>    Coverage tool for the baseline: kover | jacoco (default: kover)
              --gradle-test-task <t> Gradle test task to run (default: test)
              --gradle-report-task <t> Gradle report task for the coverage baseline
              --no-coverage          Skip the coverage baseline; test every mutant
              --timeout <seconds>    Per-mutant timeout (default: 3 × baseline time + 60)
              --dry-run              List the mutants; run no tests; change no files
              --json                 Emit JSON to stdout (default: pretty text)
              --fail-under <score>   Exit 2 if the mutation score (0-100) is below this value
          -v, --verbose              Stream gradle output to stderr
              --quiet                Suppress all progress chatter on stderr

          Ignore an equivalent mutant with a comment on its line (or on the line above):
            // slopguard-ignore-mutant(boundary): equal values give the same result
    """.trimIndent()
}
