package dev.slopguard.core.aggregation

import dev.slopguard.core.Crap
import dev.slopguard.core.CrapReport
import dev.slopguard.core.FileReport
import dev.slopguard.core.MethodCrap
import dev.slopguard.core.MethodMetric
import dev.slopguard.core.ReportSummary
import dev.slopguard.core.TypeCrap
import dev.slopguard.core.TypeDecl
import dev.slopguard.core.Version
import kotlin.math.sqrt

class AggregationInput(
    val fileReports: List<FileReport>,
    val sourceRoot: String,
    val provider: CoverageProvider,
    val threshold: Double,
    val coverageAvailable: Boolean,
    val coverageDataPath: String?,
    val notes: List<String>,
    val generatedAt: String,
)

/**
 * Joins parsed complexity with coverage into the final [CrapReport]: per-method
 * wCRAP, per-type aggregation by lexical nesting, and the summary. The shape and
 * ordering match the sibling ports exactly.
 */
object CrapAggregator {

    private const val SCHEMA_2_NOTE =
        "Score is wCRAP (weighted CRAP) since schema 2: complexity is " +
            "sqrt(cyclomatic × cognitive)."

    fun aggregate(input: AggregationInput): CrapReport {
        val allMethods = input.fileReports.flatMap { it.methods }
        val allTypes = input.fileReports.flatMap { it.types }

        val methodCraps = allMethods.map { m -> toMethodCrap(m, input) }
            .sortedWith(compareByDescending<MethodCrap> { it.crap }.thenBy { it.id })

        // Index method metrics by (file, enclosing-type qualified name) for type rollup.
        val byType: Map<Pair<String, String>, List<MethodMetric>> =
            allMethods.groupBy { it.file to enclosingTypeQualified(it.qualifiedName) }

        val typeCraps = allTypes.map { t -> toTypeCrap(t, byType[t.file to t.qualifiedName].orEmpty(), input) }
            .sortedWith(compareByDescending<TypeCrap> { it.aggregatedCrap }.thenBy { it.id })

        val summary = summarize(methodCraps, typeCraps, input)

        val notes = buildList {
            add(SCHEMA_2_NOTE)
            addAll(input.notes)
        }

        return CrapReport(
            schemaVersion = Version.SCHEMA_VERSION,
            tool = Version.TOOL_NAME,
            toolVersion = Version.VERSION,
            generatedAt = input.generatedAt,
            sourceRoot = input.sourceRoot,
            coverageDataPath = input.coverageDataPath,
            threshold = input.threshold,
            coverageAvailable = input.coverageAvailable,
            notes = notes,
            summary = summary,
            methods = methodCraps,
            types = typeCraps,
        )
    }

    private fun toMethodCrap(m: MethodMetric, input: AggregationInput): MethodCrap {
        val coverage = coverageFor(m, input)
        val crap = Crap.score(m.weightedComplexity, coverage)
        return MethodCrap(
            id = "${m.file}#${m.qualifiedName}@${m.startLine}",
            file = m.file,
            line = m.startLine,
            endLine = m.endLine,
            typeName = m.typeName,
            name = m.name,
            qualifiedName = m.qualifiedName,
            kind = m.kind,
            complexity = m.complexity,
            cognitiveComplexity = m.cognitiveComplexity,
            weightedComplexity = m.weightedComplexity,
            coverage = coverage,
            crap = crap,
            isCrappy = crap > input.threshold,
        )
    }

    private fun toTypeCrap(t: TypeDecl, members: List<MethodMetric>, input: AggregationInput): TypeCrap {
        val totalComplexity = members.sumOf { it.complexity }
        val totalCognitive = members.sumOf { it.cognitiveComplexity }
        val maxComplexity = members.maxOfOrNull { it.complexity } ?: 0
        val maxCognitive = members.maxOfOrNull { it.cognitiveComplexity } ?: 0
        val weightedTotal = sqrt(totalComplexity.toDouble() * totalCognitive.toDouble())

        val weightedCoverage = lineWeightedCoverage(members, input)
        val memberCraps = members.map { Crap.score(it.weightedComplexity, coverageFor(it, input)) }
        val sumCrap = memberCraps.sum()
        val maxCrap = memberCraps.maxOrNull() ?: 0.0
        val aggregatedCrap = Crap.score(weightedTotal, weightedCoverage)

        return TypeCrap(
            id = "${t.file}#${t.qualifiedName}@${t.startLine}",
            file = t.file,
            line = t.startLine,
            kind = t.kind,
            name = t.name,
            methodCount = members.size,
            totalComplexity = totalComplexity,
            maxComplexity = maxComplexity,
            totalCognitiveComplexity = totalCognitive,
            maxCognitiveComplexity = maxCognitive,
            weightedTotalComplexity = weightedTotal,
            weightedCoverage = weightedCoverage,
            sumCrap = sumCrap,
            maxCrap = maxCrap,
            aggregatedCrap = aggregatedCrap,
            isCrappy = aggregatedCrap > input.threshold || maxCrap > input.threshold,
        )
    }

    private fun summarize(
        methods: List<MethodCrap>,
        types: List<TypeCrap>,
        input: AggregationInput,
    ): ReportSummary {
        val n = methods.size
        val fileCount = input.fileReports.count { it.methods.isNotEmpty() || it.types.isNotEmpty() }
        return ReportSummary(
            fileCount = fileCount,
            typeCount = types.size,
            methodCount = n,
            crappyMethodCount = methods.count { it.isCrappy },
            crappyTypeCount = types.count { it.isCrappy },
            averageCrap = if (n == 0) 0.0 else methods.sumOf { it.crap } / n,
            maxCrap = methods.maxOfOrNull { it.crap } ?: 0.0,
            averageComplexity = if (n == 0) 0.0 else methods.sumOf { it.complexity.toDouble() } / n,
            averageCognitiveComplexity = if (n == 0) 0.0 else methods.sumOf { it.cognitiveComplexity.toDouble() } / n,
            averageWeightedComplexity = if (n == 0) 0.0 else methods.sumOf { it.weightedComplexity } / n,
            weightedCoverage = if (!input.coverageAvailable) {
                null
            } else {
                overallWeightedCoverage(methods)
            },
        )
    }

    private fun coverageFor(m: MethodMetric, input: AggregationInput): Double {
        if (!input.coverageAvailable) return 0.0
        return input.provider.methodCoverage(m.file, m.startLine, m.endLine) ?: 0.0
    }

    private fun lineWeightedCoverage(members: List<MethodMetric>, input: AggregationInput): Double {
        if (members.isEmpty()) return 0.0
        var weighted = 0.0
        var totalLines = 0
        for (m in members) {
            val lines = maxOf(1, m.endLine - m.startLine + 1)
            weighted += coverageFor(m, input) * lines
            totalLines += lines
        }
        return if (totalLines == 0) 0.0 else weighted / totalLines
    }

    private fun overallWeightedCoverage(methods: List<MethodCrap>): Double {
        if (methods.isEmpty()) return 0.0
        var weighted = 0.0
        var totalLines = 0
        for (m in methods) {
            val lines = maxOf(1, m.endLine - m.line + 1)
            weighted += m.coverage * lines
            totalLines += lines
        }
        return if (totalLines == 0) 0.0 else weighted / totalLines
    }

    /** "A.B.bar" -> "A.B"; "foo" -> "" (a free function, owned by no type). */
    private fun enclosingTypeQualified(qualifiedName: String): String {
        val idx = qualifiedName.lastIndexOf('.')
        return if (idx < 0) "" else qualifiedName.substring(0, idx)
    }
}
