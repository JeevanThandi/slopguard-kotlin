package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import java.io.File

/**
 * Child-JVM entry point for [SignalRestoreTest]: runs `mutate` against a fake
 * project whose mutant run hangs, so the test can interrupt it with a signal.
 */
object SignalFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = args[0]
        try {
            MutationPipeline(File(args[1])).run(
                MutationArgs(
                    sourcePath = "$root/src/main/kotlin",
                    projectDir = root,
                    coverage = false,
                    reporter = ProgressReporter(Verbosity.NORMAL),
                ),
            )
            println("finished without a signal")
        } catch (e: MutationInterruptedException) {
            // Like the CLI: the shutdown hook reports the interruption, and the JVM exits with 128 + signal.
        }
    }
}
