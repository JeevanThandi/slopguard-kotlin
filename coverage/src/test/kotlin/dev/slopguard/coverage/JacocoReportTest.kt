package dev.slopguard.coverage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JacocoReportTest {
    private val tmp = File.createTempFile("jacoco", ".xml")

    @AfterTest
    fun cleanup() = tmp.delete().let {}

    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <report name="todolist">
          <package name="com/example/todo">
            <sourcefile name="TodoStore.kt">
              <line nr="8" mi="0" ci="4"/>
              <line nr="9" mi="0" ci="2"/>
              <line nr="17" mi="3" ci="0"/>
              <line nr="18" mi="0" ci="0"/>
            </sourcefile>
          </package>
        </report>
    """.trimIndent()

    @Test
    fun parsesPerLineCoverage() {
        tmp.writeText(sampleXml)
        val sources = JacocoReport.parse(tmp)
        assertEquals(1, sources.size)
        val sf = sources.first()
        assertEquals("com/example/todo", sf.packagePath)
        assertEquals("TodoStore.kt", sf.name)
        assertEquals("com/example/todo/TodoStore.kt", sf.key)
        assertTrue(sf.lines.getValue(8).isCovered)
        assertTrue(!sf.lines.getValue(17).isCovered)
        assertTrue(!sf.lines.getValue(18).isExecutable) // mi=0 ci=0 -> not executable
    }

    @Test
    fun indexJoinsByLongestSuffix() {
        tmp.writeText(sampleXml)
        val index = CoverageIndex(JacocoReport.parse(tmp))
        assertTrue(index.hasData)
        // method spanning lines 8..9 are both covered -> 100%
        assertEquals(100.0, index.methodCoverage("src/main/kotlin/com/example/todo/TodoStore.kt", 8, 9)!!, 1e-9)
        // method spanning 8..17: 2 executable covered, 1 executable uncovered -> 2/3
        assertEquals(200.0 / 3.0, index.methodCoverage("com/example/todo/TodoStore.kt", 8, 17)!!, 1e-9)
        // no executable lines in range -> null (unknown)
        assertNull(index.methodCoverage("com/example/todo/TodoStore.kt", 100, 110))
        // unknown file -> null
        assertNull(index.methodCoverage("other/Unknown.kt", 1, 5))
    }
}
