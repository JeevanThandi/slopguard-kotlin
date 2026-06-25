package dev.slopguard.core

import kotlin.math.sqrt

/**
 * The wCRAP (weighted Change Risk Anti-Patterns) formula — the shared contract
 * across every slopguard port.
 *
 * ```
 * CRAP(m) = comp(m)² × (1 − cov(m)/100)³ + comp(m)
 * ```
 *
 * `comp` is the weighted complexity fed in by the caller. Since schema 2 that is
 * `sqrt(cyclomatic × cognitive)`, blending raw branching (cyclomatic) with
 * human-perceived difficulty (cognitive). The formula itself is metric-agnostic.
 *
 * - Fully covered code (cov = 100) collapses to `comp` — complexity alone.
 * - Untested code (cov = 0) is penalised quadratically: `comp² + comp`.
 * - The cubed coverage factor sharply rewards even partial test coverage.
 */
object Crap {
    const val DEFAULT_THRESHOLD: Double = 30.0

    fun score(complexity: Double, coveragePercent: Double): Double {
        val comp = maxOf(0.0, complexity)
        val cov = coveragePercent.coerceIn(0.0, 100.0)
        val covFactor = 1.0 - cov / 100.0
        return comp * comp * (covFactor * covFactor * covFactor) + comp
    }

    /** The schema-2 weighted-complexity blend: the geometric mean of the two metrics. */
    fun weightedComplexity(cyclomatic: Int, cognitive: Int): Double {
        val cyc = maxOf(0, cyclomatic).toDouble()
        val cog = maxOf(0, cognitive).toDouble()
        return sqrt(cyc * cog)
    }
}
