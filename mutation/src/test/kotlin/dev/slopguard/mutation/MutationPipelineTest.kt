package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.core.mutation.MutationReport
import dev.slopguard.coverage.CoverageTool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationPipelineTest {
    private val projects = ArrayList<FakeGradleProject>()
    private val progress = ByteArrayOutputStream()
    private val reporter = ProgressReporter(Verbosity.NORMAL, PrintStream(progress, true))

    @AfterTest
    fun cleanup() = projects.forEach { it.delete() }

    private val calc = """
        package demo

        fun add(a: Int, b: Int) = a + b
        fun isPositive(x: Int) = x > 0
        fun spin(flag: Boolean): Boolean = !flag
    """.trimIndent() + "\n"

    /**
     * The fake build reacts to the mutant in place: `a - b` fails a test,
     * `x >= 0` passes, `x <= 0` does not compile and `= flag` hangs. The
     * coverage run reports lines 3-4 covered and line 5 missed.
     */
    private val calcBuild = listOf(
        "case \"\$*\" in *koverXmlReport*)",
        "mkdir -p build/reports/kover",
        "cat > build/reports/kover/report.xml <<'XML'",
        FakeGradleProject.reportXml("Calc.kt", covered = listOf(3, 4), missed = listOf(5)),
        "XML",
        "exit 0 ;;",
        "esac",
        "SRC=src/main/kotlin/demo/Calc.kt",
        "if grep -q 'a - b' \$SRC; then echo '> Task :test FAILED'; exit 1; fi",
        "if grep -q 'x >= 0' \$SRC; then exit 0; fi",
        "if grep -q 'x <= 0' \$SRC; then echo '> Task :compileKotlin FAILED'; exit 1; fi",
        "if grep -q '= flag' \$SRC; then sleep 300; fi",
        "exit 0",
    ).joinToString("\n")

    private fun project(body: String = calcBuild): FakeGradleProject = FakeGradleProject(body).also { projects.add(it) }

    private fun run(
        project: FakeGradleProject,
        coverage: Boolean = true,
        timeoutSeconds: Double? = null,
        dryRun: Boolean = false,
        operators: List<MutationOperator> = MutationOperator.ALL,
    ): MutationReport = MutationPipeline(project.tempRoot).run(
        MutationArgs(
            sourcePath = project.sourceDir.parentFile.path,
            operators = operators,
            projectDir = project.root.path,
            coverage = coverage,
            timeoutSeconds = timeoutSeconds,
            dryRun = dryRun,
            reporter = reporter,
        ),
    )

    private fun statuses(report: MutationReport) = report.mutants.associate { it.id to it.status }

    private fun guardDir(project: FakeGradleProject) = WorkspaceGuard.directoryFor(project.root, project.tempRoot)

    @Test
    fun fullRunClassifiesEveryMutantAndRestoresTheSource() {
        val project = project()
        val source = project.source("Calc.kt", calc).apply { setLastModified(1_600_000_000_000L) }

        val report = run(project)

        assertEquals(
            mapOf(
                "demo/Calc.kt:3:29:arithmetic" to MutantStatus.KILLED,
                "demo/Calc.kt:4:28:boundary" to MutantStatus.SURVIVED,
                "demo/Calc.kt:4:28:negate_conditional" to MutantStatus.COMPILE_ERROR,
                "demo/Calc.kt:5:36:remove_not" to MutantStatus.NO_COVERAGE,
            ),
            statuses(report),
        )
        val s = report.summary
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 4, 1), listOf(s.killed, s.survived, s.compileErrors, s.noCoverage, s.timedOut, s.ignored, s.mutantCount, s.fileCount))
        assertEquals(100.0 / 3.0, s.mutationScore!!, 1e-9)
        assertEquals(listOf("1 mutant(s) did not compile and are excluded from the score."), report.notes)
        assertTrue(report.coverageAvailable)
        assertEquals("gradle", report.runner)
        assertEquals(project.root.path, report.projectRoot)
        assertEquals(project.sourceDir.parentFile.path, report.sourceRoot)
        assertTrue(report.timeoutSeconds!! >= 61.0 && report.timeoutSeconds!! == Math.rint(report.timeoutSeconds!!))
        assertEquals("add", report.mutants.first().method)
        assertEquals("mutation", report.reportType)

        assertEquals(calc, source.readText())
        assertEquals(1_600_000_000_000L, source.lastModified())
        assertFalse(guardDir(project).exists())

        val invocations = project.invocationLines()
        assertEquals(5, invocations.size)
        assertTrue(invocations[0].startsWith("test --fail-fast --console=plain --init-script "))
        assertTrue(invocations[1].startsWith("test koverXmlReport --console=plain --continue"))

        val lines = progress.toString().lines()
        assertTrue(lines.any { it.startsWith("slopguard: baseline passed in ") && it.endsWith("s per mutant") })
        assertTrue(lines.any { it.startsWith("slopguard: [1/4] killed        demo/Calc.kt:3:29 arithmetic (") })
        assertTrue(lines.contains("slopguard: [4/4] no_coverage   demo/Calc.kt:5:36 remove_not"))
        assertTrue(lines.contains("slopguard: done — 1 killed, 0 timeout, 1 survived, 1 no_coverage, 1 compile_error, 0 ignored"))
    }

    @Test
    fun withoutCoverageEveryMutantRunsAndAHangTimesOut() {
        val project = project()
        project.source("Calc.kt", calc)

        val report = run(project, coverage = false, timeoutSeconds = 5.0)

        assertEquals(MutantStatus.TIMEOUT, statuses(report)["demo/Calc.kt:5:36:remove_not"])
        assertEquals(5.0, report.timeoutSeconds)
        assertFalse(report.coverageAvailable)
        assertEquals(2.0 / 3.0 * 100.0, report.summary.mutationScore!!, 1e-9)
        assertTrue(project.invocationLines().none { it.contains("koverXmlReport") })
        assertEquals(calc, File(project.sourceDir, "Calc.kt").readText())
    }

    @Test
    fun aFailingBaselineIsFatalAndTouchesNothing() {
        val project = project("echo 'CalcTest > add() FAILED'; exit 1")
        val source = project.source("Calc.kt", calc)
        val error = assertFailsWith<SlopguardError> { run(project) }
        assertEquals("baseline_failed", error.code.wire)
        assertEquals("The test suite fails without any mutation (exit 1). Fix the failing tests first: CalcTest > add() FAILED", error.message)
        assertEquals(calc, source.readText())
        assertFalse(guardDir(project).exists())
    }

    @Test
    fun aFailingCoverageRunIsNotFatal() {
        val project = project("case \"\$*\" in *koverXmlReport*) exit 1 ;; esac; exit 0")
        project.source("Calc.kt", "package demo\nfun add(a: Int, b: Int) = a + b\n")
        val report = run(project)
        assertFalse(report.coverageAvailable)
        assertEquals(
            listOf(GradleMutantRunner.NO_COVERAGE_NOTE, MutationPipeline.NOTE_ALL_SURVIVED),
            report.notes,
        )
        assertEquals(MutantStatus.SURVIVED, report.mutants.single().status)
    }

    @Test
    fun dryRunTouchesNoFileAndRunsNothing() {
        val project = project()
        project.source("Calc.kt", calc)
        val report = run(project, dryRun = true)
        assertTrue(report.mutants.all { it.status == MutantStatus.PENDING })
        assertNull(report.runner)
        assertNull(report.projectRoot)
        assertNull(report.timeoutSeconds)
        assertFalse(report.coverageAvailable)
        assertNull(report.summary.mutationScore)
        assertEquals(4, report.summary.pending)
        assertEquals(emptyList(), project.invocationLines())
        assertFalse(guardDir(project).exists())
    }

    @Test
    fun nothingLeftToRunSkipsTheBaseline() {
        val project = project()
        project.source("Calc.kt", "package demo\nfun add(a: Int, b: Int) = a + b // slopguard-ignore-mutant\n")
        project.source("Empty.kt", "package demo\nfun one() = 1\n")
        val report = run(project)
        assertEquals(listOf(MutantStatus.IGNORED), report.mutants.map { it.status })
        assertEquals(2, report.summary.fileCount)
        assertNull(report.runner)
        assertNull(report.timeoutSeconds)
        assertEquals(emptyList(), project.invocationLines())
    }

    @Test
    fun operatorsFilterWhatRuns() {
        val project = project()
        project.source("Calc.kt", calc)
        val report = run(project, coverage = false, operators = listOf(MutationOperator.ARITHMETIC))
        assertEquals(listOf("demo/Calc.kt:3:29:arithmetic"), report.mutants.map { it.id })
        assertEquals(listOf(MutationOperator.ARITHMETIC), report.operators)
        assertEquals(2, project.invocationLines().size)
    }

    @Test
    fun recoveryRestoresTheFileAndThePlanFollows() {
        val project = project()
        val source = project.source("Calc.kt", calc)
        val mutant = calc.replace("a + b", "a - b").toByteArray()
        val dir = guardDir(project).apply { mkdirs() }
        File(dir, WorkspaceGuard.LOCK).writeText("${Long.MAX_VALUE}")
        File(dir, WorkspaceGuard.ORIGINAL).writeText(calc)
        File(dir, WorkspaceGuard.JOURNAL).writeText(
            """{"file":"${source.path}","mutantSha256":"${WorkspaceGuard.sha256(mutant)}"}""",
        )
        source.writeBytes(mutant)

        val report = run(project, operators = listOf(MutationOperator.ARITHMETIC))

        assertEquals("Restored ${source.path}, which an interrupted mutate run left mutated.", report.notes.first())
        assertEquals("+", report.mutants.single().original)
        assertEquals(MutantStatus.KILLED, report.mutants.single().status)
        assertEquals(calc, source.readText())
    }

    @Test
    fun aFileThatChangesDuringTheRunIsLeftAlone() {
        // The baseline edits B.kt, so B's mutants no longer match the file.
        val project = project(
            "if [ ! -f edited ]; then touch edited; echo '// edited' >> src/main/kotlin/demo/B.kt; fi\n" +
                "if grep -q 'a - b' src/main/kotlin/demo/A.kt; then exit 1; fi\nexit 0",
        )
        project.source("A.kt", "package demo\nfun a(a: Int, b: Int) = a + b\n")
        val b = project.source("B.kt", "package demo\nfun b(x: Boolean) = !x\n")

        val report = run(project, coverage = false)

        assertEquals(MutantStatus.KILLED, statuses(report)["demo/A.kt:2:27:arithmetic"])
        assertEquals(MutantStatus.PENDING, statuses(report)["demo/B.kt:2:21:remove_not"])
        assertEquals(1, report.summary.pending)
        assertEquals(listOf("${b.path} changed while mutate was running, so its remaining mutants were not run."), report.notes)
        assertEquals("package demo\nfun b(x: Boolean) = !x\n// edited\n", b.readText())
    }

    @Test
    fun aMissingProjectDirectoryIsReported() {
        val project = project()
        project.source("Calc.kt", calc)
        val error = assertFailsWith<SlopguardError> {
            MutationPipeline(project.tempRoot).run(
                MutationArgs(sourcePath = project.sourceDir.path, projectDir = File(project.root, "nope").path, reporter = reporter),
            )
        }
        assertEquals("file_not_found", error.code.wire)
    }

    @Test
    fun noGradleRootAboveThePathIsReported() {
        val project = project()
        File(project.root, "settings.gradle.kts").delete()
        File(project.root, "gradlew").delete()
        project.source("Calc.kt", calc)
        val error = assertFailsWith<SlopguardError> {
            MutationPipeline(project.tempRoot).run(MutationArgs(sourcePath = project.sourceDir.path, reporter = reporter))
        }
        assertEquals("project_root_not_found", error.code.wire)
        assertTrue(error.message.endsWith("Pass --project-dir <dir>."))
    }

    @Test
    fun anotherLiveRunHoldsTheProject() {
        val project = project()
        project.source("Calc.kt", calc)
        val dir = guardDir(project).apply { mkdirs() }
        File(dir, WorkspaceGuard.LOCK).writeText(ProcessHandle.current().parent().get().pid().toString())
        val error = assertFailsWith<SlopguardError> { run(project) }
        assertEquals("mutation_in_progress", error.code.wire)
        assertEquals(emptyList(), project.invocationLines())
    }

    @Test
    fun theSignalHookKillsTheRunRestoresTheFileAndSaysSo() {
        val project = project("sleep 300")
        val source = project.source("Calc.kt", calc)
        val guard = WorkspaceGuard.acquire(project.root, project.tempRoot)
        val runner = GradleMutantRunner(project.root, "test", "koverXmlReport", CoverageTool.KOVER, reporter)
        var failure: Throwable? = null
        val main = thread {
            failure = runCatching {
                guard.withMutant(source, calc.toByteArray(), calc.replace("a + b", "a - b").toByteArray()) { runner.runTests(null) }
            }.exceptionOrNull()
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (project.invocationLines().isEmpty()) {
            assertTrue(System.currentTimeMillis() < deadline, "the fake test run never started")
            Thread.sleep(50)
        }

        MutationPipeline.onSignal(guard, runner, reporter)
        main.join(60_000)

        assertTrue(failure is MutationInterruptedException, failure.toString())
        assertEquals(calc, source.readText())
        assertFalse(guardDir(project).exists())
        assertTrue(progress.toString().contains("slopguard: interrupted — restored ${source.path}\n"), progress.toString())
        runner.close()
    }

    @Test
    fun theSignalHookWithNothingInFlightJustReleases() {
        val project = project()
        val guard = WorkspaceGuard.acquire(project.root, project.tempRoot)
        val runner = GradleMutantRunner(project.root, "test", "koverXmlReport", CoverageTool.KOVER, reporter)
        MutationPipeline.onSignal(guard, runner, reporter)
        assertEquals("slopguard: interrupted\n", progress.toString())
        assertFalse(guardDir(project).exists())
    }
}
