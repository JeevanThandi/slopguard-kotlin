package dev.slopguard.core.aggregation

import dev.slopguard.core.FileReport
import dev.slopguard.core.MethodKind
import dev.slopguard.core.MethodMetric
import dev.slopguard.core.TypeDecl
import dev.slopguard.core.TypeKind
import dev.slopguard.core.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrapAggregatorTest {

    private fun method(name: String, type: String?, cyc: Int, cog: Int, line: Int) = MethodMetric(
        name = name,
        qualifiedName = if (type == null) name else "$type.$name",
        typeName = type,
        kind = if (type == null) MethodKind.FUNCTION else MethodKind.METHOD,
        file = "src/Foo.kt",
        startLine = line,
        endLine = line + 5,
        complexity = cyc,
        cognitiveComplexity = cog,
    )

    private fun aggregate(provider: CoverageProvider, coverageAvailable: Boolean): dev.slopguard.core.CrapReport {
        val methods = listOf(
            method("hot", "Foo", cyc = 10, cog = 10, line = 10),
            method("cool", "Foo", cyc = 1, cog = 0, line = 30),
        )
        val types = listOf(
            TypeDecl("Foo", "Foo", TypeKind.CLASS, "src/Foo.kt", 1, 100),
        )
        return CrapAggregator.aggregate(
            AggregationInput(
                fileReports = listOf(FileReport("src/Foo.kt", methods, types)),
                sourceRoot = "/root",
                provider = provider,
                threshold = 30.0,
                coverageAvailable = coverageAvailable,
                coverageDataPath = null,
                notes = emptyList(),
                generatedAt = "2026-06-22T00:00:00.000Z",
            ),
        )
    }

    @Test
    fun noCoverageMakesComplexCodeCrappy() {
        val report = aggregate(NoCoverage, coverageAvailable = false)
        assertEquals(Version.SCHEMA_VERSION, report.schemaVersion)
        assertEquals(Version.TOOL_NAME, report.tool)
        // hot: weighted 10, cov 0 -> 110 crap, crappy
        val hot = report.methods.first { it.name == "hot" }
        assertTrue(hot.isCrappy)
        assertEquals(110.0, hot.crap, 1e-9)
        // cool: weighted 0 -> 0 crap
        val cool = report.methods.first { it.name == "cool" }
        assertFalse(cool.isCrappy)
        assertEquals(0.0, cool.crap, 1e-9)
        // weightedCoverage is null when coverage unavailable
        assertEquals(null, report.summary.weightedCoverage)
    }

    @Test
    fun fullCoverageCollapsesCrap() {
        val provider = object : CoverageProvider {
            override fun methodCoverage(file: String, startLine: Int, endLine: Int) = 100.0
        }
        val report = aggregate(provider, coverageAvailable = true)
        val hot = report.methods.first { it.name == "hot" }
        assertEquals(10.0, hot.crap, 1e-9) // collapses to weighted complexity
        assertFalse(hot.isCrappy)
        assertEquals(100.0, report.summary.weightedCoverage!!, 1e-9)
    }

    @Test
    fun methodsSortedByCrapDescending() {
        val report = aggregate(NoCoverage, coverageAvailable = false)
        assertEquals("hot", report.methods.first().name)
    }

    @Test
    fun typeAggregatesMembers() {
        val report = aggregate(NoCoverage, coverageAvailable = false)
        val foo = report.types.first { it.name == "Foo" }
        assertEquals(2, foo.methodCount)
        assertEquals(11, foo.totalComplexity)
        assertEquals(10, foo.totalCognitiveComplexity)
        assertEquals(10, foo.maxComplexity)
        assertTrue(foo.isCrappy) // maxCrap (110) exceeds threshold
        assertEquals("src/Foo.kt#Foo@1", foo.id)
    }

    @Test
    fun methodIdFormat() {
        val report = aggregate(NoCoverage, coverageAvailable = false)
        val hot = report.methods.first { it.name == "hot" }
        assertEquals("src/Foo.kt#Foo.hot@10", hot.id)
    }

    @Test
    fun schemaNoteIsPrepended() {
        val report = aggregate(NoCoverage, coverageAvailable = false)
        assertTrue(report.notes.first().contains("wCRAP"))
    }
}
