package dev.slopguard.core

/** Build-stamped identity, shared across the slopguard sibling ports. */
object Version {
    const val TOOL_NAME: String = "slopguard-kotlin"
    const val VERSION: String = "0.1.0"

    /**
     * JSON schema version. Shared with slopguard-go, slopguard-swift and
     * slopguard-typescript — schema 2 introduced the weighted-complexity blend
     * (`sqrt(cyclomatic × cognitive)`). Do not bump unilaterally.
     */
    const val SCHEMA_VERSION: String = "2"
}
