package dev.slopguard.cli

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.Version
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.errors.envelopeFor
import dev.slopguard.core.formatting.CrapReportFormatter
import dev.slopguard.core.formatting.MutationReportFormatter
import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.core.mutation.MutationReport
import dev.slopguard.coverage.CoverageTool
import dev.slopguard.mutation.MutationArgs
import dev.slopguard.mutation.MutationInterruptedException
import dev.slopguard.mutation.MutationPipeline
import java.io.PrintStream
import java.util.Locale

/**
 * `mutate`: change the source one mutant at a time, run the tests against each
 * mutant, and report the mutants the tests miss. Exit codes: 0 ok, 1 error,
 * 2 `--fail-under` not met (130 / 143 / 129 when a signal interrupts the run).
 */
object MutateCommand {

    private class Options {
        var path: String = "."
        val includes = ArrayList<String>()
        val excludes = ArrayList<String>()
        var noDefaultExcludes: Boolean = false
        val operatorValues = ArrayList<String>()
        var operators: List<MutationOperator> = MutationOperator.ALL
        var projectDir: String? = null
        var noCoverage: Boolean = false
        var coverageTool: CoverageTool = CoverageTool.KOVER
        var testTask: String = "test"
        var reportTask: String? = null
        var timeout: Double? = null
        var dryRun: Boolean = false
        var json: Boolean = false
        var failUnder: Double? = null
        var verbose: Boolean = false
        var quiet: Boolean = false
        var help: Boolean = false
    }

    fun run(
        args: List<String>,
        stdout: PrintStream,
        stderr: PrintStream,
        mutate: (MutationArgs) -> MutationReport = { MutationPipeline().run(it) },
    ): Int {
        // Known before parsing, so even argument errors honour --json.
        val json = args.contains("--json")
        val options = try {
            parse(args)
        } catch (e: SlopguardError) {
            printError(e, json, stderr)
            return 1
        }
        if (options.help) {
            stdout.println(Cli.usage())
            return 0
        }

        val reporter = ProgressReporter(
            when {
                options.quiet -> Verbosity.SILENT
                options.verbose -> Verbosity.VERBOSE
                else -> Verbosity.NORMAL
            },
            stderr,
        )

        val report = try {
            mutate(
                MutationArgs(
                    sourcePath = options.path,
                    analysisOptions = AnalysisOptions(
                        includeGlobs = options.includes,
                        excludeGlobs = options.excludes,
                        useDefaultExcludes = !options.noDefaultExcludes,
                    ),
                    operators = options.operators,
                    projectDir = options.projectDir,
                    coverage = !options.noCoverage,
                    coverageTool = options.coverageTool,
                    testTask = options.testTask,
                    reportTask = options.reportTask,
                    timeoutSeconds = options.timeout,
                    dryRun = options.dryRun,
                    reporter = reporter,
                ),
            )
        } catch (e: MutationInterruptedException) {
            // The shutdown hook restored the workspace and reported it; the JVM exits with 128 + signal.
            return INTERRUPTED_EXIT
        } catch (e: Throwable) {
            printError(e, options.json, stderr)
            return 1
        }

        if (options.json) {
            stdout.println(MutationReportFormatter.jsonReport(report))
        } else {
            stdout.println(MutationReportFormatter.prettyReport(report))
        }

        val failUnder = options.failUnder ?: return 0
        val score = report.summary.mutationScore
        if (options.dryRun || score == null || score >= failUnder) return 0
        stderr.println(
            "${Version.TOOL_NAME}: mutation score ${String.format(Locale.ROOT, "%.2f", score)}% " +
                "is below --fail-under ${MutationReportFormatter.number(failUnder)}",
        )
        return 2
    }

    private const val INTERRUPTED_EXIT = 130

    private fun printError(error: Throwable, json: Boolean, stderr: PrintStream) {
        val envelope = envelopeFor(error)
        stderr.println(if (json) CrapReportFormatter.errorJson(envelope) else CrapReportFormatter.errorText(envelope))
    }

    private fun parse(args: List<String>): Options {
        val o = Options()
        var i = 0

        fun next(flag: String): String {
            if (i + 1 >= args.size) throw SlopguardError.invalidArgument(flag, "missing value")
            return args[++i]
        }

        while (i < args.size) {
            val (flag, inlineValue) = splitInline(args[i])

            fun value(): String = inlineValue ?: next(flag)

            when (flag) {
                "-p", "--path" -> o.path = Cli.expandTilde(value())
                "--include" -> o.includes.add(value())
                "--exclude" -> o.excludes.add(value())
                "--no-default-excludes" -> o.noDefaultExcludes = true
                "--operators" -> o.operatorValues.add(value())
                "--project-dir" -> o.projectDir = Cli.expandTilde(value())
                "--coverage-tool" -> o.coverageTool = CoverageTool.fromWire(value())
                "--gradle-test-task" -> o.testTask = value()
                "--gradle-report-task" -> o.reportTask = value()
                "--no-coverage" -> o.noCoverage = true
                "--timeout" -> o.timeout = parseTimeout(value())
                "--dry-run" -> o.dryRun = true
                "--json" -> o.json = true
                "--fail-under" -> o.failUnder = parseScore(value())
                "-v", "--verbose" -> o.verbose = true
                "--quiet" -> o.quiet = true
                "-h", "--help" -> {
                    o.help = true
                    return o
                }
                else -> throw SlopguardError.invalidArgument(flag, "unknown flag")
            }
            i++
        }
        o.operators = MutationOperator.parseList(o.operatorValues)
        return o
    }

    private fun splitInline(arg: String): Pair<String, String?> {
        if (arg.startsWith("--") && arg.contains('=')) {
            val idx = arg.indexOf('=')
            return arg.substring(0, idx) to arg.substring(idx + 1)
        }
        return arg to null
    }

    private fun parseTimeout(raw: String): Double {
        val seconds = raw.toDoubleOrNull()
        if (seconds == null || !seconds.isFinite() || seconds <= 0.0) {
            throw SlopguardError.invalidArgument("--timeout", "not a positive number: $raw")
        }
        return seconds
    }

    private fun parseScore(raw: String): Double {
        val score = raw.toDoubleOrNull()
        if (score == null || !score.isFinite()) throw SlopguardError.invalidArgument("--fail-under", "not a number: $raw")
        return score
    }
}
