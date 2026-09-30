package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.coverage.CoverageIndex
import dev.slopguard.coverage.CoverageTool
import dev.slopguard.coverage.GradleRunner
import dev.slopguard.coverage.JacocoReport
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** One test run plus what its output revealed. */
data class TestRun(val outcome: CommandOutcome, val compileFailed: Boolean)

/** The coverage baseline: the index (null when the run gave no usable data) and the notes it earned. */
data class CoverageBaseline(val index: CoverageIndex?, val notes: List<String>)

/**
 * Drives the project's Gradle build for `mutate`, through the wrapper like the
 * `analyze` coverage runner does.
 *
 * - Mutant command (also the plain baseline): `./gradlew <test-task> --fail-fast
 *   --console=plain --init-script <script>`. The init script switches off every
 *   Kover / JaCoCo report and verification task and the JaCoCo test agent, so a
 *   coverage threshold in the project's build can never fail a mutant run and
 *   fake a kill.
 * - Coverage baseline: the `analyze` command (`<test-task> <report-task>
 *   --console=plain --continue`) and report lookup, reused from [GradleRunner].
 */
class GradleMutantRunner(
    private val projectRoot: File,
    private val testTask: String,
    private val reportTask: String,
    private val coverageTool: CoverageTool,
    private val reporter: ProgressReporter,
    private val commands: CommandRunner = CommandRunner(reporter),
    /** How long a timed-out run's test workers get to stop before they are killed. */
    private val workerStopMillis: Long = WORKER_STOP_MILLIS,
) : AutoCloseable {
    val name: String = "gradle"

    private var initScriptFile: File? = null

    fun mutantCommand(): List<String> = listOf(
        GradleRunner.gradleExecutable(projectRoot),
        testTask,
        "--fail-fast",
        "--console=plain",
        "--init-script",
        initScript().absolutePath,
    )

    /** Run the mutant test command once (the plain baseline passes no timeout). */
    fun runTests(timeoutMillis: Long?): TestRun {
        val compileFailed = AtomicBoolean(false)
        val outcome = commands.run(mutantCommand(), projectRoot, timeoutMillis) { line ->
            if (isCompileFailure(line)) compileFailed.set(true)
        }
        if (outcome.timedOut) stopLingeringTestWorkers()
        return TestRun(outcome, compileFailed.get())
    }

    /**
     * The coverage baseline. It never fails the run: a non-zero exit with a fresh
     * report still yields coverage (with a note), and no usable report — or a
     * coverage run that could not even start — means every mutant is tested
     * (with the no-coverage note).
     */
    fun runCoverage(): CoverageBaseline {
        val gradle = GradleRunner(projectRoot, testTask, reportTask, coverageTool, reporter)
        reporter.phase("running gradle with coverage in ${projectRoot.path} — this can take a while")
        val started = System.currentTimeMillis()
        val outcome = try {
            commands.run(gradle.command(), projectRoot, timeoutMillis = null)
        } catch (e: SlopguardError) {
            return CoverageBaseline(null, listOf(NO_COVERAGE_NOTE))
        }
        val candidates = gradle.reportXmlCandidates()
        // Prefer a report this run wrote: an older one may describe other code.
        val fresh = candidates.filter { it.lastModified() >= started - CLOCK_SLACK_MILLIS }
        val xml = GradleRunner.pickReportXml(fresh, projectRoot, coverageTool)
            ?: if (outcome.exitCode == 0) GradleRunner.pickReportXml(candidates, projectRoot, coverageTool) else null
        val index = xml?.let { loadIndex(it) } ?: return CoverageBaseline(null, listOf(NO_COVERAGE_NOTE))
        val notes = if (outcome.exitCode == 0) {
            emptyList()
        } else {
            listOf("The coverage run exited with code ${outcome.exitCode}; its coverage data was still used.")
        }
        return CoverageBaseline(index, notes)
    }

    fun classify(run: TestRun): MutantStatus = when {
        run.outcome.timedOut -> MutantStatus.TIMEOUT
        run.outcome.exitCode == 0 -> MutantStatus.SURVIVED
        run.compileFailed -> MutantStatus.COMPILE_ERROR
        else -> MutantStatus.KILLED
    }

    /** Kill the running Gradle client and refuse new runs (the signal path). */
    fun shutdown() = commands.shutdown()

    /** Delete the init script written for this run. */
    override fun close() {
        initScriptFile?.delete()
        initScriptFile = null
    }

    private fun initScript(): File = initScriptFile ?: Files.createTempFile("slopguard-mutate-", ".gradle").toFile().also {
        it.writeText(INIT_SCRIPT)
        it.deleteOnExit()
        initScriptFile = it
    }

    /**
     * A killed Gradle client makes the daemon cancel the build, and the daemon
     * then stops the test JVM (about a second in practice). As a safety net, wait
     * for this project's test workers — found by the worker temp dir Gradle puts
     * on their command line — and kill any that are still running.
     */
    private fun stopLingeringTestWorkers() {
        val markers = workerMarkers(projectRoot)
        val workers = ProcessHandle.allProcesses()
            .filter { handle -> handle.info().commandLine().map { line -> markers.any { it in line } }.orElse(false) }
            .toList()
        if (workers.isEmpty()) return
        ProcessTree.awaitExit(workers, workerStopMillis)
        workers.filter { it.isAlive }.forEach { ProcessTree.kill(it) }
    }

    private fun loadIndex(xml: File): CoverageIndex? =
        runCatching { CoverageIndex(JacocoReport.parse(xml)) }.getOrNull()?.takeIf { it.hasData }

    companion object {
        const val NO_COVERAGE_NOTE = "The baseline test run produced no coverage data, so every mutant was run."

        /** Tolerance for coarse file-system timestamps when telling a fresh report from an old one. */
        private const val CLOCK_SLACK_MILLIS = 2_000L

        const val WORKER_STOP_MILLIS = 10_000L

        private val FAILED_COMPILE_TASK = Regex("""^> Task (\S*:)?compile\S* FAILED""")
        private val FAILED_COMPILE_EXECUTION = Regex("""^Execution failed for task '(\S*:)?compile[^']*'""")

        /** A Kotlin compiler diagnostic: `e: file:///…/Foo.kt:3:5 …` (or a plain path in older compilers). */
        private val KOTLIN_ERROR = Regex("""^e: (file:)?\S+\.kts?\b""")

        /** Whether an output line shows that compilation failed (the mutant does not compile). */
        fun isCompileFailure(line: String): Boolean =
            FAILED_COMPILE_TASK.containsMatchIn(line) ||
                FAILED_COMPILE_EXECUTION.containsMatchIn(line) ||
                KOTLIN_ERROR.containsMatchIn(line)

        /** Command-line fragments that identify a test worker JVM of a build under [projectRoot]. */
        fun workerMarkers(projectRoot: File): List<String> {
            val paths = listOfNotNull(
                projectRoot.absoluteFile.normalize().path,
                runCatching { projectRoot.toPath().toRealPath().toString() }.getOrNull(),
            ).distinct()
            return paths.map { "-Dorg.gradle.internal.worker.tmpdir=$it${File.separator}" }
        }

        /**
         * Gradle init script for mutant runs: coverage reports and verification
         * are switched off, whatever the project's build wires to its tests.
         */
        val INIT_SCRIPT: String = """
            // Written by slopguard-kotlin mutate. Mutant runs must never fail on a coverage
            // threshold, so every Kover / JaCoCo report and verification task is switched off,
            // and the JaCoCo test agent too.
            allprojects {
                tasks.configureEach { task ->
                    def name = task.name
                    if ((name.startsWith("kover") || name.startsWith("jacoco")) &&
                            (name.contains("Report") || name.contains("Verif"))) {
                        task.enabled = false
                    }
                }
                tasks.withType(Test).configureEach { test ->
                    def jacoco = test.extensions.findByName("jacoco")
                    if (jacoco != null) {
                        jacoco.enabled = false
                    }
                }
            }
        """.trimIndent() + "\n"
    }
}
