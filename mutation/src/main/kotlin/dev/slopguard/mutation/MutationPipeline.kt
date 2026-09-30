package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Version
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.formatting.MutationReportFormatter
import dev.slopguard.core.mutation.MutantResult
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.core.mutation.MutationPlan
import dev.slopguard.core.mutation.MutationPlanner
import dev.slopguard.core.mutation.MutationReport
import dev.slopguard.core.mutation.MutationSummary
import dev.slopguard.core.mutation.PlannedFile
import dev.slopguard.core.mutation.PlannedMutant
import dev.slopguard.coverage.AnalysisPipeline
import dev.slopguard.coverage.CoverageIndex
import dev.slopguard.coverage.CoverageTool
import dev.slopguard.coverage.ProjectRootDiscovery
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

class MutationArgs(
    /** Directory or single `.kt` file to mutate. */
    val sourcePath: String,
    val analysisOptions: AnalysisOptions = AnalysisOptions(),
    /** Operators to apply. Default: all. */
    val operators: List<MutationOperator> = MutationOperator.ALL,
    /** The Gradle project the tests run in; discovered above [sourcePath] when null. */
    val projectDir: String? = null,
    /** Classify mutants on untested lines as `no_coverage` (runs the coverage baseline). */
    val coverage: Boolean = true,
    val coverageTool: CoverageTool = CoverageTool.KOVER,
    val testTask: String = "test",
    /** Explicit Gradle report task; when null, defaults to [coverageTool]'s task. */
    val reportTask: String? = null,
    /** Per-mutant timeout; computed from the plain baseline when null. */
    val timeoutSeconds: Double? = null,
    /** List mutants without running any test. */
    val dryRun: Boolean = false,
    val reporter: ProgressReporter,
)

/**
 * Orchestrates `mutate`: plan mutants → acquire the workspace guard → plain
 * baseline (the exact mutant command, unmutated) → coverage baseline → one
 * Gradle test run per mutant → report. Mutants run one at a time, in report
 * order, each written in place and restored by the [WorkspaceGuard] —
 * including on SIGINT / SIGTERM / SIGHUP, through a JVM shutdown hook.
 */
