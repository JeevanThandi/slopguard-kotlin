package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.errors.SlopguardError
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How one test-command invocation ended. */
data class CommandOutcome(
    /** The exit code, or null when the timeout killed the process. */
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMillis: Long,
    /** Bounded tail of the combined stdout/stderr. */
    val outputTail: String,
)

/** The run was interrupted by SIGINT / SIGTERM / SIGHUP; the shutdown hook restores the workspace. */
class MutationInterruptedException : RuntimeException("interrupted")

/**
 * Runs the project's test command for `mutate`: the baselines and one run per
 * mutant. Output is drained into a bounded tail and streamed to the reporter
 * (visible only with `--verbose`). A timeout kills the process **and every
 * descendant**, so an infinite-loop mutant never leaves a spinning process.
 */
class CommandRunner(
    private val reporter: ProgressReporter,
    /** Extra environment for every run. `CI=1` keeps tools non-interactive. */
    private val environment: Map<String, String> = mapOf("CI" to "1"),
) {
    private val lock = Any()
    private var active: Process? = null
    private var closed = false

    /**
     * Run [command] in [workingDir] to completion, or until [timeoutMillis]
     * passes (null = no limit). [onLine] sees every output line.
     *
     * @throws SlopguardError `runner_unavailable` when the command cannot be launched.
     * @throws MutationInterruptedException when [shutdown] stopped the run.
     */
    fun run(
        command: List<String>,
        workingDir: File,
        timeoutMillis: Long?,
        onLine: (String) -> Unit = {},
    ): CommandOutcome {
        val started = System.nanoTime()
        val process = start(command, workingDir)
        try {
            val tail = OutputTail()
            val drainer = drain(process, tail, onLine)
            val finished = if (timeoutMillis == null) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            if (!finished) ProcessTree.kill(process.toHandle())
            process.waitFor()
            drainer.join(DRAIN_WAIT_MILLIS)
            if (isClosed()) throw MutationInterruptedException()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            return CommandOutcome(if (finished) process.exitValue() else null, !finished, elapsed, tail.text())
        } finally {
            synchronized(lock) { active = null }
        }
    }

    /** Kill the running command (and its descendants), and refuse to start new ones. Safe to call at any time. */
    fun shutdown() {
        val running = synchronized(lock) {
            closed = true
            active
        }
        running?.let { ProcessTree.kill(it.toHandle()) }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun start(command: List<String>, workingDir: File): Process = synchronized(lock) {
        if (closed) throw MutationInterruptedException()
        val builder = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw SlopguardError.runnerUnavailable("could not launch ${command.first()}: ${e.message ?: e}")
        }
        active = process
        process
    }

    private fun drain(process: Process, tail: OutputTail, onLine: (String) -> Unit): Thread {
        val thread = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    reporter.raw(line)
                    tail.add(line)
                    onLine(line)
                }
            }
        }, "slopguard-output-drain")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /** The last [TAIL_LINES] output lines. Written by the drain thread, read by the caller. */
    private class OutputTail {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(line)
            if (lines.size > TAIL_LINES) lines.removeFirst()
        }

        @Synchronized
        fun text(): String = lines.joinToString("\n")
    }

    private companion object {
        const val TAIL_LINES = 80

        /** How long to wait for the output pipe to close after the process ended. */
        const val DRAIN_WAIT_MILLIS = 5_000L
    }
}

/** Kills a process together with every descendant it spawned. */
object ProcessTree {
    /** Grace period between the polite signal (SIGTERM) and the forced kill (SIGKILL). */
    private const val GRACE_MILLIS = 2_000L

    fun kill(root: ProcessHandle) {
        // Snapshot the descendants first: once the root dies they are re-parented and unreachable.
        val handles = listOf(root) + root.descendants().toList()
        handles.forEach { it.destroy() }
        awaitExit(handles, GRACE_MILLIS)
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
        awaitExit(handles, GRACE_MILLIS)
    }

    /** Wait up to [millis] for every handle to exit. */
    fun awaitExit(handles: List<ProcessHandle>, millis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        for (handle in handles) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            runCatching { handle.onExit().get(remaining, TimeUnit.NANOSECONDS) }
        }
    }
}
