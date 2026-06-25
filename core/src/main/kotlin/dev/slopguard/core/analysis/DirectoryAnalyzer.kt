package dev.slopguard.core.analysis

import dev.slopguard.core.FileReport
import dev.slopguard.core.errors.SlopguardError
import java.io.File

data class AnalysisOptions(
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val useDefaultExcludes: Boolean = true,
)

/**
 * Walks a directory (or analyzes a single file), applies include/exclude globs,
 * and parses every Kotlin source into a [FileReport]. Owns the [KotlinParser]
 * for the scan and closes it when done.
 */
object DirectoryAnalyzer {

    /** Default noise filters for Kotlin / Android projects. Disable with --no-default-excludes. */
    val DEFAULT_EXCLUDE_GLOBS: List<String> = listOf(
        "**/build/**",
        "**/.gradle/**",
        "**/.git/**",
        "**/.idea/**",
        "**/out/**",
        "**/node_modules/**",
        "**/*Test.kt",
        "**/*Tests.kt",
        "**/test/**",
        "**/androidTest/**",
        "**/testFixtures/**",
        "**/generated/**",
        "**/*.generated.kt",
        "**/sample-apps/**",
        "**/sampleapps/**",
    )

    /**
     * @return the per-file reports plus the absolute source root used for
     *   relativization. For a single-file path the root is the file's parent.
     */
    fun analyze(path: String, options: AnalysisOptions): Pair<List<FileReport>, File> {
        val target = File(path)
        if (!target.exists()) throw SlopguardError.fileNotFound(target.absolutePath)

        val excludes = buildList {
            if (options.useDefaultExcludes) addAll(DEFAULT_EXCLUDE_GLOBS)
            addAll(options.excludeGlobs)
        }

        KotlinParser().use { parser ->
            val analyzer = FileAnalyzer(parser)

            if (target.isFile) {
                if (!isKotlinSource(target.name)) {
                    throw SlopguardError.unsupported("Not a Kotlin source file: ${target.absolutePath}")
                }
                val report = analyzer.analyzeSource(readOrThrow(target), target.name)
                return listOf(report) to target.absoluteFile.parentFile
            }

            val root = target.absoluteFile
            val reports = ArrayList<FileReport>()
            root.walkTopDown()
                .filter { it.isFile && isKotlinSource(it.name) }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    val rel = relativePath(root, file)
                    if (shouldInclude(rel, options.includeGlobs, excludes)) {
                        reports.add(analyzer.analyzeSource(readOrThrow(file), rel))
                    }
                }
            return reports to root
        }
    }

    private fun shouldInclude(rel: String, includes: List<String>, excludes: List<String>): Boolean {
        if (Glob.matchesAny(excludes, rel)) return false
        if (includes.isNotEmpty() && !Glob.matchesAny(includes, rel)) return false
        return true
    }

    private fun isKotlinSource(name: String): Boolean = name.endsWith(".kt")

    private fun relativePath(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun readOrThrow(file: File): String = try {
        file.readText()
    } catch (e: Exception) {
        throw SlopguardError.unreadableFile(file.absolutePath, e.message ?: e.toString())
    }
}
