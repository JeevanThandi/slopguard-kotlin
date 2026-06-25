package dev.slopguard.coverage

import dev.slopguard.core.errors.SlopguardError

/**
 * The JVM coverage tool slopguard-kotlin drives in AUTO mode. Both emit
 * JaCoCo-format XML (Kover does so deliberately, for ecosystem compatibility),
 * so a single [JacocoReport] parser handles either — only the Gradle report task
 * and the report directory differ.
 */
enum class CoverageTool(
    val wire: String,
    val defaultReportTask: String,
    val reportDirMarker: String,
) {
    // The default. Kover's koverXmlReport produces build/reports/kover/report.xml
    // with XML enabled out of the box (no `xml.required` opt-in needed).
    KOVER("kover", "koverXmlReport", "/reports/kover/"),

    JACOCO("jacoco", "jacocoTestReport", "/reports/jacoco/"),
    ;

    companion object {
        fun fromWire(value: String): CoverageTool = entries.firstOrNull { it.wire == value.lowercase() }
            ?: throw SlopguardError.invalidArgument(
                "--coverage-tool",
                "expected one of ${entries.joinToString("|") { it.wire }}, got $value",
            )
    }
}
