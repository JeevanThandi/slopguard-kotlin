package dev.slopguard.core.analysis

import dev.slopguard.core.MethodKind
import dev.slopguard.core.MethodMetric
import dev.slopguard.core.TypeDecl
import dev.slopguard.core.TypeKind
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/** Maps character offsets to 1-based line numbers. */
internal class LineMap(text: String) {
    private val starts: IntArray

    init {
        val list = ArrayList<Int>()
        list.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') list.add(i + 1)
        }
        starts = list.toIntArray()
    }

    fun lineAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans + 1
    }
}

/**
 * Computes McCabe cyclomatic and SonarSource-2023 cognitive complexity for a
 * single method body, in one PSI walk. Follows the same rules as the sibling
 * ports so wCRAP stays comparable across languages.
 *
 * Cyclomatic (base 1): +1 per `if`, `for`, `while`, `do`, non-`else` `when`
 * entry, `catch`, elvis `?:`, and each `&&` / `||`.
 *
 * Cognitive (base 0): structural constructs (`if`, loops, `when`, `catch`) add
 * `1 + nesting` and deepen nesting for their bodies; `else` / `else if` add a
 * flat `+1`; a whole `when` counts once regardless of branch count; runs of like
 * boolean operators collapse (a transition adds `+1`); labelled `break`/
 * `continue` add `+1`; lambdas, local functions and anonymous objects deepen
 * nesting without an entry of their own; plain early exits are free.
 */
internal class ComplexityCalculator {
    var cyclomatic = 1
    var cognitive = 0

    private fun walk(element: PsiElement?, nesting: Int) {
        when (element) {
            null -> return

            is KtIfExpression -> handleIf(element, nesting, isElseIf = false)

            is KtWhenExpression -> {
                cognitive += 1 + nesting
                walk(element.subjectExpression, nesting)
                for (entry in element.entries) {
                    if (!entry.isElse) cyclomatic += 1
                    entry.conditions.forEach { walk(it, nesting) }
                    walk(entry.expression, nesting + 1)
                }
            }

            is KtForExpression -> {
                cognitive += 1 + nesting
                cyclomatic += 1
                walk(element.loopRange, nesting)
                walk(element.body, nesting + 1)
            }

            is KtWhileExpression -> {
                cognitive += 1 + nesting
                cyclomatic += 1
                walk(element.condition, nesting)
                walk(element.body, nesting + 1)
            }

            is KtDoWhileExpression -> {
                cognitive += 1 + nesting
                cyclomatic += 1
                walk(element.condition, nesting)
                walk(element.body, nesting + 1)
            }

            is KtTryExpression -> {
                walk(element.tryBlock, nesting)
                for (clause in element.catchClauses) {
                    cognitive += 1 + nesting
                    cyclomatic += 1
                    walk(clause.catchBody, nesting + 1)
                }
                walk(element.finallyBlock?.finalExpression, nesting)
            }

            is KtBinaryExpression -> handleBinary(element, nesting)

            is KtLambdaExpression -> walk(element.bodyExpression, nesting + 1)

            is KtObjectLiteralExpression -> walk(element.objectDeclaration.body, nesting + 1)

            is KtNamedFunction -> {
                // A *local* function: deepen nesting, fold its body in. Top-level and
                // member functions are never reached here — they enter via their body.
                walk(element.bodyExpression, nesting + 1)
            }

            is KtBreakExpression -> {
                if (element.getLabelName() != null) cognitive += 1
            }

            is KtContinueExpression -> {
                if (element.getLabelName() != null) cognitive += 1
            }

            else -> recurseChildren(element, nesting)
        }
    }

    private fun recurseChildren(element: PsiElement, nesting: Int) {
        var child = element.firstChild
        while (child != null) {
            walk(child, nesting)
            child = child.nextSibling
        }
    }

    private fun handleIf(ifExpr: KtIfExpression, nesting: Int, isElseIf: Boolean) {
        cognitive += if (isElseIf) 1 else 1 + nesting
        cyclomatic += 1
        walk(ifExpr.condition, nesting)
        walk(ifExpr.then, nesting + 1)
        when (val elseBranch = ifExpr.`else`) {
            is KtIfExpression -> handleIf(elseBranch, nesting, isElseIf = true)
            null -> {}
            else -> {
                cognitive += 1
                walk(elseBranch, nesting + 1)
            }
        }
    }

    private fun handleBinary(expr: KtBinaryExpression, nesting: Int) {
        when (expr.operationToken) {
            KtTokens.ANDAND, KtTokens.OROR -> handleLogicalRoot(expr, nesting)
            KtTokens.ELVIS -> {
                cyclomatic += 1
                walk(expr.left, nesting)
                walk(expr.right, nesting)
            }
            else -> recurseChildren(expr, nesting)
        }
    }

    /**
     * Collapses a run of like boolean operators. `a && b && c` is one run (+1);
     * `a && b || c` transitions once (+2). Explicit parentheses start a fresh
     * run, picked up when [walk] re-enters the parenthesised expression.
     */
    private fun handleLogicalRoot(top: KtBinaryExpression, nesting: Int) {
        val ops = ArrayList<IElementType>()

        fun spine(e: KtExpression?) {
            if (e is KtBinaryExpression &&
                (e.operationToken == KtTokens.ANDAND || e.operationToken == KtTokens.OROR)
            ) {
                spine(e.left)
                ops.add(e.operationToken)
                walk(e.right, nesting)
            } else {
                walk(e, nesting)
            }
        }
        spine(top)

        cyclomatic += ops.size
        if (ops.isNotEmpty()) {
            var runs = 1
            for (i in 1 until ops.size) {
                if (ops[i] != ops[i - 1]) runs++
            }
            cognitive += runs
        }
    }

