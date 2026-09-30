package dev.slopguard.core.mutation

import dev.slopguard.core.analysis.LineMap
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFinallySection
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenEntry

/**
 * Walks a parsed Kotlin file and lists every mutant the shared operator set
 * defines (see [MutationOperator]). Only real PSI nodes are mutated — never
 * comments, string text or annotation arguments — and each mutant replaces one
 * contiguous span of the original text. Pure: no I/O.
 */
class MutantGenerator {

    /**
     * Every mutant in [ktFile], in walk order. [reportedPath] becomes each
     * mutant's `file`. [originalSource] is the file's text before the parser
     * normalized its line separators; mutant offsets and `original` text refer
     * to it, while line and column follow the parsed text (the compiler's view).
     */
    fun generate(ktFile: KtFile, reportedPath: String, originalSource: String = ktFile.text): List<MutantSite> {
        val walker = Walker(ktFile.text, originalSource, reportedPath)
        walker.walk(ktFile)
        return walker.sites
    }

    private class Walker(
        private val text: String,
        private val originalSource: String,
        private val reportedPath: String,
    ) {
        val sites = ArrayList<MutantSite>()
        private val lines = LineMap(text)
        private val offsets = OffsetMap(originalSource)

        fun walk(element: PsiElement) {
            // Annotation arguments are compile-time metadata, not executable code.
            if (element is KtAnnotationEntry) return
            visit(element)
            var child = element.firstChild
            while (child != null) {
                walk(child)
                child = child.nextSibling
            }
        }

        private fun visit(element: PsiElement) {
            when (element) {
                is KtBinaryExpression -> binaryMutants(element)
                is KtPrefixExpression -> prefixMutants(element)
                is KtPostfixExpression -> postfixMutants(element)
                is KtConstantExpression -> booleanMutant(element)
                is KtBlockExpression -> callStatementMutants(element)
                else -> {}
            }
        }

        private fun binaryMutants(expr: KtBinaryExpression) {
            val token = expr.operationToken
            ARITHMETIC[token]?.let { if (!isStringConcatenation(expr)) emitOperator(MutationOperator.ARITHMETIC, expr, it) }
            BOUNDARY[token]?.let { emitOperator(MutationOperator.BOUNDARY, expr, it) }
            NEGATE_CONDITIONAL[token]?.let { emitOperator(MutationOperator.NEGATE_CONDITIONAL, expr, it) }
            LOGICAL[token]?.let { emitOperator(MutationOperator.LOGICAL, expr, it) }
        }

        private fun prefixMutants(expr: KtPrefixExpression) {
            when (expr.operationToken) {
                KtTokens.EXCL -> emitRemoval(MutationOperator.REMOVE_NOT, expr)
                KtTokens.MINUS -> emitRemoval(MutationOperator.INVERT_NEGATIVE, expr)
                KtTokens.PLUSPLUS -> emitOperator(MutationOperator.INCREMENT, expr, "--")
                KtTokens.MINUSMINUS -> emitOperator(MutationOperator.INCREMENT, expr, "++")
            }
        }

        /** Postfix `++` / `--` only; the force unwrap `!!` is never touched. */
        private fun postfixMutants(expr: KtPostfixExpression) {
            when (expr.operationToken) {
                KtTokens.PLUSPLUS -> emitOperator(MutationOperator.INCREMENT, expr, "--")
                KtTokens.MINUSMINUS -> emitOperator(MutationOperator.INCREMENT, expr, "++")
            }
        }

        private fun booleanMutant(expr: KtConstantExpression) {
            if (expr.node.elementType != KtNodeTypes.BOOLEAN_CONSTANT) return
            val replacement = if (expr.text == "true") "false" else "true"
            emit(MutationOperator.BOOLEAN_LITERAL, expr.textRange.startOffset, expr.textRange.endOffset, replacement)
        }

        /**
         * `remove_call`: a statement that is nothing but a call, directly inside a
         * braced block, is removed (replaced by empty text). The last statement of
         * a block whose value may be used (a lambda body, a branch of an `if` /
         * `when` / `try` used as an expression) is kept: removing it would change
         * the block's type and most likely not compile.
         */
        private fun callStatementMutants(block: KtBlockExpression) {
            val statements = block.statements
            statements.forEachIndexed { index, statement ->
                if (!isCallStatement(statement) || isLoggingCall(statement)) return@forEachIndexed
                if (index == statements.lastIndex && blockValueUsed(block)) return@forEachIndexed
                emit(MutationOperator.REMOVE_CALL, statement.textRange.startOffset, statement.textRange.endOffset, "")
            }
        }

        private fun emitOperator(operator: MutationOperator, expr: KtOperationExpression, replacement: String) {
            val range = expr.operationReference.textRange
            emit(operator, range.startOffset, range.endOffset, replacement)
        }

        /**
         * Removes a prefix operator token. When the token sits between two
         * identifier characters (`return!x`), a space stands in for it so the
         * neighbouring tokens do not merge into one identifier.
         */
        private fun emitRemoval(operator: MutationOperator, expr: KtPrefixExpression) {
            val range = expr.operationReference.textRange
            val start = range.startOffset
            val end = range.endOffset
            val joins = start > 0 && end < text.length && isIdentifierChar(text[start - 1]) && isIdentifierChar(text[end])
            emit(operator, start, end, if (joins) " " else "")
        }

        private fun emit(operator: MutationOperator, start: Int, end: Int, replacement: String) {
            val line = lines.lineAt(start)
            val column = text.codePointCount(lines.lineStart(line), start) + 1
            val originalStart = offsets.toOriginal(start)
            val originalEnd = offsets.toOriginal(end)
            sites.add(
                MutantSite(
                    file = reportedPath,
                    line = line,
                    column = column,
                    operator = operator,
                    original = originalSource.substring(originalStart, originalEnd),
                    replacement = replacement,
                    startOffset = originalStart,
                    endOffset = originalEnd,
                ),
            )
        }
    }

