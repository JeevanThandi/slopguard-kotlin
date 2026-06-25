package dev.slopguard.coverage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleRunnerTest {
    private val root = File("/repo")

    @Test
    fun prefersAggregatedRootReportOverPerModule() {
        val candidates = listOf(
            File("/repo/core/build/reports/kover/report.xml"),
            File("/repo/cli/build/reports/kover/report.xml"),
            File("/repo/build/reports/kover/report.xml"), // the aggregate
        )
        val pick = GradleRunner.pickReportXml(candidates, root, CoverageTool.KOVER)
        assertEquals(File("/repo/build/reports/kover/report.xml"), pick)
    }

    @Test
    fun prefersSelectedToolDirectory() {
        val candidates = listOf(
            File("/repo/build/reports/jacoco/test/jacocoTestReport.xml"),
            File("/repo/build/reports/kover/report.xml"),
        )
        assertEquals(
            File("/repo/build/reports/kover/report.xml"),
            GradleRunner.pickReportXml(candidates, root, CoverageTool.KOVER),
        )
        assertEquals(
            File("/repo/build/reports/jacoco/test/jacocoTestReport.xml"),
            GradleRunner.pickReportXml(candidates, root, CoverageTool.JACOCO),
        )
    }

    @Test
    fun fallsBackAcrossToolDirectories() {
        // Selected tool is Kover, but only an AGP-native coverage report exists.
        val candidates = listOf(File("/repo/app/build/reports/coverage/test/debug/report.xml"))
        assertEquals(candidates[0], GradleRunner.pickReportXml(candidates, root, CoverageTool.KOVER))
    }

    @Test
    fun noCoverageReportYieldsNull() {
        val candidates = listOf(File("/repo/build/test-results/foo.xml"))
        assertNull(GradleRunner.pickReportXml(candidates, root, CoverageTool.KOVER))
    }
}
