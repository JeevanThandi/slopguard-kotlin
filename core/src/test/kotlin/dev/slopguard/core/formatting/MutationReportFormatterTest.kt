package dev.slopguard.core.formatting

import dev.slopguard.core.mutation.MutantResult
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.core.mutation.MutationReport
import dev.slopguard.core.mutation.MutationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutationReportFormatterTest {
    private fun mutant(
        line: Int,
        operator: MutationOperator,
        original: String,
        replacement: String,
        status: MutantStatus,
        method: String? = "Store.toggle",
    ) = MutantResult(
        id = "Store.kt:$line:9:${operator.wire}",
        file = "Store.kt",
        line = line,
        column = 9,
        operator = operator,
        original = original,
        replacement = replacement,
        method = method,
        status = status,
    )

    private val mutants = listOf(
        mutant(3, MutationOperator.NEGATE_CONDITIONAL, "==", "!=", MutantStatus.KILLED),
        mutant(4, MutationOperator.BOUNDARY, "<", "<=", MutantStatus.SURVIVED),
        mutant(5, MutationOperator.REMOVE_CALL, "items.add(\n        todo,\n    )", "", MutantStatus.NO_COVERAGE, method = null),
        mutant(6, MutationOperator.INCREMENT, "++", "--", MutantStatus.TIMEOUT),
        mutant(7, MutationOperator.BOOLEAN_LITERAL, "true", "false", MutantStatus.COMPILE_ERROR),
        mutant(8, MutationOperator.LOGICAL, "&&", "||", MutantStatus.IGNORED),
    )

    private fun report(
        mutants: List<MutantResult> = this.mutants,
        notes: List<String> = listOf("1 mutant(s) did not compile and are excluded from the score."),
        ran: Boolean = true,
    ) = MutationReport(
        schemaVersion = "1",
        tool = "slopguard-kotlin",
        toolVersion = "0.2.0",
        generatedAt = "2026-09-30T12:00:00.000Z",
        sourceRoot = "/abs/project/src",
        projectRoot = if (ran) "/abs/project" else null,
        runner = if (ran) "gradle" else null,
        timeoutSeconds = if (ran) 64.0 else null,
        coverageAvailable = ran,
        operators = MutationOperator.ALL,
        notes = notes,
        summary = MutationSummary.of(mutants, fileCount = 3),
        mutants = mutants,
    )

    @Test
    fun prettyReportMatchesTheSharedLayout() {
        val expected = """
            slopguard-kotlin 0.2.0 — mutation report (schema 1)
            source:    /abs/project/src
            project:   /abs/project
            runner:    gradle
            timeout:   64s per mutant

            Notes
              • 1 mutant(s) did not compile and are excluded from the score.

            Summary
              files:          3
              mutants:        6
              killed:         1
              timed out:      1
              survived:       1
              no coverage:    1
              compile errors: 1
              ignored:        1
              score:          50.00%

            Survived (1) — tests still pass with these changes
              Store.kt:4:9  boundary  `<` → `<=`  Store.toggle

            No coverage (1) — no test runs these lines
              Store.kt:5:9  remove_call  `items.add( todo, )` → ``

            Timed out (1) — counted as killed
              Store.kt:6:9  increment  `++` → `--`  Store.toggle
        """.trimIndent()
        assertEquals(expected, MutationReportFormatter.prettyReport(report()))
    }

    @Test
    fun dryRunReadsNotRunAndListsPendingMutants() {
        val pending = listOf(
            mutant(3, MutationOperator.NEGATE_CONDITIONAL, "==", "!=", MutantStatus.PENDING),
            mutant(8, MutationOperator.LOGICAL, "&&", "||", MutantStatus.IGNORED),
        )
        val expected = """
            slopguard-kotlin 0.2.0 — mutation report (schema 1)
            source:    /abs/project/src
            project:   (not run)
            runner:    (not run)
            timeout:   (not run)

            Summary
              files:          3
              mutants:        2
              killed:         0
              timed out:      0
              survived:       0
              no coverage:    0
              compile errors: 0
              ignored:        1
              pending:        1
              score:          n/a

            Mutants (1, not run)
              Store.kt:3:9  negate_conditional  `==` → `!=`  Store.toggle
        """.trimIndent()
        assertEquals(expected, MutationReportFormatter.prettyReport(report(pending, notes = emptyList(), ran = false)))
    }

    @Test
    fun snippetsCollapseWhitespaceAndTruncateByCodePoint() {
        assertEquals("a b c", MutationReportFormatter.snippet("a \n\t b  c"))
        val forty = "x".repeat(40)
        assertEquals(forty, MutationReportFormatter.snippet(forty))
        assertEquals("x".repeat(39) + "…", MutationReportFormatter.snippet("x".repeat(41)))
        // 41 emoji: truncation never splits a surrogate pair.
        val emoji = "😀"
        assertEquals(emoji.repeat(39) + "…", MutationReportFormatter.snippet(emoji.repeat(41)))
        assertEquals(emoji.repeat(40), MutationReportFormatter.snippet(emoji.repeat(40)))
    }

    @Test
    fun numbersPrintLikeTheSiblingPorts() {
        assertEquals("16", MutationReportFormatter.number(16.0))
        assertEquals("2.5", MutationReportFormatter.number(2.5))
        assertEquals("90", MutationReportFormatter.number(90.0))
    }

    @Test
    fun jsonReportHasTheSharedShapeWithSortedKeys() {
        val json = MutationReportFormatter.jsonReport(report())
        val topKeys = Regex("^  \"(\\w+)\":", RegexOption.MULTILINE).findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "coverageAvailable", "generatedAt", "mutants", "notes", "operators", "projectRoot", "reportType",
                "runner", "schemaVersion", "sourceRoot", "summary", "timeoutSeconds", "tool", "toolVersion",
            ),
            topKeys,
        )
        assertTrue(json.contains("\"reportType\": \"mutation\""))
        assertTrue(json.contains("\"schemaVersion\": \"1\""))
        assertTrue(json.contains("\"timeoutSeconds\": 64,"))
        assertTrue(json.contains("\"mutationScore\": 50,"))
        assertTrue(json.contains("\"id\": \"Store.kt:4:9:boundary\""))
        assertTrue(json.contains("\"status\": \"no_coverage\""))
        assertTrue(json.contains("\"method\": null"))
        assertTrue(json.contains("\"original\": \"items.add(\\n        todo,\\n    )\""))
        assertTrue(json.contains("\"replacement\": \"\""))
        val mutantKeys = Regex("^      \"(\\w+)\":", RegexOption.MULTILINE).findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("column", "file", "id", "line", "method", "operator", "original", "replacement", "status"),
            mutantKeys.take(9),
        )
    }

    @Test
    fun jsonReportForADryRunHasNullRunFields() {
        val json = MutationReportFormatter.jsonReport(report(ran = false))
        assertTrue(json.contains("\"projectRoot\": null"))
        assertTrue(json.contains("\"runner\": null"))
        assertTrue(json.contains("\"timeoutSeconds\": null"))
        assertTrue(json.contains("\"coverageAvailable\": false"))
        assertFalse(json.contains("\"runner\": \"gradle\""))
    }

    @Test
    fun fractionalScoresKeepFullPrecisionInJsonAndTwoDecimalsInText() {
        val scored = listOf(
            mutant(3, MutationOperator.NEGATE_CONDITIONAL, "==", "!=", MutantStatus.KILLED),
            mutant(4, MutationOperator.NEGATE_CONDITIONAL, "==", "!=", MutantStatus.KILLED),
            mutant(5, MutationOperator.NEGATE_CONDITIONAL, "==", "!=", MutantStatus.SURVIVED),
        )
        val r = report(scored, notes = emptyList())
        assertTrue(MutationReportFormatter.jsonReport(r).contains("\"mutationScore\": 66.66666666666666"))
        assertTrue(MutationReportFormatter.prettyReport(r).contains("  score:          66.67%"))
    }
}
