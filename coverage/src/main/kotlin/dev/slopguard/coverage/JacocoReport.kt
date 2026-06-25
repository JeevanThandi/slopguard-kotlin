package dev.slopguard.coverage

import dev.slopguard.core.errors.SlopguardError
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Per-line coverage: a line is executable when [missed] + [covered] > 0. */
data class LineCoverage(val missed: Int, val covered: Int) {
    val isExecutable: Boolean get() = missed + covered > 0
    val isCovered: Boolean get() = covered > 0
}

/** One JaCoCo `<sourcefile>` keyed by its package path and filename. */
data class SourceFileCoverage(
    val packagePath: String,
    val name: String,
    val lines: Map<Int, LineCoverage>,
) {
    /** e.g. "com/example/Foo.kt" — the suffix we match against real file paths. */
    val key: String get() = if (packagePath.isEmpty()) name else "$packagePath/$name"
}

/**
 * Parses a JaCoCo XML report (the universal JVM coverage interchange format) into
 * per-sourcefile line coverage. Pure stdlib DOM; external DTD loading is disabled.
 */
object JacocoReport {
    fun parse(file: File): List<SourceFileCoverage> {
        if (!file.exists()) throw SlopguardError.coverageDataMissing("No coverage report at ${file.absolutePath}")
        return try {
            parseDocument(file)
        } catch (e: SlopguardError) {
            throw e
        } catch (e: Exception) {
            throw SlopguardError.coverageDecodeFailed(e.message ?: e.toString())
        }
    }

    private fun parseDocument(file: File): List<SourceFileCoverage> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/validation", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val result = ArrayList<SourceFileCoverage>()

        for (pkg in doc.getElementsByTagName("package").asElements()) {
            val packagePath = pkg.getAttribute("name")
            for (sourcefile in pkg.childElements("sourcefile")) {
                val name = sourcefile.getAttribute("name")
                val lines = HashMap<Int, LineCoverage>()
                for (line in sourcefile.childElements("line")) {
                    val nr = line.getAttribute("nr").toIntOrNull() ?: continue
                    val mi = line.getAttribute("mi").toIntOrNull() ?: 0
                    val ci = line.getAttribute("ci").toIntOrNull() ?: 0
                    lines[nr] = LineCoverage(mi, ci)
                }
                result.add(SourceFileCoverage(packagePath, name, lines))
            }
        }
        return result
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.childElements(tag: String): List<Element> {
        val out = ArrayList<Element>()
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element && child.tagName == tag) out.add(child)
            child = child.nextSibling
        }
        return out
    }
}
