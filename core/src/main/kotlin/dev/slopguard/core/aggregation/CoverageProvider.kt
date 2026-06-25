package dev.slopguard.core.aggregation

/**
 * Supplies line-coverage for a method. Implementations live in the coverage
 * module (JaCoCo). Keeping it abstract lets `core` stay free of any build-system
 * or subprocess concerns.
 */
interface CoverageProvider {
    /**
     * @return coverage percentage in [0, 100] for the method spanning
     *   [startLine]..[endLine] (1-based, inclusive) of [file] (relative to the
     *   source root), or `null` if no coverage data is known for it.
     */
    fun methodCoverage(file: String, startLine: Int, endLine: Int): Double?
}

/** A provider that knows nothing — every method reads 0%. Used for --no-coverage. */
object NoCoverage : CoverageProvider {
    override fun methodCoverage(file: String, startLine: Int, endLine: Int): Double? = null
}
