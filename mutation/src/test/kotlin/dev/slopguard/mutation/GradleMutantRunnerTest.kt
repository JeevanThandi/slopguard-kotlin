package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.coverage.CoverageTool
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleMutantRunnerTest {
    private val projects = ArrayList<FakeGradleProject>()

    @AfterTest
    fun cleanup() = projects.forEach { it.delete() }

    private fun project(body: String) = FakeGradleProject(body).also { projects.add(it) }

    private fun runner(project: FakeGradleProject) =
        GradleMutantRunner(project.root, "test", "koverXmlReport", CoverageTool.KOVER, ProgressReporter(Verbosity.SILENT))

    // ---- commands ----------------------------------------------------------

    @Test
    fun mutantCommandRunsTheTestTaskFailFastWithCoverageSwitchedOff() {
        val project = project("exit 0")
        val runner = runner(project)
        val command = runner.mutantCommand()
        assertEquals(listOf(File(project.root, "gradlew").path, "test", "--fail-fast", "--console=plain", "--init-script"), command.dropLast(1))
        val script = File(command.last())
        assertTrue(script.readText().contains("task.enabled = false"))
        assertTrue(script.readText().contains("jacoco.enabled = false"))
        assertFalse(script.path.startsWith(project.root.path))
        assertEquals(command, runner.mutantCommand())
        runner.close()
        assertFalse(script.exists())
    }

    @Test
    fun withoutAWrapperTheCommandUsesGradleFromPath() {
        val project = project("exit 0")
        File(project.root, "gradlew").delete()
        assertEquals("gradle", runner(project).mutantCommand().first())
    }

    // ---- classification ----------------------------------------------------

    private fun classify(body: String, timeoutMillis: Long? = 60_000): MutantStatus {
        val runner = runner(project(body))
        return runner.classify(runner.runTests(timeoutMillis)).also { runner.close() }
    }

    @Test
    fun passingTestsMeanTheMutantSurvived() {
        assertEquals(MutantStatus.SURVIVED, classify("echo '> Task :test'; exit 0"))
    }

    @Test
    fun failingTestsMeanTheMutantWasKilled() {
        assertEquals(MutantStatus.KILLED, classify("echo '> Task :test FAILED'; echo 'There were failing tests.'; exit 1"))
    }

    @Test
    fun aFailedCompileTaskIsACompileError() {
        assertEquals(MutantStatus.COMPILE_ERROR, classify("echo '> Task :app:compileKotlin FAILED'; exit 1"))
    }

    @Test
    fun aKotlinCompilerErrorIsACompileError() {
        assertEquals(
            MutantStatus.COMPILE_ERROR,
            classify("echo 'e: file:///p/src/main/kotlin/demo/Calc.kt:3:28 Type mismatch: inferred type is String but Int was expected'; exit 1"),
        )
    }

    @Test
    fun compileMarkersNeverOverrideASuccessfulRun() {
        assertEquals(MutantStatus.SURVIVED, classify("echo 'e: file:///p/Calc.kt:1:1 warning turned info'; exit 0"))
    }

    @Test
    fun aHangingRunTimesOut() {
        val started = System.nanoTime()
        assertEquals(MutantStatus.TIMEOUT, classify("sleep 300", timeoutMillis = 1_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 60_000)
    }

    @Test
    fun aTestWorkerThatOutlivesTheTimeoutIsKilled() {
        val project = project("sleep 300")
        // A stand-in for a Gradle test JVM of this project that ignores the build's cancellation.
        val marker = "-Dorg.gradle.internal.worker.tmpdir=${project.root.path}/build/tmp/test/work"
        val worker = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 300; true", marker).start()
        try {
            val runner = GradleMutantRunner(
                project.root, "test", "koverXmlReport", CoverageTool.KOVER, ProgressReporter(Verbosity.SILENT),
                workerStopMillis = 500,
            )
            assertTrue(runner.runTests(1_000).outcome.timedOut)
            assertTrue(worker.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "the lingering worker is still running")
        } finally {
            worker.destroyForcibly()
        }
    }

    @Test
    fun compileFailureDetection() {
        val compileFailures = listOf(
            "> Task :compileKotlin FAILED",
            "> Task :app:compileDebugKotlin FAILED",
            "> Task :core:compileTestKotlin FAILED",
            "> Task :compileJava FAILED",
            "> Task :compileTestJava FAILED",
            "Execution failed for task ':app:compileDebugUnitTestKotlin'.",
            "e: file:///Users/me/p/src/main/kotlin/A.kt:3:5 Unresolved reference: x",
            "e: /Users/me/p/src/main/kotlin/A.kt: (3, 5): Unresolved reference: x",
        )
        compileFailures.forEach { assertTrue(GradleMutantRunner.isCompileFailure(it), it) }
        val others = listOf(
            "> Task :test FAILED",
            "> Task :compileKotlin",
            "> Task :compileKotlin UP-TO-DATE",
            "e: Daemon compilation failed: Could not connect to kotlin daemon",
            "Execution failed for task ':test'.",
            "CalcTest > add() FAILED",
            // Test output that quotes a marker is indented: a failing test, not a compile failure.
            "    expected: <Execution failed for task ':core:compileKotlin'.> but was: <ok>",
            "    > Task :core:compileKotlin FAILED",
            "    e: file:///Users/me/p/src/main/kotlin/A.kt:3:5 Unresolved reference: x",
        )
        others.forEach { assertFalse(GradleMutantRunner.isCompileFailure(it), it) }
    }

    @Test
    fun workerMarkersNameTheProjectsWorkerTempDir() {
        val project = project("exit 0")
        val markers = GradleMutantRunner.workerMarkers(project.root)
        assertTrue(markers.contains("-Dorg.gradle.internal.worker.tmpdir=${project.root.path}/"))
    }

    // ---- coverage baseline -------------------------------------------------

    private fun reportScript(exitCode: Int) = listOf(
        "mkdir -p build/reports/kover",
        "cat > build/reports/kover/report.xml <<'XML'",
        FakeGradleProject.reportXml("Calc.kt", covered = listOf(3), missed = listOf(4)),
        "XML",
        "exit $exitCode",
    ).joinToString("\n")

    @Test
    fun coverageBaselineLoadsTheReportTheRunWrote() {
        val project = project(reportScript(0))
        val baseline = runner(project).runCoverage()
        val index = assertNotNull(baseline.index)
        assertEquals(emptyList(), baseline.notes)
        assertEquals(100.0, index.methodCoverage(File(project.sourceDir, "Calc.kt").path, 3, 3))
        assertEquals(0.0, index.methodCoverage(File(project.sourceDir, "Calc.kt").path, 4, 4))
        assertTrue(project.invocationLines().single().startsWith("test koverXmlReport --console=plain --continue"))
    }

    @Test
    fun aFailingCoverageRunStillLendsItsFreshData() {
        val baseline = runner(project(reportScript(1))).runCoverage()
        assertNotNull(baseline.index)
        assertEquals(listOf("The coverage run exited with code 1; its coverage data was still used."), baseline.notes)
    }

    @Test
    fun aFailingCoverageRunIgnoresAStaleReport() {
        val project = project("exit 1")
        val stale = File(project.root, "build/reports/kover/report.xml").apply {
            parentFile.mkdirs()
            writeText(FakeGradleProject.reportXml("Calc.kt", listOf(3), emptyList()))
            setLastModified(System.currentTimeMillis() - 3_600_000)
        }
        val baseline = runner(project).runCoverage()
        assertNull(baseline.index)
        assertEquals(listOf(GradleMutantRunner.NO_COVERAGE_NOTE), baseline.notes)
        assertTrue(stale.exists())
    }

    @Test
    fun aPassingRunWithAnUpToDateReportUsesIt() {
        val project = project("exit 0")
        File(project.root, "build/reports/kover/report.xml").apply {
            parentFile.mkdirs()
            writeText(FakeGradleProject.reportXml("Calc.kt", listOf(3), emptyList()))
            setLastModified(System.currentTimeMillis() - 3_600_000)
        }
        assertNotNull(runner(project).runCoverage().index)
    }

    @Test
    fun aCoverageRunThatCannotStartMeansNoCoverage() {
        val project = project("exit 0")
        File(project.root, "gradlew").setExecutable(false)
        val baseline = runner(project).runCoverage()
        assertNull(baseline.index)
        assertEquals(listOf(GradleMutantRunner.NO_COVERAGE_NOTE), baseline.notes)
    }

    @Test
    fun noReportOrAnEmptyReportMeansNoCoverage() {
        assertEquals(listOf(GradleMutantRunner.NO_COVERAGE_NOTE), runner(project("exit 0")).runCoverage().notes)
        val empty = project(
            """
            mkdir -p build/reports/kover
            echo '<report name="demo"></report>' > build/reports/kover/report.xml
            exit 0
            """.trimIndent(),
        )
        assertNull(runner(empty).runCoverage().index)
        val garbage = project("mkdir -p build/reports/kover; echo 'not xml' > build/reports/kover/report.xml; exit 0")
        assertNull(runner(garbage).runCoverage().index)
    }
}
