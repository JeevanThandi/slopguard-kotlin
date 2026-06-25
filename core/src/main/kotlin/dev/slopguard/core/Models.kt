package dev.slopguard.core

/** What kind of callable a method entry represents. */
enum class MethodKind(val wire: String) {
    FUNCTION("function"),
    METHOD("method"),
    CONSTRUCTOR("constructor"),
    INITIALIZER("initializer"),
    GETTER("getter"),
    SETTER("setter"),
}

/** What kind of declaration a type entry represents. */
enum class TypeKind(val wire: String) {
    CLASS("class"),
    INTERFACE("interface"),
    OBJECT("object"),
    ENUM("enum"),
}

/**
 * Raw per-method metrics straight off the parser, before coverage is joined.
 */
data class MethodMetric(
    val name: String,
    val qualifiedName: String,
    val typeName: String?,
    val kind: MethodKind,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val complexity: Int,
    val cognitiveComplexity: Int,
) {
    val weightedComplexity: Double = Crap.weightedComplexity(complexity, cognitiveComplexity)
}

/**
 * Raw per-type declaration. Method membership is resolved later, by lexical
 * nesting (a method belongs to its innermost enclosing type), mirroring the
 * Swift/TypeScript ports.
 */
data class TypeDecl(
    val name: String,
    val qualifiedName: String,
    val kind: TypeKind,
    val file: String,
    val startLine: Int,
    val endLine: Int,
)

/** Everything one source file yields. */
data class FileReport(
    val path: String,
    val methods: List<MethodMetric>,
    val types: List<TypeDecl>,
)

// ---- Report entries (post-coverage, schema 2) -----------------------------

data class MethodCrap(
    val id: String,
    val file: String,
    val line: Int,
    val endLine: Int,
    val typeName: String?,
    val name: String,
    val qualifiedName: String,
    val kind: MethodKind,
    val complexity: Int,
    val cognitiveComplexity: Int,
    val weightedComplexity: Double,
    val coverage: Double,
    val crap: Double,
    val isCrappy: Boolean,
)

data class TypeCrap(
    val id: String,
    val file: String,
    val line: Int,
    val kind: TypeKind,
    val name: String,
    val methodCount: Int,
    val totalComplexity: Int,
    val maxComplexity: Int,
    val totalCognitiveComplexity: Int,
    val maxCognitiveComplexity: Int,
    val weightedTotalComplexity: Double,
    val weightedCoverage: Double,
    val sumCrap: Double,
    val maxCrap: Double,
    val aggregatedCrap: Double,
    val isCrappy: Boolean,
)

data class ReportSummary(
    val fileCount: Int,
    val typeCount: Int,
    val methodCount: Int,
    val crappyMethodCount: Int,
    val crappyTypeCount: Int,
    val averageCrap: Double,
    val maxCrap: Double,
    val averageComplexity: Double,
    val averageCognitiveComplexity: Double,
    val averageWeightedComplexity: Double,
    val weightedCoverage: Double?,
)

data class CrapReport(
    val schemaVersion: String,
    val tool: String,
    val toolVersion: String,
    val generatedAt: String,
    val sourceRoot: String,
    val coverageDataPath: String?,
    val threshold: Double,
    val coverageAvailable: Boolean,
    val notes: List<String>,
    val summary: ReportSummary,
    val methods: List<MethodCrap>,
    val types: List<TypeCrap>,
)
