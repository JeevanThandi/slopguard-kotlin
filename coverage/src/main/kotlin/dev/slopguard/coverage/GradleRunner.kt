package dev.slopguard.coverage

import dev.slopguard.core.ProgressReporter
import java.io.File

data class GradleOutcome(val exitCode: Int, val outputTail: String, val reportXml: File?)

/**
 * Drives the project's own Gradle test run with JaCoCo or Kover, the Kotlin
 * analogue of slopguard-swift's `xcodebuild test` and slopguard-typescript's
 * vitest/jest driver. Coverage is an artifact of the analysis, never a user input.
 */
class GradleRunner(
    private val projectRoot: File,
    private val testTask: String,
    private val reportTask: String,
    private val tool: CoverageTool,
    private val reporter: ProgressReporter,
) {
    fun run(): GradleOutcome {
        reporter.phase("running gradle $testTask $reportTask with coverage…")
        val outcome = ProcessRunner.run(command(), projectRoot, reporter)
        return GradleOutcome(outcome.exitCode, outcome.outputTail, findReportXml())
    }

    /**
     * The coverage run: `./gradlew <test-task> <report-task> --console=plain
     * --continue`. `--continue` keeps going to the report task even if some
     * tests fail — partial coverage is still useful, exactly like the sibling ports.
     */
    fun command(): List<String> = listOf(gradleExecutable(projectRoot), testTask, reportTask, "--console=plain", "--continue")

    /** Every XML file under the project root: the candidates for [pickReportXml]. */
    fun reportXmlCandidates(): List<File> =
        projectRoot.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .toList()

    /**
     * Locate the coverage XML report. Prefers the selected tool's report directory
     * and a canonical filename, then falls back across the other known coverage
     * directories (JaCoCo, Kover, and the Android Gradle Plugin's native
     * `reports/coverage`), so it works regardless of which produced the report.
     */
    fun findReportXml(): File? = pickReportXml(reportXmlCandidates(), projectRoot, tool)

    companion object {
        val ALL_REPORT_DIRS = listOf("/reports/jacoco/", "/reports/kover/", "/reports/coverage/")
        val CANONICAL_NAMES = setOf("jacocoTestReport.xml", "report.xml")

        /** The project's Gradle wrapper when it has one, else `gradle` from PATH. */
        fun gradleExecutable(projectRoot: File): String {
            val wrapper = File(projectRoot, "gradlew")
            return if (wrapper.exists()) wrapper.absolutePath else "gradle"
        }

        /**
         * Choose the coverage report among [candidates]. Prefers the selected
         * [tool]'s report directory, then — because multi-module Kover/JaCoCo emit
         * a per-module report *and* an aggregated one at the project root — the
         * shallowest file (the aggregate covers every module), then a canonical
         * filename, then path for determinism.
         */
        fun pickReportXml(candidates: List<File>, projectRoot: File, tool: CoverageTool): File? {
            val preferenceOrder = buildList {
                add(tool.reportDirMarker)
                addAll(ALL_REPORT_DIRS.filter { it != tool.reportDirMarker })
            }
            for (marker in preferenceOrder) {
                val inDir = candidates.filter { it.absolutePath.replace('\\', '/').contains(marker) }
                if (inDir.isEmpty()) continue
                return inDir.sortedWith(
                    compareBy(
                        { depthFromRoot(projectRoot, it) },
                        { if (it.name in CANONICAL_NAMES) 0 else 1 },
                        { it.absolutePath },
                    ),
                ).first()
            }
            return null
        }

        private fun depthFromRoot(projectRoot: File, file: File): Int =
            runCatching { projectRoot.toPath().relativize(file.toPath()).nameCount }.getOrDefault(Int.MAX_VALUE)
    }
}
