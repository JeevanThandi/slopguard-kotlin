package dev.slopguard.core.formatting

import dev.slopguard.core.CrapReport
import dev.slopguard.core.MethodCrap
import dev.slopguard.core.ReportSummary
import dev.slopguard.core.TypeCrap
import dev.slopguard.core.errors.SlopguardErrorEnvelope
import java.util.Locale

/** Renders a [CrapReport] as schema-2 JSON or human-readable text. */
object CrapReportFormatter {

    private const val TOP_N = 10

    fun jsonReport(report: CrapReport): String = Json.encode(reportMap(report))

    fun errorJson(envelope: SlopguardErrorEnvelope): String =
        Json.encode(mapOf("error" to mapOf("code" to envelope.code, "message" to envelope.message)))

    fun errorText(envelope: SlopguardErrorEnvelope): String =
        "slopguard-kotlin: [${envelope.code}] ${envelope.message}"

    fun prettyReport(report: CrapReport): String {
        val s = report.summary
        val sb = StringBuilder()
        sb.appendLine("${report.tool} ${report.toolVersion}  (schema ${report.schemaVersion})")
        sb.appendLine("source: ${report.sourceRoot}")
        sb.appendLine(
            "threshold: ${num(report.threshold)}   coverage: " +
                if (report.coverageAvailable) "available" else "unavailable",
        )
        sb.appendLine()
        sb.appendLine(
            "files: ${s.fileCount}  types: ${s.typeCount}  methods: ${s.methodCount}  " +
                "crappy methods: ${s.crappyMethodCount}  crappy types: ${s.crappyTypeCount}",
        )
        sb.appendLine(
            "avg wCRAP: ${num(s.averageCrap)}  max wCRAP: ${num(s.maxCrap)}  " +
                "weighted coverage: ${coverageText(s)}",
        )

        if (report.methods.isEmpty()) {
            sb.appendLine()
            sb.appendLine("No analyzable methods found.")
        } else {
            sb.appendLine()
            sb.appendLine("Top methods by wCRAP:")
            sb.appendLine(
                String.format(
                    Locale.ROOT,
                    "  %-9s %5s %5s %6s  %s",
                    "CRAP",
                    "COG",
                    "CYC",
                    "COV",
                    "METHOD (file:line)",
                ),
            )
            report.methods.take(TOP_N).forEach { m -> sb.appendLine(methodLine(m)) }
        }

        for (note in report.notes) {
            sb.appendLine()
            sb.appendLine("note: $note")
        }
        return sb.toString().trimEnd('\n')
    }

    private fun methodLine(m: MethodCrap): String {
        val flag = if (m.isCrappy) "!" else " "
        return String.format(
            Locale.ROOT,
            "%s %-8s %5d %5d %5.0f%%  %s  (%s:%d)",
            flag,
            num(m.crap),
            m.cognitiveComplexity,
            m.complexity,
            m.coverage,
            m.qualifiedName,
            m.file,
            m.line,
        )
    }

    private fun coverageText(s: ReportSummary): String =
        s.weightedCoverage?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "n/a"

    private fun num(d: Double): String = String.format(Locale.ROOT, "%.2f", d)

    // ---- JSON shaping ------------------------------------------------------

    private fun reportMap(r: CrapReport): Map<String, Any?> = mapOf(
        "schemaVersion" to r.schemaVersion,
        "tool" to r.tool,
        "toolVersion" to r.toolVersion,
        "generatedAt" to r.generatedAt,
        "sourceRoot" to r.sourceRoot,
        "coverageDataPath" to r.coverageDataPath,
        "threshold" to r.threshold,
        "coverageAvailable" to r.coverageAvailable,
        "notes" to r.notes,
        "summary" to summaryMap(r.summary),
        "methods" to r.methods.map { methodMap(it) },
        "types" to r.types.map { typeMap(it) },
    )

    private fun summaryMap(s: ReportSummary): Map<String, Any?> = mapOf(
        "fileCount" to s.fileCount,
        "typeCount" to s.typeCount,
        "methodCount" to s.methodCount,
        "crappyMethodCount" to s.crappyMethodCount,
        "crappyTypeCount" to s.crappyTypeCount,
        "averageCrap" to s.averageCrap,
        "maxCrap" to s.maxCrap,
        "averageComplexity" to s.averageComplexity,
        "averageCognitiveComplexity" to s.averageCognitiveComplexity,
        "averageWeightedComplexity" to s.averageWeightedComplexity,
        "weightedCoverage" to s.weightedCoverage,
    )

    private fun methodMap(m: MethodCrap): Map<String, Any?> = mapOf(
        "id" to m.id,
        "file" to m.file,
        "line" to m.line,
        "endLine" to m.endLine,
        "typeName" to m.typeName,
        "name" to m.name,
        "qualifiedName" to m.qualifiedName,
        "kind" to m.kind.wire,
        "complexity" to m.complexity,
        "cognitiveComplexity" to m.cognitiveComplexity,
        "weightedComplexity" to m.weightedComplexity,
        "coverage" to m.coverage,
        "crap" to m.crap,
        "isCrappy" to m.isCrappy,
    )

    private fun typeMap(t: TypeCrap): Map<String, Any?> = mapOf(
        "id" to t.id,
        "file" to t.file,
        "line" to t.line,
        "kind" to t.kind.wire,
        "name" to t.name,
        "methodCount" to t.methodCount,
        "totalComplexity" to t.totalComplexity,
        "maxComplexity" to t.maxComplexity,
        "totalCognitiveComplexity" to t.totalCognitiveComplexity,
        "maxCognitiveComplexity" to t.maxCognitiveComplexity,
        "weightedTotalComplexity" to t.weightedTotalComplexity,
        "weightedCoverage" to t.weightedCoverage,
        "sumCrap" to t.sumCrap,
        "maxCrap" to t.maxCrap,
        "aggregatedCrap" to t.aggregatedCrap,
        "isCrappy" to t.isCrappy,
    )
}
