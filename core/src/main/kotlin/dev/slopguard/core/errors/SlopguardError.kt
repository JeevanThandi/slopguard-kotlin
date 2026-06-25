package dev.slopguard.core.errors

/**
 * Stable, machine-readable error codes shared across the slopguard ports.
 * Downstream agents key off these without parsing human messages.
 */
enum class ErrorCode(val wire: String) {
    FILE_NOT_FOUND("file_not_found"),
    NOT_A_DIRECTORY("not_a_directory"),
    UNREADABLE_FILE("unreadable_file"),
    PARSE_FAILED("parse_failed"),
    COVERAGE_DATA_MISSING("coverage_data_missing"),
    PROJECT_ROOT_NOT_FOUND("project_root_not_found"),
    TEST_RUN_FAILED("test_run_failed"),
    COVERAGE_DECODE_FAILED("coverage_decode_failed"),
    INVALID_ARGUMENT("invalid_argument"),
    UNSUPPORTED("unsupported"),
    INTERNAL_ERROR("internal_error"),
}

/**
 * The single typed error surface. Carries a stable [code] and a human [message].
 * Mirrors `SlopguardError` in the sibling ports.
 */
class SlopguardError(
    val code: ErrorCode,
    override val message: String,
) : Exception(message) {

    override fun toString(): String = "[${code.wire}] $message"

    companion object {
        fun fileNotFound(path: String) =
            SlopguardError(ErrorCode.FILE_NOT_FOUND, "File not found: $path")

        fun notADirectory(path: String) =
            SlopguardError(ErrorCode.NOT_A_DIRECTORY, "Not a directory: $path")

        fun unreadableFile(path: String, underlying: String) =
            SlopguardError(ErrorCode.UNREADABLE_FILE, "Could not read $path: $underlying")

        fun parseFailed(path: String, underlying: String) =
            SlopguardError(ErrorCode.PARSE_FAILED, "Failed to parse $path: $underlying")

        fun coverageDataMissing(reason: String) =
            SlopguardError(ErrorCode.COVERAGE_DATA_MISSING, reason)

        fun projectRootNotFound(start: String) =
            SlopguardError(
                ErrorCode.PROJECT_ROOT_NOT_FOUND,
                "No Gradle project root (settings.gradle[.kts] or build.gradle[.kts]) found above $start. " +
                    "Pass --project-dir <dir>, --coverage-file <jacoco.xml>, or --no-coverage.",
            )

        fun testRunFailed(exitCode: Int, tail: String) =
            SlopguardError(
                ErrorCode.TEST_RUN_FAILED,
                "The Gradle test run failed (exit $exitCode):\n$tail",
            )

        fun coverageDecodeFailed(underlying: String) =
            SlopguardError(ErrorCode.COVERAGE_DECODE_FAILED, "Could not decode coverage data: $underlying")

        fun invalidArgument(name: String, reason: String) =
            SlopguardError(ErrorCode.INVALID_ARGUMENT, "Invalid value for $name: $reason")

        fun unsupported(reason: String) =
            SlopguardError(ErrorCode.UNSUPPORTED, reason)
    }
}

/** The wire shape emitted under `{"error": …}` in JSON mode. */
data class SlopguardErrorEnvelope(
    val code: String,
    val message: String,
)

/** Coerce any throwable into a stable envelope. Non-slopguard errors become internal_error. */
fun envelopeFor(error: Throwable): SlopguardErrorEnvelope =
    when (error) {
        is SlopguardError -> SlopguardErrorEnvelope(error.code.wire, error.message)
        else -> SlopguardErrorEnvelope(ErrorCode.INTERNAL_ERROR.wire, error.message ?: error.toString())
    }
