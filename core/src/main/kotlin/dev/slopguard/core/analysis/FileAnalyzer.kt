package dev.slopguard.core.analysis

import dev.slopguard.core.FileReport
import dev.slopguard.core.errors.SlopguardError

/**
 * Parses a single Kotlin source into a [FileReport]. Stateless apart from the
 * shared [parser]; safe to call repeatedly within one scan.
 */
class FileAnalyzer(private val parser: KotlinParser) {

    /** Analyze in-memory source. [reportedPath] is the path recorded on every metric. */
    fun analyzeSource(source: String, reportedPath: String): FileReport {
        val ktFile = try {
            parser.parse(reportedPath, source)
        } catch (e: Exception) {
            throw SlopguardError.parseFailed(reportedPath, e.message ?: e.toString())
        }
        val (methods, types) = ComplexityVisitor(reportedPath, ktFile).analyze()
        return FileReport(reportedPath, methods, types)
    }
}
