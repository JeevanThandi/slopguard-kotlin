package dev.slopguard.coverage

import dev.slopguard.core.ProgressReporter
import java.io.BufferedReader
import java.io.File

data class ProcessOutcome(val exitCode: Int, val outputTail: String)

/**
 * Runs a subprocess, streaming its output to the reporter in verbose mode and
 * retaining a bounded tail for error messages. The only process slopguard-kotlin
 * ever spawns is the project's own Gradle test run.
 */
object ProcessRunner {
    private const val TAIL_LINES = 80

    fun run(command: List<String>, workingDir: File, reporter: ProgressReporter): ProcessOutcome {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        val tail = ArrayDeque<String>()
        process.inputStream.bufferedReader().use { reader: BufferedReader ->
            reader.lineSequence().forEach { line ->
                reporter.raw(line)
                tail.addLast(line)
                if (tail.size > TAIL_LINES) tail.removeFirst()
            }
        }
        val code = process.waitFor()
        return ProcessOutcome(code, tail.joinToString("\n"))
    }
}