    companion object {
        fun compute(body: KtExpression?): Pair<Int, Int> {
            val calc = ComplexityCalculator()
            if (body != null) calc.walk(body, nesting = 0)
            return calc.cyclomatic to calc.cognitive
        }
    }
}

/**
 * Single-pass inventory of a parsed [KtFile]: every method-like declaration and
 * every type declaration, with complexity computed. Type membership is resolved
 * by lexical nesting (a method belongs to its innermost enclosing type), as in
 * the Swift and TypeScript ports.
 */
class ComplexityVisitor(
    private val reportedPath: String,
    private val ktFile: KtFile,
) {
    private val lineMap = LineMap(ktFile.text)
    private val methods = ArrayList<MethodMetric>()
    private val types = ArrayList<TypeDecl>()

    fun analyze(): Pair<List<MethodMetric>, List<TypeDecl>> {
        visitContainer(ktFile.declarations, emptyList())
        return methods to types
    }

    private fun visitContainer(declarations: List<PsiElement>, typeStack: List<String>) {
        for (decl in declarations) {
            when (decl) {
                is KtEnumEntry -> {
                    // Enum entries can carry overrides; fold them into the enum, no own type.
                    decl.body?.declarations?.let { visitContainer(it, typeStack) }
                }
                is KtClassOrObject -> visitType(decl, typeStack)
                is KtNamedFunction -> registerFunction(decl, typeStack)
                is KtSecondaryConstructor -> registerConstructor(decl, typeStack)
                is KtAnonymousInitializer -> registerInitializer(decl, typeStack)
                is KtProperty -> registerAccessors(decl, typeStack)
                else -> {}
            }
        }
    }

    private fun visitType(decl: KtClassOrObject, typeStack: List<String>) {
        val name = when {
            decl is KtObjectDeclaration && decl.isCompanion() && decl.name == null -> "Companion"
            else -> decl.name ?: "<anonymous>"
        }
        val newStack = typeStack + name
        types.add(
            TypeDecl(
                name = name,
                qualifiedName = newStack.joinToString("."),
                kind = typeKindOf(decl),
                file = reportedPath,
                startLine = startLine(decl),
                endLine = endLine(decl),
            ),
        )
        decl.body?.declarations?.let { visitContainer(it, newStack) }
    }

    private fun registerFunction(fn: KtNamedFunction, typeStack: List<String>) {
        val body = fn.bodyExpression ?: return // abstract / expect: no body to score
        val name = fn.name ?: "<anonymous>"
        val (cyc, cog) = ComplexityCalculator.compute(body)
        methods.add(
            metric(
                name = name,
                typeStack = typeStack,
                kind = if (typeStack.isEmpty()) MethodKind.FUNCTION else MethodKind.METHOD,
                node = fn,
                cyc = cyc,
                cog = cog,
            ),
        )
    }

    private fun registerConstructor(ctor: KtSecondaryConstructor, typeStack: List<String>) {
        val body = ctor.bodyExpression ?: return
        val (cyc, cog) = ComplexityCalculator.compute(body)
        methods.add(
            metric("constructor", typeStack, MethodKind.CONSTRUCTOR, ctor, cyc, cog),
        )
    }

    private fun registerInitializer(init: KtAnonymousInitializer, typeStack: List<String>) {
        val body = init.body ?: return
        val (cyc, cog) = ComplexityCalculator.compute(body)
        methods.add(
            metric("init", typeStack, MethodKind.INITIALIZER, init, cyc, cog),
        )
    }

    private fun registerAccessors(prop: KtProperty, typeStack: List<String>) {
        val base = prop.name ?: "<anonymous>"
        for (accessor in prop.accessors) {
            val body = accessor.bodyExpression ?: continue // only custom accessors with a body
            val (cyc, cog) = ComplexityCalculator.compute(body)
            val isGetter = accessor.isGetter
            methods.add(
                metric(
                    name = if (isGetter) "$base.get" else "$base.set",
                    typeStack = typeStack,
                    kind = if (isGetter) MethodKind.GETTER else MethodKind.SETTER,
                    node = accessor,
                    cyc = cyc,
                    cog = cog,
                ),
            )
        }
    }

    private fun metric(
        name: String,
        typeStack: List<String>,
        kind: MethodKind,
        node: PsiElement,
        cyc: Int,
        cog: Int,
    ): MethodMetric = MethodMetric(
        name = name,
        qualifiedName = (typeStack + name).joinToString("."),
        typeName = typeStack.lastOrNull(),
        kind = kind,
        file = reportedPath,
        startLine = startLine(node),
        endLine = endLine(node),
        complexity = cyc,
        cognitiveComplexity = cog,
    )

    private fun typeKindOf(decl: KtClassOrObject): TypeKind = when {
        decl is KtObjectDeclaration -> TypeKind.OBJECT
        decl is KtClass && decl.isInterface() -> TypeKind.INTERFACE
        decl is KtClass && decl.isEnum() -> TypeKind.ENUM
        else -> TypeKind.CLASS
    }

    private fun startLine(node: PsiElement): Int = lineMap.lineAt(node.textRange.startOffset)

    private fun endLine(node: PsiElement): Int =
        lineMap.lineAt(maxOf(node.textRange.startOffset, node.textRange.endOffset - 1))
}
