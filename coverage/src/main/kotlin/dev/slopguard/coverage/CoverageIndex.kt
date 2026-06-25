package dev.slopguard.coverage

import dev.slopguard.core.aggregation.CoverageProvider

/**
 * Joins JaCoCo source-file coverage onto analyzed methods. JaCoCo keys files by
 * package path (e.g. `com/example/Foo.kt`) while the analyzer reports paths
 * relative to the scan root (e.g. `src/main/kotlin/com/example/Foo.kt`), so we
 * match by longest path suffix, falling back to a unique basename — mirroring
 * the CI-vs-local path reconciliation in the sibling ports.
 */
class CoverageIndex(sources: List<SourceFileCoverage>) : CoverageProvider {

    private val byKey: Map<String, SourceFileCoverage> = sources.associateBy { it.key }
    private val byBasename: Map<String, List<SourceFileCoverage>> = sources.groupBy { it.name }

    /** True if any per-file coverage was present at all. */
    val hasData: Boolean = sources.isNotEmpty()

    override fun methodCoverage(file: String, startLine: Int, endLine: Int): Double? {
        val source = resolve(file) ?: return null
        var executable = 0
        var covered = 0
        for (lineNr in startLine..endLine) {
            val line = source.lines[lineNr] ?: continue
            if (!line.isExecutable) continue
            executable++
            if (line.isCovered) covered++
        }
        if (executable == 0) return null
        return covered.toDouble() / executable.toDouble() * 100.0
    }

    private fun resolve(file: String): SourceFileCoverage? {
        val normalized = file.replace('\\', '/')

        // Longest-suffix match against the package-qualified key.
        var best: SourceFileCoverage? = null
        var bestLen = -1
        for ((key, source) in byKey) {
            if (suffixMatches(normalized, key) && key.length > bestLen) {
                best = source
                bestLen = key.length
            }
        }
        if (best != null) return best

        // Fallback: unique basename.
        val base = normalized.substringAfterLast('/')
        val candidates = byBasename[base]
        return if (candidates != null && candidates.size == 1) candidates[0] else null
    }

    private fun suffixMatches(path: String, key: String): Boolean =
        path == key || path.endsWith("/$key")
}
