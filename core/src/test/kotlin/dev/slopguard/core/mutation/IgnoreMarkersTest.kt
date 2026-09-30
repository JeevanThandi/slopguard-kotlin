package dev.slopguard.core.mutation

import dev.slopguard.core.mutation.MutationOperator.BOUNDARY
import dev.slopguard.core.mutation.MutationOperator.LOGICAL
import dev.slopguard.core.mutation.MutationOperator.NEGATE_CONDITIONAL
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreMarkersTest {
    @Test
    fun bareMarkerIgnoresEveryOperatorOnItsLine() {
        val markers = IgnoreMarkers.parse("val a = 1\nif (a > b) x() // slopguard-ignore-mutant\nval c = 2")
        assertTrue(markers.isIgnored(2, BOUNDARY))
        assertTrue(markers.isIgnored(2, LOGICAL))
        assertFalse(markers.isIgnored(1, BOUNDARY))
        assertFalse(markers.isIgnored(3, BOUNDARY))
    }

    @Test
    fun listedMarkerIgnoresOnlyThoseOperators() {
        val markers = IgnoreMarkers.parse("if (a > b) x() // slopguard-ignore-mutant( boundary , negate_conditional ): equal is fine")
        assertTrue(markers.isIgnored(1, BOUNDARY))
        assertTrue(markers.isIgnored(1, NEGATE_CONDITIONAL))
        assertFalse(markers.isIgnored(1, LOGICAL))
    }

    @Test
    fun commentOnlyLineAppliesToTheNextLine() {
        val src = """
            fun f() {
                // slopguard-ignore-mutant(boundary)
                if (a > b) x()
                /* slopguard-ignore-mutant */
                if (c && d) y()
                 * slopguard-ignore-mutant(logical)
                if (e || f) z()
            }
        """.trimIndent()
        val markers = IgnoreMarkers.parse(src)
        assertFalse(markers.isIgnored(2, BOUNDARY))
        assertTrue(markers.isIgnored(3, BOUNDARY))
        assertFalse(markers.isIgnored(3, LOGICAL))
        assertTrue(markers.isIgnored(5, LOGICAL))
        assertTrue(markers.isIgnored(7, LOGICAL))
        assertFalse(markers.isIgnored(7, BOUNDARY))
    }

    @Test
    fun aSpreadArgumentLineIsCodeNotAComment() {
        val src = "val xs = listOf(\n    *args, // slopguard-ignore-mutant(boundary)\n    a > b,\n)"
        val markers = IgnoreMarkers.parse(src)
        assertTrue(markers.isIgnored(2, BOUNDARY))
        assertFalse(markers.isIgnored(3, BOUNDARY))
    }

    @Test
    fun commentOnlyLines() {
        listOf("// x", "  /* x", " * x", "*", "*/", "\t*\tx", "/** doc").forEach { assertTrue(IgnoreMarkers.isCommentOnly(it), it) }
        listOf("*args", "  *p = x", "a > b // x", "val x = 1", "").forEach { assertFalse(IgnoreMarkers.isCommentOnly(it), it) }
    }

    @Test
    fun unknownIdsAreDroppedAndEmptyListsIgnoreNothing() {
        val markers = IgnoreMarkers.parse("a > b // slopguard-ignore-mutant(nonsense, boundary)\nc > d // slopguard-ignore-mutant()")
        assertTrue(markers.isIgnored(1, BOUNDARY))
        assertFalse(markers.isIgnored(1, NEGATE_CONDITIONAL))
        assertFalse(markers.isIgnored(2, BOUNDARY))
    }

    @Test
    fun unclosedListRunsToTheEndOfTheLine() {
        val markers = IgnoreMarkers.parse("a > b // slopguard-ignore-mutant(boundary, logical")
        assertTrue(markers.isIgnored(1, BOUNDARY))
        assertTrue(markers.isIgnored(1, LOGICAL))
    }

    @Test
    fun markersForTheSameLineMerge() {
        val src = "// slopguard-ignore-mutant(boundary)\na > b && c // slopguard-ignore-mutant(logical)"
        val markers = IgnoreMarkers.parse(src)
        assertTrue(markers.isIgnored(2, BOUNDARY))
        assertTrue(markers.isIgnored(2, LOGICAL))
        assertFalse(markers.isIgnored(2, NEGATE_CONDITIONAL))

        val widened = IgnoreMarkers.parse("// slopguard-ignore-mutant(boundary)\na > b // slopguard-ignore-mutant")
        assertTrue(widened.isIgnored(2, NEGATE_CONDITIONAL))
        val stillAll = IgnoreMarkers.parse("// slopguard-ignore-mutant\na > b // slopguard-ignore-mutant(boundary)")
        assertTrue(stillAll.isIgnored(2, NEGATE_CONDITIONAL))
    }
}
