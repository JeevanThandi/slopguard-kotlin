package dev.slopguard.core.mutation

import dev.slopguard.core.errors.SlopguardError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationModelsTest {
    private fun site(file: String, line: Int, column: Int, operator: MutationOperator) =
        MutantSite(file, line, column, operator, "+", "-", 0, 1)

    private fun result(status: MutantStatus) =
        MutantResult.of(PlannedMutant(site("A.kt", 1, 1, MutationOperator.ARITHMETIC), null, false), status)

    private fun summary(vararg statuses: MutantStatus) = MutationSummary.of(statuses.map { result(it) }, fileCount = 3)

    // ---- operators ---------------------------------------------------------

    @Test
    fun operatorIdsAreSnakeCaseAndSorted() {
        assertEquals(
            listOf(
                "arithmetic", "boolean_literal", "boundary", "increment", "invert_negative",
                "logical", "negate_conditional", "remove_call", "remove_not",
            ),
            MutationOperator.ALL.map { it.wire },
        )
    }

    @Test
    fun parseListAcceptsCommasRepeatsAndWhitespace() {
        val parsed = MutationOperator.parseList(listOf("remove_not, boundary", "logical", "boundary"))
        assertEquals(listOf("boundary", "logical", "remove_not"), parsed.map { it.wire })
    }

    @Test
    fun parseListWithoutIdsMeansAll() {
        assertEquals(MutationOperator.ALL, MutationOperator.parseList(emptyList()))
        assertEquals(MutationOperator.ALL, MutationOperator.parseList(listOf(" , ")))
    }

    @Test
    fun parseListRejectsUnknownIds() {
        val error = assertFailsWith<SlopguardError> { MutationOperator.parseList(listOf("boundary,nope,worse")) }
        assertEquals("invalid_argument", error.code.wire)
        assertTrue(error.message.contains("unknown operator(s): nope, worse"))
    }

    // ---- ids and order -----------------------------------------------------

    @Test
    fun idJoinsFileLineColumnAndOperator() {
        assertEquals("src/a/B.kt:12:7:remove_not", site("src/a/B.kt", 12, 7, MutationOperator.REMOVE_NOT).id)
    }

    @Test
    fun orderIsFileThenLineColumnAndOperatorId() {
        val sites = listOf(
            site("b.kt", 1, 1, MutationOperator.ARITHMETIC),
            site("a.kt", 2, 1, MutationOperator.ARITHMETIC),
            site("a.kt", 1, 9, MutationOperator.NEGATE_CONDITIONAL),
            site("a.kt", 1, 9, MutationOperator.BOUNDARY),
            site("a.kt", 1, 3, MutationOperator.LOGICAL),
        )
        assertEquals(
            listOf("a.kt:1:3:logical", "a.kt:1:9:boundary", "a.kt:1:9:negate_conditional", "a.kt:2:1:arithmetic", "b.kt:1:1:arithmetic"),
            sites.sortedWith(MutantSite.ORDER).map { it.id },
        )
    }

    @Test
    fun fileOrderIsBytewiseUtf8() {
        // U+FF21 (fullwidth A) sorts before U+1F600 in UTF-8 bytes, but after it in UTF-16 units.
        val emoji = "😀.kt"
        val fullwidth = "Ａ.kt"
        assertTrue(emoji < fullwidth)
        assertTrue(MutantSite.compareBytewise(fullwidth, emoji) < 0)
        assertTrue(MutantSite.compareBytewise("a.kt", "a.kt") == 0)
        assertTrue(MutantSite.compareBytewise("a", "a/b") < 0)
        assertTrue(MutantSite.compareBytewise("Z.kt", "a.kt") < 0)
    }

    // ---- summary and score -------------------------------------------------

    @Test
    fun scoreCountsKilledAndTimeoutsOverScoredStatuses() {
        val s = summary(
            MutantStatus.KILLED, MutantStatus.KILLED, MutantStatus.TIMEOUT,
            MutantStatus.SURVIVED, MutantStatus.NO_COVERAGE,
            MutantStatus.COMPILE_ERROR, MutantStatus.IGNORED,
        )
        assertEquals(3.0 / 5.0 * 100.0, s.mutationScore!!, 1e-12)
        assertEquals(2, s.killed)
        assertEquals(1, s.timedOut)
        assertEquals(1, s.survived)
        assertEquals(1, s.noCoverage)
        assertEquals(1, s.compileErrors)
        assertEquals(1, s.ignored)
        assertEquals(0, s.pending)
        assertEquals(7, s.mutantCount)
        assertEquals(3, s.fileCount)
    }

    @Test
    fun scoreIsUnrounded() {
        val s = summary(MutantStatus.KILLED, MutantStatus.KILLED, MutantStatus.SURVIVED)
        assertEquals(2.0 / 3.0 * 100.0, s.mutationScore!!, 0.0)
        assertEquals("66.66666666666666", s.mutationScore.toString())
    }

    @Test
    fun scoreIsNullWithoutScoredMutants() {
        assertNull(summary().mutationScore)
        assertNull(summary(MutantStatus.PENDING, MutantStatus.IGNORED, MutantStatus.COMPILE_ERROR).mutationScore)
    }

    @Test
    fun summaryCountsAlwaysSumToMutantCount() {
        val all = MutantStatus.entries.flatMap { status -> List(status.ordinal + 1) { status } }
        val s = MutationSummary.of(all.map { result(it) }, fileCount = 1)
        val sum = s.killed + s.survived + s.timedOut + s.noCoverage + s.compileErrors + s.ignored + s.pending
        assertEquals(s.mutantCount, sum)
        assertEquals(all.size, s.mutantCount)
    }

    @Test
    fun resultCopiesTheSiteAndMethod() {
        val planned = PlannedMutant(site("A.kt", 4, 2, MutationOperator.BOUNDARY), "A.f", ignored = false)
        val r = MutantResult.of(planned, MutantStatus.SURVIVED)
        assertEquals("A.kt:4:2:boundary", r.id)
        assertEquals("A.f", r.method)
        assertEquals(MutantStatus.SURVIVED, r.status)
        assertEquals("survived", r.status.wire)
    }
}
