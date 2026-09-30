package dev.slopguard.core.analysis

import dev.slopguard.core.FileReport
import dev.slopguard.core.errors.SlopguardError
import java.io.File

data class AnalysisOptions(
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val useDefaultExcludes: Boolean = true,
)

/** One Kotlin source picked by a scan: the absolute [file] and its [relativePath] under the scan root. */
data class SourceRef(val file: File, val relativePath: String)

/** The result of a scan's directory walk: the absolute [root] and the selected [files], in walk order. */
data class SourceListing(val root: File, val files: List<SourceRef>)

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
        val listing = listSources(path, options)
        KotlinParser().use { parser ->
            val analyzer = FileAnalyzer(parser)
            val reports = listing.files.map { ref -> analyzer.analyzeSource(readOrThrow(ref.file), ref.relativePath) }
            return reports to listing.root
        }
    }

    /**
     * The directory walk behind [analyze], shared with `mutate`: validates [path],
     * applies the include / exclude / default-exclude globs, and lists the Kotlin
     * sources to scan. For a single-file path the root is the file's parent.
     */
    fun listSources(path: String, options: AnalysisOptions): SourceListing {
        val target = File(path)
        if (!target.exists()) throw SlopguardError.fileNotFound(target.absolutePath)

        if (target.isFile) {
            if (!isKotlinSource(target.name)) {
                throw SlopguardError.unsupported("Not a Kotlin source file: ${target.absolutePath}")
            }
            val file = target.absoluteFile.normalize()
            return SourceListing(file.parentFile, listOf(SourceRef(file, file.name)))
        }

        val excludes = buildList {
            if (options.useDefaultExcludes) addAll(DEFAULT_EXCLUDE_GLOBS)
            addAll(options.excludeGlobs)
        }
        // normalize() drops `.` / `..` segments, so `--path .` reports a clean root.
        val root = target.absoluteFile.normalize()
        val files = root.walkTopDown()
            .filter { it.isFile && isKotlinSource(it.name) }
            .sortedBy { it.absolutePath }
            .map { file -> SourceRef(file, relativePath(root, file)) }
            .filter { ref -> shouldInclude(ref.relativePath, options.includeGlobs, excludes) }
            .toList()
        return SourceListing(root, files)
    }

    private fun shouldInclude(rel: String, includes: List<String>, excludes: List<String>): Boolean {
        if (Glob.matchesAny(excludes, rel)) return false
        if (includes.isNotEmpty() && !Glob.matchesAny(includes, rel)) return false
        return true
    }

    private fun isKotlinSource(name: String): Boolean = name.endsWith(".kt")

    private fun relativePath(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun readOrThrow(file: File): String = String(readBytesOrThrow(file), Charsets.UTF_8)

    /** Read [file]'s bytes, mapping any I/O failure to `unreadable_file`. */
    fun readBytesOrThrow(file: File): ByteArray = try {
        file.readBytes()
    } catch (e: Exception) {
        throw SlopguardError.unreadableFile(file.absolutePath, e.message ?: e.toString())
    }
}
