package dev.slopguard.core.mutation

/**
 * What happened to a mutant.
 *
 * - `killed` — the tests failed with the mutant in place (good).
 * - `survived` — the tests still passed (a gap: no test checks this behaviour).
 * - `timeout` — the test run exceeded the timeout; counted as killed.
 * - `no_coverage` — no test executes the mutated line, so it was not run.
 * - `compile_error` — the mutant did not compile; excluded from the score.
 * - `ignored` — switched off by a `slopguard-ignore-mutant` marker.
 * - `pending` — listed by `--dry-run` (or otherwise not run).
 */
enum class MutantStatus(val wire: String) {
    KILLED("killed"),
    SURVIVED("survived"),
    TIMEOUT("timeout"),
    NO_COVERAGE("no_coverage"),
    COMPILE_ERROR("compile_error"),
    IGNORED("ignored"),
    PENDING("pending"),
}

/**
 * One planned source change, before any test run. [startOffset] / [endOffset]
 * index the file's decoded source string (UTF-16 code units); [line] and
 * [column] are 1-based, with [column] counted in Unicode code points.
 */
data class MutantSite(
    /** Path relative to the source root, forward-slash normalized. */
    val file: String,
    val line: Int,
    val column: Int,
    val operator: MutationOperator,
    /** The exact source text the mutant replaces. */
    val original: String,
    /** The exact text written in its place. */
    val replacement: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    /** Stable id: `<file>:<line>:<column>:<operator>`. */
    val id: String get() = "$file:$line:$column:${operator.wire}"

    /** The source with this mutant applied. */
    fun applyTo(source: String): String =
        source.substring(0, startOffset) + replacement + source.substring(endOffset)

    companion object {
        /** Report order: file (byte-wise UTF-8 order), then line, column and operator id. */
        val ORDER: Comparator<MutantSite> = Comparator<MutantSite> { a, b -> compareBytewise(a.file, b.file) }
            .thenBy { it.line }
            .thenBy { it.column }
            .thenBy { it.operator.wire }

        /** Byte-wise comparison of the UTF-8 encodings of [a] and [b]. */
        fun compareBytewise(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val diff = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return x.size - y.size
        }
    }
}

/** A mutant plus everything known about it before any test run. */
data class PlannedMutant(
    val site: MutantSite,
    /** Qualified name of the innermost enclosing method, or null for code outside any method. */
    val method: String?,
    /** Switched off by a `slopguard-ignore-mutant` marker. */
    val ignored: Boolean,
)

/** A mutant in the final report. */
data class MutantResult(
    val id: String,
    val file: String,
    val line: Int,
    val column: Int,
    val operator: MutationOperator,
    val original: String,
    val replacement: String,
    val method: String?,
    val status: MutantStatus,
) {
    companion object {
        fun of(planned: PlannedMutant, status: MutantStatus): MutantResult {
            val site = planned.site
            return MutantResult(
                id = site.id,
                file = site.file,
                line = site.line,
                column = site.column,
                operator = site.operator,
                original = site.original,
                replacement = site.replacement,
                method = planned.method,
                status = status,
            )
        }
    }
}

data class MutationSummary(
    /** Source files scanned, including files that yielded no mutants. */
    val fileCount: Int,
    val mutantCount: Int,
    val killed: Int,
    val survived: Int,
    val timedOut: Int,
    val noCoverage: Int,
    val compileErrors: Int,
    val ignored: Int,
    val pending: Int,
    /**
     * `(killed + timedOut) / (killed + timedOut + survived + noCoverage) × 100`,
     * unrounded, or null when no mutant counts towards the score.
     */
    val mutationScore: Double?,
) {
    companion object {
        fun of(mutants: List<MutantResult>, fileCount: Int): MutationSummary {
            fun count(status: MutantStatus) = mutants.count { it.status == status }
            val killed = count(MutantStatus.KILLED)
            val timedOut = count(MutantStatus.TIMEOUT)
            val survived = count(MutantStatus.SURVIVED)
            val noCoverage = count(MutantStatus.NO_COVERAGE)
            val detected = killed + timedOut
            val scored = detected + survived + noCoverage
            return MutationSummary(
                fileCount = fileCount,
                mutantCount = mutants.size,
                killed = killed,
                survived = survived,
                timedOut = timedOut,
                noCoverage = noCoverage,
                compileErrors = count(MutantStatus.COMPILE_ERROR),
                ignored = count(MutantStatus.IGNORED),
                pending = count(MutantStatus.PENDING),
                mutationScore = if (scored == 0) null else detected.toDouble() / scored.toDouble() * 100.0,
            )
        }
    }
}

/**
 * The `mutate` report (`reportType: "mutation"`), versioned separately from the
 * CRAP report. The shape is shared with every sibling port.
 */
data class MutationReport(
    val schemaVersion: String,
    val tool: String,
    val toolVersion: String,
    val generatedAt: String,
    val sourceRoot: String,
    /** Where the tests ran; null when no test ran (dry run, or nothing to run). */
    val projectRoot: String?,
    /** The test runner driven for mutants (`gradle`); null when no test ran. */
    val runner: String?,
    /** Per-mutant timeout in seconds; null when no test ran. */
    val timeoutSeconds: Double?,
    /** Whether line coverage classified `no_coverage` mutants. */
    val coverageAvailable: Boolean,
    /** The enabled operators, sorted by id. */
    val operators: List<MutationOperator>,
    val notes: List<String>,
    val summary: MutationSummary,
    val mutants: List<MutantResult>,
) {
    val reportType: String get() = REPORT_TYPE

    companion object {
        const val REPORT_TYPE: String = "mutation"
    }
}