class MutationPipeline(
    /** Parent of the guard directories. Default: the OS temp dir. */
    private val tempRoot: File = WorkspaceGuard.defaultTempRoot(),
) {
    private class Execution(
        val plan: MutationPlan,
        val projectRoot: File,
        val timeoutSeconds: Double,
        val coverageAvailable: Boolean,
        val notes: List<String>,
        val statuses: List<MutantStatus>,
    )

    private class RunContext(
        val guard: WorkspaceGuard,
        val runner: GradleMutantRunner,
        val coverage: CoverageIndex?,
        val timeoutMillis: Long,
        val reporter: ProgressReporter,
        val notes: MutableList<String>,
    )

    fun run(args: MutationArgs): MutationReport {
        val reporter = args.reporter
        val operators = args.operators.distinct().sortedBy { it.wire }
        reporter.phase("walking ${File(args.sourcePath).absoluteFile.normalize().path}")
        val plan = plan(args, operators)
        val planned = plan.mutants
        reporter.phase("generated ${planned.size} mutant(s) in ${plan.files.size} file(s)")

        // A dry run — or nothing left to run — needs no project, guard or baseline.
        if (args.dryRun || planned.all { it.ignored }) {
            val statuses = planned.map { if (it.ignored) MutantStatus.IGNORED else MutantStatus.PENDING }
            return buildReport(plan, statuses, operators, execution = null)
        }
        val execution = execute(plan, args, operators)
        val report = buildReport(execution.plan, execution.statuses, operators, execution)
        val s = report.summary
        reporter.phase(
            "done — ${s.killed} killed, ${s.timedOut} timeout, ${s.survived} survived, " +
                "${s.noCoverage} no_coverage, ${s.compileErrors} compile_error, ${s.ignored} ignored",
        )
        return report
    }

    private fun plan(args: MutationArgs, operators: List<MutationOperator>): MutationPlan =
        MutationPlanner.planPath(args.sourcePath, args.analysisOptions, operators)

    private fun execute(planned: MutationPlan, args: MutationArgs, operators: List<MutationOperator>): Execution {
        val reporter = args.reporter
        val projectRoot = resolveProjectRoot(args.projectDir, planned.sourceRoot)
        val guard = WorkspaceGuard.acquire(projectRoot, tempRoot)
        val notes = guard.notes.toMutableList()
        val reportTask = args.reportTask ?: args.coverageTool.defaultReportTask
        val runner = GradleMutantRunner(projectRoot, args.testTask, reportTask, args.coverageTool, reporter)
        val hook = Thread({ onSignal(guard, runner, reporter) }, "slopguard-mutate-restore")
        try {
            Runtime.getRuntime().addShutdownHook(hook)
            // Recovery may have restored a file the plan was read from: plan again.
            val plan = if (notes.isNotEmpty()) plan(args, operators) else planned
            val timeoutSeconds = plainBaseline(runner, projectRoot, args.timeoutSeconds, reporter)
            val coverage = if (args.coverage) runner.runCoverage().also { notes.addAll(it.notes) }.index else null
            val context = RunContext(guard, runner, coverage, (timeoutSeconds * 1000).toLong(), reporter, notes)
            val statuses = runMutants(plan, context)
            return Execution(plan, projectRoot, timeoutSeconds, coverage != null, notes, statuses)
        } finally {
            try {
                runner.close()
                guard.release()
            } finally {
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    }

    /**
     * Run the exact mutant command once, unmutated and without a timeout. It
     * must pass: a broken command would otherwise "kill" every mutant. Returns
     * the per-mutant timeout in seconds.
     */
    private fun plainBaseline(
        runner: GradleMutantRunner,
        projectRoot: File,
        timeoutSeconds: Double?,
        reporter: ProgressReporter,
    ): Double {
        reporter.phase("running baseline tests (${runner.name}) in ${projectRoot.path}")
        val baseline = runner.runTests(timeoutMillis = null).outcome
        val exitCode = baseline.exitCode ?: -1
        if (exitCode != 0) {
            throw SlopguardError.baselineFailed(exitCode, baseline.outputTail.trim().ifEmpty { "no output captured" })
        }
        val seconds = baseline.durationMillis / 1000.0
        val timeout = timeoutSeconds ?: (ceil(seconds * 3) + TIMEOUT_GRACE_SECONDS)
        reporter.phase(
            String.format(Locale.ROOT, "baseline passed in %.1fs; timeout is %ss per mutant", seconds, MutationReportFormatter.number(timeout)),
        )
        return timeout
    }

    private fun runMutants(plan: MutationPlan, context: RunContext): List<MutantStatus> {
        val total = plan.mutants.size
        val statuses = ArrayList<MutantStatus>(total)
        for (file in plan.files) {
            var changed = false
            for (mutant in file.mutants) {
                var millis: Long? = null
                val status = when {
                    mutant.ignored -> MutantStatus.IGNORED
                    changed -> MutantStatus.PENDING
                    isUncovered(context.coverage, file, mutant) -> MutantStatus.NO_COVERAGE
                    else -> try {
                        val run = runMutant(file, mutant, context)
                        millis = run.outcome.durationMillis
                        context.runner.classify(run)
                    } catch (e: SourceChangedException) {
                        changed = true
                        context.notes.add("${file.file.path} changed while mutate was running, so its remaining mutants were not run.")
                        MutantStatus.PENDING
                    }
                }
                statuses.add(status)
                val site = mutant.site
                val timing = millis?.let { String.format(Locale.ROOT, " (%.1fs)", it / 1000.0) } ?: ""
                context.reporter.phase(
                    "[${statuses.size}/$total] ${status.wire.padEnd(13)} ${site.file}:${site.line}:${site.column} ${site.operator.wire}$timing",
                )
            }
        }
        return statuses
    }

    private fun runMutant(file: PlannedFile, mutant: PlannedMutant, context: RunContext): TestRun {
        val mutated = mutant.site.applyTo(file.source).toByteArray(Charsets.UTF_8)
        return context.guard.withMutant(file.file, file.bytes, mutated) { context.runner.runTests(context.timeoutMillis) }
    }

    /** Exactly 0% line coverage on the mutant's first line: no test runs it. Unknown lines are run. */
    private fun isUncovered(coverage: CoverageIndex?, file: PlannedFile, mutant: PlannedMutant): Boolean {
        val line = mutant.site.line
        return coverage?.methodCoverage(file.file.path, line, line) == 0.0
    }

    private fun buildReport(
        plan: MutationPlan,
        statuses: List<MutantStatus>,
        operators: List<MutationOperator>,
        execution: Execution?,
    ): MutationReport {
        val mutants = plan.mutants.mapIndexed { i, planned -> MutantResult.of(planned, statuses[i]) }
        val summary = MutationSummary.of(mutants, plan.files.size)
        return MutationReport(
            schemaVersion = Version.MUTATION_SCHEMA_VERSION,
            tool = Version.TOOL_NAME,
            toolVersion = Version.VERSION,
            generatedAt = AnalysisPipeline.TIMESTAMP.format(Instant.now()),
            sourceRoot = plan.sourceRoot.path,
            projectRoot = execution?.projectRoot?.path,
            runner = execution?.let { RUNNER_NAME },
            timeoutSeconds = execution?.timeoutSeconds,
            coverageAvailable = execution?.coverageAvailable ?: false,
            operators = operators,
            notes = execution?.notes.orEmpty() + resultNotes(summary),
            summary = summary,
            mutants = mutants,
        )
    }

    companion object {
        const val RUNNER_NAME = "gradle"

        /**
         * The shutdown hook's work on SIGINT / SIGTERM / SIGHUP: stop new mutants,
         * kill the test run, restore the file, release the guard, and say so.
         */
        internal fun onSignal(guard: WorkspaceGuard, runner: GradleMutantRunner, reporter: ProgressReporter) {
            // Once the run is killed, the main thread may restore the file first; remember which it was.
            val inFlight = guard.stopMutating()
            runner.shutdown()
            try {
                val restored = guard.interrupt() ?: inFlight
                reporter.phase(if (restored == null) "interrupted" else "interrupted — restored ${restored.path}")
            } catch (e: Exception) {
                reporter.phase(e.message ?: e.toString())
                reporter.phase("interrupted")
            }
        }

        /** Added to three times the plain baseline's run time: each mutant run includes a compile. */
        const val TIMEOUT_GRACE_SECONDS = 60

        const val NOTE_ALL_SURVIVED = "Every tested mutant survived. Check that the tests import the source under --path."

        private fun resultNotes(s: MutationSummary): List<String> = buildList {
            if (s.survived > 0 && s.killed + s.timedOut == 0) add(NOTE_ALL_SURVIVED)
            if (s.compileErrors > 0) add("${s.compileErrors} mutant(s) did not compile and are excluded from the score.")
        }

        private fun resolveProjectRoot(projectDir: String?, sourceRoot: File): File {
            val root = projectDir?.let { File(it).absoluteFile.normalize() }
                ?: ProjectRootDiscovery.discover(sourceRoot)
                ?: throw SlopguardError.projectRootNotFound(sourceRoot.path, "Pass --project-dir <dir>.")
            if (!root.exists()) throw SlopguardError.fileNotFound(root.path)
            if (!root.isDirectory) throw SlopguardError.notADirectory(root.path)
            return root
        }
    }
}
