package dev.slopguard.mutation

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interrupts a real `mutate` run (in a child JVM) while a mutant is in place:
 * the shutdown hook must kill the test run, restore the file and its mtime,
 * release the guard, say so on stderr, and exit with 128 + the signal number.
 */
class SignalRestoreTest {
    private val calc = "package demo\n\nfun add(a: Int, b: Int) = a + b\n"
    private val mtime = 1_600_000_000_000L

    // `kill`, not Process.destroy(): destroy() also closes our end of the child's output pipe.
    @Test
    fun sigtermRestoresTheFileAndExits143() = interruptWith(143) { signal(it, "TERM") }

    @Test
    fun sigintRestoresTheFileAndExits130() = interruptWith(130) { signal(it, "INT") }

    private fun signal(process: Process, name: String) {
        ProcessBuilder("kill", "-$name", process.pid().toString()).start().waitFor()
    }

    private fun interruptWith(expectedExit: Int, send: (Process) -> Unit) {
        // The unmutated baseline passes; the mutant run hangs and records its sleeping child.
        val project = FakeGradleProject(
            "if grep -q 'a - b' src/main/kotlin/demo/Calc.kt; then sleep 300 & echo \$! > sleep.pid; wait; fi\nexit 0",
        )
        try {
            val source = project.source("Calc.kt", calc).apply { setLastModified(mtime) }
            val process = ProcessBuilder(
                ProcessHandle.current().info().command().get(),
                "-cp",
                System.getProperty("java.class.path"),
                SignalFixture::class.java.name,
                project.root.path,
                project.tempRoot.path,
            ).redirectErrorStream(true).start()
            val output = StringBuffer()
            val drain = thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }

            val sleepPid = File(project.root, "sleep.pid")
            val deadline = System.currentTimeMillis() + 180_000
            while (!sleepPid.exists() || sleepPid.readText().isBlank()) {
                assertTrue(process.isAlive, "mutate ended before the mutant ran:\n$output")
                assertTrue(System.currentTimeMillis() < deadline, "the mutant never ran:\n$output")
                Thread.sleep(100)
            }
            assertEquals(calc.replace("a + b", "a - b"), source.readText())

            send(process)
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "mutate did not exit:\n$output")
            drain.join(10_000)

            assertEquals(expectedExit, process.exitValue(), output.toString())
            assertEquals(calc, source.readText())
            assertEquals(mtime, source.lastModified())
            assertTrue(output.contains("slopguard: interrupted — restored ${source.path}"), output.toString())
            assertFalse(output.contains("finished without a signal"), output.toString())
            val guard = WorkspaceGuard.directoryFor(project.root, project.tempRoot)
            assertFalse(File(guard, WorkspaceGuard.LOCK).exists())
            assertFalse(File(guard, WorkspaceGuard.JOURNAL).exists())
            val sleeper = ProcessHandle.of(sleepPid.readText().trim().toLong())
            assertTrue(sleeper.isEmpty || !sleeper.get().isAlive, "the hung test run is still alive")
        } finally {
            project.delete()
        }
    }
}
