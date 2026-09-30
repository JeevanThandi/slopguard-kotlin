package dev.slopguard.core.mutation

/**
 * The comment marker that switches mutants off. Use it for equivalent mutants —
 * changes with no observable effect, which no test can kill:
 *
 * ```
 * if (a > max) { // slopguard-ignore-mutant(boundary): equal values assign the same max
 * ```
 *
 * - `slopguard-ignore-mutant` ignores every mutant on its line.
 * - `slopguard-ignore-mutant(boundary,logical)` ignores only those operators
 *   (unknown ids are dropped).
 * - On a line that holds only a comment (see [isCommentOnly]), the marker
 *   applies to the next line instead.
 *
 * Detection is a plain text search of the source lines; only the first marker
 * on a line counts.
 */
class IgnoreMarkers private constructor(
    /** 1-based line → ignored operators; null means every operator. */
    private val byLine: Map<Int, Set<MutationOperator>?>,
) {
    fun isIgnored(line: Int, operator: MutationOperator): Boolean {
        if (!byLine.containsKey(line)) return false
        val scope = byLine[line] ?: return true
        return operator in scope
    }

    companion object {
        const val MARKER: String = "slopguard-ignore-mutant"

        /** Find every marker in [source] (lines split on `\n`, like the analyzer's line numbers). */
        fun parse(source: String): IgnoreMarkers {
            val byLine = HashMap<Int, Set<MutationOperator>?>()
            source.split('\n').forEachIndexed { index, text ->
                val at = text.indexOf(MARKER)
                if (at < 0) return@forEachIndexed
                val scope = scopeOf(text.substring(at + MARKER.length))
                val line = if (isCommentOnly(text)) index + 2 else index + 1
                byLine[line] = merge(byLine, line, scope)
            }
            return IgnoreMarkers(byLine)
        }

        /**
         * A line is comment-only when, trimmed, it starts with two slashes or
         * slash-star, or with a star followed by whitespace, a slash or the end of
         * the line (a KDoc continuation line). A spread argument such as
         * `*args` is code.
         */
        fun isCommentOnly(line: String): Boolean {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) return true
            if (!trimmed.startsWith("*")) return false
            val next = trimmed.getOrNull(1)
            return next == null || next == '/' || next.isWhitespace()
        }

        private fun merge(
            byLine: Map<Int, Set<MutationOperator>?>,
            line: Int,
            scope: Set<MutationOperator>?,
        ): Set<MutationOperator>? {
            if (!byLine.containsKey(line)) return scope
            val existing = byLine[line] ?: return null
            return if (scope == null) null else existing + scope
        }

        /** `(a,b)` right after the marker narrows it to those operators; null means all. */
        private fun scopeOf(rest: String): Set<MutationOperator>? {
            if (!rest.startsWith("(")) return null
            val close = rest.indexOf(')')
            val body = if (close < 0) rest.substring(1) else rest.substring(1, close)
            return body.split(',').mapNotNull { MutationOperator.fromWire(it.trim()) }.toSet()
        }
    }
}
