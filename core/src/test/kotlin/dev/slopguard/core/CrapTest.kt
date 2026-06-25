package dev.slopguard.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class CrapTest {
    @Test
    fun untestedCodeIsPenalisedQuadratically() {
        // cov = 0 -> comp² + comp
        assertEquals(110.0, Crap.score(10.0, 0.0), 1e-9)
    }

    @Test
    fun fullyCoveredCollapsesToComplexity() {
        assertEquals(10.0, Crap.score(10.0, 100.0), 1e-9)
    }

    @Test
    fun partialCoverageUsesCubedFactor() {
        // comp=10, cov=50 -> 100 * 0.5³ + 10 = 12.5 + 10
        assertEquals(22.5, Crap.score(10.0, 50.0), 1e-9)
    }

    @Test
    fun coverageIsClamped() {
        assertEquals(Crap.score(5.0, 100.0), Crap.score(5.0, 150.0), 1e-9)
        assertEquals(Crap.score(5.0, 0.0), Crap.score(5.0, -10.0), 1e-9)
    }

    @Test
    fun weightedComplexityIsGeometricMean() {
        assertEquals(sqrt(12.0), Crap.weightedComplexity(3, 4), 1e-9)
        assertEquals(0.0, Crap.weightedComplexity(5, 0), 1e-9)
    }

    @Test
    fun defaultThresholdMatchesSiblings() {
        assertEquals(30.0, Crap.DEFAULT_THRESHOLD, 1e-9)
    }
}
