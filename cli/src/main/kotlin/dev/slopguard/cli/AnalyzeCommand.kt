package dev.slopguard.cli

import dev.slopguard.core.Crap
import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.errors.envelopeFor
import dev.slopguard.core.formatting.CrapReportFormatter
import dev.slopguard.coverage.AnalysisPipeline
import dev.slopguard.coverage.CoverageMode
import dev.slopguard.coverage.CoverageTool
import dev.slopguard.coverage.PipelineArgs
import java.io.PrintStream

object AnalyzeCommand {

    private class Options {
        var path: String = "."
        var threshold: Double = Crap.DEFAULT_THRESHOLD
        var projectDir: String? = null
        var noCoverage: Boolean = false
        var coverageFile: String? = null
        var coverageTool: CoverageTool = CoverageTool.KOVER
        var testTask: String = "test"
        var reportTask: String? = null
        val includes = ArrayList<String>()
        val excludes = ArrayList<String>()
        var noDefaultExcludes: Boolean = false
        var json: Boolean = false
        var failOver: Double? = null
        var verbose: Boolean = false
        var quiet: Boolean = false
    }

    fun run(args: List<String>, stdout: PrintStream, stderr: PrintStream): Int {
        val options = try {
            parse(args)
        } catch (e: SlopguardError) {
            // Argument errors are reported in text form (JSON mode isn't known yet).
            stderr.println(CrapReportFormatter.errorText(envelopeFor(e)))
            return 1
        }

        val reporter = ProgressReporter(
            when {
                options.quiet -> Verbosity.SILENT
                options.verbose -> Verbosity.VERBOSE
                else -> Verbosity.NORMAL
            },
            stderr,
        )

        return try {
            val report = AnalysisPipeline.run(
                PipelineArgs(
                    sourcePath = options.path,
                    analysisOptions = AnalysisOptions(
                        includeGlobs = options.includes,
                        excludeGlobs = options.excludes,
                        useDefaultExcludes = !options.noDefaultExcludes,
                    ),
                    coverageMode = coverageMode(options),
                    threshold = options.threshold,
                    projectDir = options.projectDir,
                    coverageFile = options.coverageFile,
                    coverageTool = options.coverageTool,
                    testTask = options.testTask,
                    reportTask = options.reportTask,
                    reporter = reporter,
                ),
            )

            if (options.json) {
                stdout.println(CrapReportFormatter.jsonReport(report))
            } else {
                stdout.println(CrapReportFormatter.prettyReport(report))
            }

            val failOver = options.failOver
            if (failOver != null && report.summary.maxCrap > failOver) 2 else 0
        } catch (e: Throwable) {
            val envelope = envelopeFor(e)
            if (options.json) {
                stderr.println(CrapReportFormatter.errorJson(envelope))
            } else {
                stderr.println(CrapReportFormatter.errorText(envelope))
            }
            1
        }
    }

    private fun coverageMode(o: Options): CoverageMode = when {
        o.noCoverage -> CoverageMode.NONE
        o.coverageFile != null -> CoverageMode.PREBUILT
        else -> CoverageMode.AUTO
    }

    private fun parse(args: List<String>): Options {
        val o = Options()
        var i = 0

        fun next(flag: String): String {
            if (i + 1 >= args.size) throw SlopguardError.invalidArgument(flag, "missing value")
            return args[++i]
        }

        while (i < args.size) {
            val arg = args[i]
            val (flag, inlineValue) = splitInline(arg)

            fun value(): String = inlineValue ?: next(flag)

            when (flag) {
                "-p", "--path" -> o.path = value()
                "-t", "--threshold" -> o.threshold = parseDouble(flag, value())
                "--project-dir" -> o.projectDir = value()
                "--no-coverage" -> o.noCoverage = true
                "--coverage-file" -> o.coverageFile = value()
                "--coverage-tool" -> o.coverageTool = CoverageTool.fromWire(value())
                "--gradle-test-task" -> o.testTask = value()
                "--gradle-report-task" -> o.reportTask = value()
                "--include" -> o.includes.add(value())
                "--exclude" -> o.excludes.add(value())
                "--no-default-excludes" -> o.noDefaultExcludes = true
                "--json" -> o.json = true
                "--fail-over" -> o.failOver = parseDouble(flag, value())
                "-v", "--verbose" -> o.verbose = true
                "--quiet" -> o.quiet = true
                else -> throw SlopguardError.invalidArgument(flag, "unknown flag")
            }
            i++
        }
        return o
    }

    private fun splitInline(arg: String): Pair<String, String?> {
        if (arg.startsWith("--") && arg.contains('=')) {
            val idx = arg.indexOf('=')
            return arg.substring(0, idx) to arg.substring(idx + 1)
        }
        return arg to null
    }

    private fun parseDouble(flag: String, raw: String): Double =
        raw.toDoubleOrNull() ?: throw SlopguardError.invalidArgument(flag, "not a number: $raw")
}
