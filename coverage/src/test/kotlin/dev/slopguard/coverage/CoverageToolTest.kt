package dev.slopguard.coverage

import dev.slopguard.core.errors.SlopguardError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoverageToolTest {
    @Test
    fun mapsWireNames() {
        assertEquals(CoverageTool.JACOCO, CoverageTool.fromWire("jacoco"))
        assertEquals(CoverageTool.KOVER, CoverageTool.fromWire("kover"))
        assertEquals(CoverageTool.KOVER, CoverageTool.fromWire("KOVER"))
    }

    @Test
    fun defaultReportTasksMatchEcosystem() {
        assertEquals("jacocoTestReport", CoverageTool.JACOCO.defaultReportTask)
        assertEquals("koverXmlReport", CoverageTool.KOVER.defaultReportTask)
    }

    @Test
    fun unknownToolIsInvalidArgument() {
        val e = assertFailsWith<SlopguardError> { CoverageTool.fromWire("cobertura") }
        assertEquals("invalid_argument", e.code.wire)
    }
}
