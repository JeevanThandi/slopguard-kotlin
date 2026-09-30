package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.errors.SlopguardError
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandRunnerTest {
    private val dir: File = Files.createTempDirectory("runner").toFile()
    private val quiet = ProgressReporter(Verbosity.SILENT)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    @Test
    fun capturesExitCodeTailAndEveryLine() {
        val lines = ArrayList<String>()
        val outcome = CommandRunner(quiet).run(sh("echo one; echo two >&2; echo \"ci=${'$'}CI\"; exit 3"), dir, null) { lines.add(it) }
        assertEquals(3, outcome.exitCode)
        assertFalse(outcome.timedOut)
        assertEquals(listOf("one", "two", "ci=1"), lines)
        assertEquals("one\ntwo\nci=1", outcome.outputTail)
    }

    @Test
    fun tailKeepsTheLastEightyLines() {
        val outcome = CommandRunner(quiet).run(sh("i=0; while [ ${'$'}i -lt 100 ]; do echo line${'$'}i; i=${'$'}((i+1)); done"), dir, null)
        val tail = outcome.outputTail.lines()
        assertEquals(80, tail.size)
        assertEquals("line20", tail.first())
        assertEquals("line99", tail.last())
    }

    @Test
    fun verboseModeStreamsOutputToStderr() {
        val err = ByteArrayOutputStream()
        CommandRunner(ProgressReporter(Verbosity.VERBOSE, PrintStream(err))).run(sh("echo streamed"), dir, 60_000)
        assertTrue(err.toString().contains("streamed"))
    }

    @Test
    fun timeoutKillsTheWholeProcessTree() {
        val pids = File(dir, "pids")
        val script = "sleep 300 & echo ${'$'}! > ${pids.path}; sleep 300 & echo ${'$'}! >> ${pids.path}; wait"
        val started = System.nanoTime()
        val outcome = CommandRunner(quiet).run(sh(script), dir, timeoutMillis = 1_000)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(outcome.timedOut)
        assertNull(outcome.exitCode)
        assertTrue(elapsedMillis < 60_000, "took $elapsedMillis ms")
        val children = pids.readLines().filter { it.isNotBlank() }.map { it.trim().toLong() }
        assertEquals(2, children.size)
        for (pid in children) {
            val handle = ProcessHandle.of(pid)
            assertTrue(handle.isEmpty || !handle.get().isAlive, "child $pid is still running")
        }
    }

    @Test
    fun unlaunchableCommandIsRunnerUnavailable() {
        val error = assertFailsWith<SlopguardError> {
            CommandRunner(quiet).run(listOf(File(dir, "no-such-gradlew").path, "test"), dir, null)
        }
        assertEquals("runner_unavailable", error.code.wire)
        assertTrue(error.message.startsWith("Test runner is unavailable: could not launch"))
    }

    @Test
    fun shutdownStopsTheRunningCommandAndRefusesNewOnes() {
        val runner = CommandRunner(quiet)
        val marker = File(dir, "started")
        thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (!marker.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            runner.shutdown()
        }
        assertFailsWith<MutationInterruptedException> {
            runner.run(sh("touch ${marker.path}; sleep 300"), dir, null)
        }
        assertFailsWith<MutationInterruptedException> { runner.run(sh("echo never"), dir, null) }
    }

    @Test
    fun processTreeKillStopsAnIgnoringProcess() {
        // The child ignores SIGTERM, so only the forced kill can stop it.
        val process = ProcessBuilder(sh("trap '' TERM; while true; do sleep 1; done")).start()
        Thread.sleep(300)
        ProcessTree.kill(process.toHandle())
        assertFalse(process.isAlive)
    }
}
