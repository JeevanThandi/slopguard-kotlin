package dev.slopguard.cli

import dev.slopguard.core.Version
import dev.slopguard.core.formatting.Json
import java.io.PrintStream

object VersionCommand {
    fun run(stdout: PrintStream): Int {
        stdout.println(
            Json.encode(
                mapOf(
                    "name" to Version.TOOL_NAME,
                    "version" to Version.VERSION,
                    "schemaVersion" to Version.SCHEMA_VERSION,
                ),
            ),
        )
        return 0
    }
}
