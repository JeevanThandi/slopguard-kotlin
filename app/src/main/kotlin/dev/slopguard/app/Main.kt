package dev.slopguard.app

import dev.slopguard.cli.Cli
import kotlin.system.exitProcess

/** Thin executable shim: delegate to the testable [Cli] and propagate its exit code. */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args, System.out, System.err))
}
