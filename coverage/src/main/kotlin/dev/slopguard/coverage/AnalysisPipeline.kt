package dev.slopguard.coverage

import dev.slopguard.core.CrapReport
import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.aggregation.AggregationInput
import dev.slopguard.core.aggregation.CoverageProvider
import dev.slopguard.core.aggregation.CrapAggregator
import dev.slopguard.core.aggregation.NoCoverage
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.analysis.DirectoryAnalyzer
import dev.slopguard.core.errors.SlopguardError
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How coverage is sourced. */
enum class CoverageMode { AUTO, PREBUILT, NONE }

class PipelineArgs(
    val sourcePath: String,
    val analysisOptions: AnalysisOptions,
    val coverageMode: CoverageMode,
    val threshold: Double,
    val projectDir: String? = null,
    val coverageFile: String? = null,
    val coverageTool: CoverageTool = CoverageTool.KOVER,
    val testTask: String = "test",
    /** Explicit Gradle report task; when null, defaults to [coverageTool]'s task. */
    val reportTask: String? = null,
    val reporter: ProgressReporter,
)

/**
 * Orchestrates the full flow: parse sources, gather coverage (drive Gradle, load
 * a prebuilt JaCoCo report, or skip), and aggregate into a [CrapReport]. Mirrors
 * the AnalysisPipeline of every sibling port.
 */
object AnalysisPipeline {

    /** The `generatedAt` format shared by every report: UTC, milliseconds, `Z`. */
    val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun run(args: PipelineArgs): CrapReport {
        val (fileReports, sourceRoot) = DirectoryAnalyzer.analyze(args.sourcePath, args.analysisOptions)
        val generatedAt = TIMESTAMP.format(Instant.now())

        val resolved = resolveCoverage(args, sourceRoot)

        return CrapAggregator.aggregate(
            AggregationInput(
                fileReports = fileReports,
                sourceRoot = sourceRoot.absolutePath,
                provider = resolved.provider,
                threshold = args.threshold,
                coverageAvailable = resolved.available,
                coverageDataPath = resolved.dataPath,
                notes = resolved.notes,
                generatedAt = generatedAt,
            ),
        )
    }

    private class ResolvedCoverage(
        val provider: CoverageProvider,
        val available: Boolean,
        val dataPath: String?,
        val notes: List<String>,
    )

    private fun resolveCoverage(args: PipelineArgs, sourceRoot: File): ResolvedCoverage =
        when (args.coverageMode) {
            CoverageMode.NONE -> ResolvedCoverage(NoCoverage, available = false, dataPath = null, notes = emptyList())

            CoverageMode.PREBUILT -> {
                val path = args.coverageFile
                    ?: throw SlopguardError.invalidArgument("--coverage-file", "no path supplied")
                val file = File(path)
                if (!file.exists()) throw SlopguardError.fileNotFound(file.absolutePath)
                val index = CoverageIndex(JacocoReport.parse(file))
                val notes = if (!index.hasData) {
                    listOf("The coverage report contained no per-file data — every method reads 0%.")
                } else {
                    emptyList()
                }
                ResolvedCoverage(index, available = true, dataPath = file.absolutePath, notes = notes)
            }

            CoverageMode.AUTO -> resolveAuto(args, sourceRoot)
        }

    private fun resolveAuto(args: PipelineArgs, sourceRoot: File): ResolvedCoverage {
        val projectRoot = args.projectDir?.let { File(it).absoluteFile }
            ?: ProjectRootDiscovery.discover(sourceRoot)
            ?: throw SlopguardError.projectRootNotFound(sourceRoot.absolutePath)

        val reportTask = args.reportTask ?: args.coverageTool.defaultReportTask
        val outcome = GradleRunner(projectRoot, args.testTask, reportTask, args.coverageTool, args.reporter).run()
        val notes = ArrayList<String>()

        val xml = outcome.reportXml
            ?: throw SlopguardError.coverageDataMissing(
                "No coverage XML report was produced under $projectRoot by '$reportTask'. For JaCoCo, apply the " +
                    "jacoco plugin with XML enabled (jacocoTestReport { reports { xml.required = true } }); for " +
                    "Kover, apply org.jetbrains.kotlinx.kover and use --coverage-tool kover (koverXmlReport emits " +
                    "XML by default). On Android, pass the variant task via --gradle-report-task. Otherwise pass " +
                    "--coverage-file <report.xml> or --no-coverage.",
            )

        if (outcome.exitCode != 0) {
            notes.add("Some tests failed during the coverage run (gradle exit ${outcome.exitCode}) — coverage reflects the failing run.")
        }

        val index = CoverageIndex(JacocoReport.parse(xml))
        if (!index.hasData) {
            notes.add("The test run produced no per-file coverage data — check the jacoco report's include patterns.")
        }
        return ResolvedCoverage(index, available = true, dataPath = xml.absolutePath, notes = notes)
    }
}
