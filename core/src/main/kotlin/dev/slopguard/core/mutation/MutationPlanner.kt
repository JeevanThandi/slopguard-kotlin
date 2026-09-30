package dev.slopguard.core.mutation

import dev.slopguard.core.MethodMetric
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.analysis.ComplexityVisitor
import dev.slopguard.core.analysis.DirectoryAnalyzer
import dev.slopguard.core.analysis.KotlinParser
import dev.slopguard.core.errors.SlopguardError
import java.io.File

/** One source file as planned for `mutate`: its original bytes, decoded text and mutants. */
class PlannedFile(
    val file: File,
    /** Path relative to the source root, forward-slash normalized (the report's `file`). */
    val relativePath: String,
    /** The file's bytes when it was planned. */
    val bytes: ByteArray,
    /** [bytes] decoded as UTF-8; mutant offsets index this string. */
    val source: String,
    /** Mutants in report order. */
    val mutants: List<PlannedMutant>,
)

/** Every planned file under a scan root, sorted by relative path (byte-wise). */
class MutationPlan(val sourceRoot: File, val files: List<PlannedFile>) {
    val mutants: List<PlannedMutant> get() = files.flatMap { it.mutants }
}

/**
 * Plans the mutants for source files: generates them, keeps the requested
 * operators, names each mutant's enclosing method (via the complexity analyzer,
 * so names match the CRAP report) and applies ignore markers.
 */
class MutationPlanner(private val parser: KotlinParser) {

    /** Mutants for [source], in report order (line, column, operator id). */
    fun plan(source: String, reportedPath: String, operators: Collection<MutationOperator>): List<PlannedMutant> {
        val ktFile = try {
            parser.parse(reportedPath, source)
        } catch (e: Exception) {
            throw SlopguardError.parseFailed(reportedPath, e.message ?: e.toString())
        }
        // Mutant offsets are mapped back from the parsed text, so it must differ only in line separators.
        if (ktFile.text != KotlinParser.normalizeLineSeparators(source)) {
            throw SlopguardError.parseFailed(reportedPath, "the parser changed the source text")
        }
        val methods = ComplexityVisitor(reportedPath, ktFile).analyze().first
        val ignores = IgnoreMarkers.parse(ktFile.text)
        return MutantGenerator().generate(ktFile, reportedPath, source)
            .filter { it.operator in operators }
            .sortedWith(MutantSite.ORDER)
            .distinctBy { it.id }
            .map { site ->
                PlannedMutant(
                    site = site,
                    method = enclosingMethod(methods, site.line),
                    ignored = ignores.isIgnored(site.line, site.operator),
                )
            }
    }

    companion object {
        /**
         * Walk [path] exactly as `analyze` does (same globs and default excludes),
         * read every selected file and plan its mutants.
         */
        fun planPath(path: String, options: AnalysisOptions, operators: Collection<MutationOperator>): MutationPlan {
            val listing = DirectoryAnalyzer.listSources(path, options)
            KotlinParser().use { parser ->
                val planner = MutationPlanner(parser)
                val files = listing.files.map { ref ->
                    val bytes = DirectoryAnalyzer.readBytesOrThrow(ref.file)
                    val source = String(bytes, Charsets.UTF_8)
                    PlannedFile(ref.file, ref.relativePath, bytes, source, planner.plan(source, ref.relativePath, operators))
                }
                val sorted = files.sortedWith { a, b -> MutantSite.compareBytewise(a.relativePath, b.relativePath) }
                return MutationPlan(listing.root, sorted)
            }
        }

        /**
         * Qualified name of the innermost method whose line range contains [line]:
         * the smallest span wins, and on a tie the one that starts later. Null for
         * code outside every method.
         */
        fun enclosingMethod(methods: List<MethodMetric>, line: Int): String? {
            var best: MethodMetric? = null
            for (method in methods) {
                if (line < method.startLine || line > method.endLine) continue
                if (best == null || isInnermost(method, best)) best = method
            }
            return best?.qualifiedName
        }

        private fun isInnermost(candidate: MethodMetric, best: MethodMetric): Boolean {
            val candidateSpan = candidate.endLine - candidate.startLine
            val bestSpan = best.endLine - best.startLine
            return candidateSpan < bestSpan || (candidateSpan == bestSpan && candidate.startLine > best.startLine)
        }
    }
}
