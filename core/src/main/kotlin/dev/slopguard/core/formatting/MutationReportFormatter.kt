package dev.slopguard.core.formatting

import dev.slopguard.core.mutation.MutantResult
import dev.slopguard.core.mutation.MutantStatus
import dev.slopguard.core.mutation.MutationReport
import dev.slopguard.core.mutation.MutationSummary
import java.util.Locale

/**
 * Renders a [MutationReport] as sorted-key JSON (for agents and CI) or as a
 * text block (for people). The layout is shared with every slopguard port.
 */
object MutationReportFormatter {

    /** Longest `original` / `replacement` shown in the text listing, in code points. */
    private const val SNIPPET_LIMIT = 40

    private const val NOT_RUN = "(not run)"

    /** Sections that list mutants, in display order. */
    private val SECTIONS: List<Pair<MutantStatus, (Int) -> String>> = listOf(
        MutantStatus.SURVIVED to { n -> "Survived ($n) — tests still pass with these changes" },
        MutantStatus.NO_COVERAGE to { n -> "No coverage ($n) — no test runs these lines" },
        MutantStatus.TIMEOUT to { n -> "Timed out ($n) — counted as killed" },
        MutantStatus.PENDING to { n -> "Mutants ($n, not run)" },
    )

    /** Whitespace as JavaScript's `\s` defines it, so snippets match the sibling ports. */
    private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    fun jsonReport(report: MutationReport): String = Json.encode(reportMap(report))

    fun prettyReport(report: MutationReport): String {
        val sb = StringBuilder()
        sb.append(header(report)).append('\n')
        if (report.notes.isNotEmpty()) {
            sb.append("Notes\n")
            report.notes.forEach { sb.append("  • ").append(it).append('\n') }
            sb.append('\n')
        }
        sb.append(summary(report.summary))
        for ((status, title) in SECTIONS) {
            val mutants = report.mutants.filter { it.status == status }
            if (mutants.isEmpty()) continue
            sb.append('\n').append(title(mutants.size)).append('\n')
            mutants.forEach { sb.append("  ").append(listingLine(it)).append('\n') }
        }
        return sb.toString().trimEnd('\n')
    }

    /** Collapse whitespace runs to one space; past 40 code points keep 39 and append `…`. */
    fun snippet(text: String): String {
        val flat = text.replace(WHITESPACE, " ")
        val codePoints = flat.codePointCount(0, flat.length)
        if (codePoints <= SNIPPET_LIMIT) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, SNIPPET_LIMIT - 1)) + "…"
    }

    /** A number as JavaScript prints it for everyday values: `16`, `2.5`. */
    fun number(value: Double): String =
        if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun header(report: MutationReport): String {
        val timeout = report.timeoutSeconds?.let { "${number(it)}s per mutant" } ?: NOT_RUN
        return "${report.tool} ${report.toolVersion} — mutation report (schema ${report.schemaVersion})\n" +
            "source:    ${report.sourceRoot}\n" +
            "project:   ${report.projectRoot ?: NOT_RUN}\n" +
            "runner:    ${report.runner ?: NOT_RUN}\n" +
            "timeout:   $timeout\n"
    }

    private fun summary(s: MutationSummary): String {
        val rows = buildList<Pair<String, Any>> {
            add("files:" to s.fileCount)
            add("mutants:" to s.mutantCount)
            add("killed:" to s.killed)
            add("timed out:" to s.timedOut)
            add("survived:" to s.survived)
            add("no coverage:" to s.noCoverage)
            add("compile errors:" to s.compileErrors)
            add("ignored:" to s.ignored)
            if (s.pending > 0) add("pending:" to s.pending)
            add("score:" to (s.mutationScore?.let { String.format(Locale.ROOT, "%.2f%%", it) } ?: "n/a"))
        }
        return "Summary\n" + rows.joinToString("") { (label, value) -> "  ${label.padEnd(16)}$value\n" }
    }

    private fun listingLine(m: MutantResult): String {
        val change = "`${snippet(m.original)}` → `${snippet(m.replacement)}`"
        val method = m.method?.let { "  $it" } ?: ""
        return "${m.file}:${m.line}:${m.column}  ${m.operator.wire}  $change$method"
    }

    // ---- JSON shaping ------------------------------------------------------

    private fun reportMap(r: MutationReport): Map<String, Any?> = mapOf(
        "schemaVersion" to r.schemaVersion,
        "reportType" to r.reportType,
        "tool" to r.tool,
        "toolVersion" to r.toolVersion,
        "generatedAt" to r.generatedAt,
        "sourceRoot" to r.sourceRoot,
        "projectRoot" to r.projectRoot,
        "runner" to r.runner,
        "timeoutSeconds" to r.timeoutSeconds,
        "coverageAvailable" to r.coverageAvailable,
        "operators" to r.operators.map { it.wire },
        "notes" to r.notes,
        "summary" to summaryMap(r.summary),
        "mutants" to r.mutants.map { mutantMap(it) },
    )

    private fun summaryMap(s: MutationSummary): Map<String, Any?> = mapOf(
        "fileCount" to s.fileCount,
        "mutantCount" to s.mutantCount,
        "killed" to s.killed,
        "survived" to s.survived,
        "timedOut" to s.timedOut,
        "noCoverage" to s.noCoverage,
        "compileErrors" to s.compileErrors,
        "ignored" to s.ignored,
        "pending" to s.pending,
        "mutationScore" to s.mutationScore,
    )

    private fun mutantMap(m: MutantResult): Map<String, Any?> = mapOf(
        "id" to m.id,
        "file" to m.file,
        "line" to m.line,
        "column" to m.column,
        "operator" to m.operator.wire,
        "original" to m.original,
        "replacement" to m.replacement,
        "method" to m.method,
        "status" to m.status.wire,
    )
}
