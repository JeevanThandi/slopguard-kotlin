package dev.slopguard.core

import java.io.PrintStream

enum class Verbosity { SILENT, NORMAL, VERBOSE }

/**
 * Phase markers and subprocess chatter go to **stderr**, so piped stdout (the
 * JSON or text report) stays clean — mirroring the sibling ports.
 */
class ProgressReporter(
    private val verbosity: Verbosity,
    private val err: PrintStream = System.err,
) {
    /** A high-level progress marker, e.g. "running gradle test with coverage…". */
    fun phase(message: String) {
        if (verbosity != Verbosity.SILENT) {
            err.println("slopguard: $message")
        }
    }

    /** Raw passthrough of a subprocess line; only shown in verbose mode. */
    fun raw(line: String) {
        if (verbosity == Verbosity.VERBOSE) {
            err.println(line)
        }
    }

    val isVerbose: Boolean get() = verbosity == Verbosity.VERBOSE
}
