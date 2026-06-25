package dev.slopguard.cli

import java.io.PrintStream

/**
 * Entry point shared by the executable and tests. Returns the process exit code
 * (0 ok, 1 error, 2 --fail-over exceeded) instead of calling exit, so it stays
 * testable — mirroring `cli.Run` in slopguard-go.
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
            // No subcommand (or a leading flag) defaults to `analyze`.
            else -> AnalyzeCommand.run(args.toList(), stdout, stderr)
        }
    }

    fun usage(): String = """
        slopguard-kotlin — CRAP (Change Risk Anti-Patterns) guardrail for Kotlin / Android.

        USAGE:
          slopguard-kotlin [analyze] [options]
          slopguard-kotlin version

        COMMANDS:
          analyze   Walk Kotlin sources, drive `gradle test` + JaCoCo for coverage,
                    emit a wCRAP report (text or JSON). The default command.
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
    """.trimIndent()
}