    companion object {
        private val ARITHMETIC: Map<IElementType, String> = mapOf(
            KtTokens.PLUS to "-",
            KtTokens.MINUS to "+",
            KtTokens.MUL to "/",
            KtTokens.DIV to "*",
            KtTokens.PERC to "*",
            KtTokens.PLUSEQ to "-=",
            KtTokens.MINUSEQ to "+=",
            KtTokens.MULTEQ to "/=",
            KtTokens.DIVEQ to "*=",
            KtTokens.PERCEQ to "*=",
        )

        private val BOUNDARY: Map<IElementType, String> = mapOf(
            KtTokens.LT to "<=",
            KtTokens.LTEQ to "<",
            KtTokens.GT to ">=",
            KtTokens.GTEQ to ">",
        )

        private val NEGATE_CONDITIONAL: Map<IElementType, String> = mapOf(
            KtTokens.EQEQ to "!=",
            KtTokens.EXCLEQ to "==",
            KtTokens.EQEQEQ to "!==",
            KtTokens.EXCLEQEQEQ to "===",
            KtTokens.LT to ">=",
            KtTokens.LTEQ to ">",
            KtTokens.GT to "<=",
            KtTokens.GTEQ to "<",
        )

        private val LOGICAL: Map<IElementType, String> = mapOf(
            KtTokens.ANDAND to "||",
            KtTokens.OROR to "&&",
        )

        /** Unqualified printing calls that `remove_call` never removes. */
        private val PRINT_CALLS = setOf("println", "print")

        /** Receivers of qualified printing calls (`System.out.println(…)`). */
        private val PRINT_RECEIVERS = setOf("System.out", "System.err", "kotlin.io")

        /** Receiver roots of logging calls (`logger.info(…)`, `Log.d(…)`). */
        private val LOGGER_ROOTS = setOf("logger", "log", "Log")

        /** `+` / `+=` joining strings is concatenation, not arithmetic. */
        private fun isStringConcatenation(expr: KtBinaryExpression): Boolean {
            val token = expr.operationToken
            if (token != KtTokens.PLUS && token != KtTokens.PLUSEQ) return false
            return isStringy(expr.left) || isStringy(expr.right)
        }

        /** A string template, or (through parentheses) a `+` with a stringy operand. */
        private fun isStringy(expr: KtExpression?): Boolean = when (expr) {
            is KtStringTemplateExpression -> true
            is KtParenthesizedExpression -> isStringy(expr.expression)
            is KtBinaryExpression -> expr.operationToken == KtTokens.PLUS && isStringConcatenation(expr)
            else -> false
        }

        /** A call, or a qualified expression (`a.b()`, `a?.b()`) whose selector is a call. */
        private fun isCallStatement(statement: KtExpression): Boolean = when (statement) {
            is KtCallExpression -> true
            is KtQualifiedExpression -> statement.selectorExpression is KtCallExpression
            else -> false
        }

        private fun isLoggingCall(statement: KtExpression): Boolean {
            if (statement is KtCallExpression) return calleeName(statement) in PRINT_CALLS
            val qualified = statement as KtQualifiedExpression
            val receiver = qualified.receiverExpression
            val call = qualified.selectorExpression as KtCallExpression
            if (calleeName(call) in PRINT_CALLS && receiver.text in PRINT_RECEIVERS) return true
            return receiverRoot(receiver) in LOGGER_ROOTS
        }

        private fun calleeName(call: KtCallExpression): String? =
            (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

        /** The leftmost name of a receiver chain, looking past a leading `this.` / `super.`. */
        private fun receiverRoot(receiver: KtExpression): String? {
            var root: KtExpression? = receiver
            var afterRoot: KtExpression? = null
            while (root is KtQualifiedExpression) {
                afterRoot = root.selectorExpression
                root = root.receiverExpression
            }
            if (root is KtThisExpression || root is KtSuperExpression) root = afterRoot
            return (root as? KtNameReferenceExpression)?.getReferencedName()
        }

        /** Whether the value of [block]'s last statement can be used by the surrounding code. */
        private fun blockValueUsed(block: KtBlockExpression): Boolean = when (val parent = block.parent) {
            is KtFunctionLiteral -> true
            is KtDeclarationWithBody, is KtAnonymousInitializer, is KtFinallySection -> false
            is KtContainerNodeForControlStructureBody -> controlBodyValueUsed(parent)
            is KtWhenEntry -> expressionValueUsed(parent.parent as KtExpression)
            is KtTryExpression -> expressionValueUsed(parent)
            is KtCatchClause -> expressionValueUsed(parent.parent as KtExpression)
            else -> true
        }

        /** Loop bodies never yield a value; `if` branches yield the `if`'s value. */
        private fun controlBodyValueUsed(container: KtContainerNodeForControlStructureBody): Boolean {
            val owner = container.parent
            return owner !is KtLoopExpression && (owner !is KtIfExpression || expressionValueUsed(owner))
        }

        /** Whether [expr]'s value is used: false only for a statement whose value is discarded. */
        private fun expressionValueUsed(expr: KtExpression): Boolean = when (val parent = expr.parent) {
            is KtBlockExpression -> parent.statements.lastOrNull() == expr && blockValueUsed(parent)
            is KtContainerNodeForControlStructureBody -> controlBodyValueUsed(parent)
            is KtWhenEntry -> expressionValueUsed(parent.parent as KtExpression)
            else -> true
        }

        private fun isIdentifierChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
    }
}
