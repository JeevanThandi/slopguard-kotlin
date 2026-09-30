package dev.slopguard.core.mutation

import dev.slopguard.core.analysis.KotlinParser
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutantGeneratorTest {
    private val parser = KotlinParser()

    @AfterTest
    fun tearDown() = parser.close()

    private fun sites(source: String): List<MutantSite> =
        MutantGenerator().generate(parser.parse("T.kt", source), "T.kt")

    private fun sites(source: String, operator: MutationOperator): List<MutantSite> =
        sites(source).filter { it.operator == operator }

    /** `original → replacement` pairs for one operator, in source order. */
    private fun changes(source: String, operator: MutationOperator): List<String> =
        sites(source, operator).map { "${it.original}→${it.replacement}" }

    // ---- arithmetic --------------------------------------------------------

    @Test
    fun arithmeticSwapsEveryBinaryOperator() {
        val src = "fun f(a: Int, b: Int) = listOf(a + b, a - b, a * b, a / b, a % b)"
        assertEquals(listOf("+→-", "-→+", "*→/", "/→*", "%→*"), changes(src, MutationOperator.ARITHMETIC))
    }

    @Test
    fun arithmeticSwapsCompoundAssignments() {
        val src = """
            fun f() {
                var x = 1
                x += 1
                x -= 1
                x *= 2
                x /= 2
                x %= 2
            }
        """.trimIndent()
        assertEquals(listOf("+=→-=", "-=→+=", "*=→/=", "/=→*=", "%=→*="), changes(src, MutationOperator.ARITHMETIC))
    }

    @Test
    fun arithmeticSkipsStringConcatenation() {
        val src = """
            fun f(a: Int, b: Int, s: String): String {
                var t = "a" + a
                t = a.toString() + "b"
                t = "x${'$'}a" + b + s
                t = ("a" + a) + b
                t += "tail"
                return t
            }
        """.trimIndent()
        assertEquals(emptyList(), changes(src, MutationOperator.ARITHMETIC))
    }

    @Test
    fun arithmeticKeepsNumericOperandOfConcatenation() {
        // (a + b) + "c": the inner `+` adds numbers, the outer one joins strings.
        val src = "fun f(a: Int, b: Int) = a + b + \"c\""
        val found = sites(src, MutationOperator.ARITHMETIC)
        assertEquals(1, found.size)
        assertEquals(27, found.single().column)
    }

    @Test
    fun arithmeticNeverTouchesUnaryOperators() {
        assertEquals(emptyList(), changes("fun f(a: Int) = listOf(-a, +a)", MutationOperator.ARITHMETIC))
    }

    // ---- boolean_literal ---------------------------------------------------

    @Test
    fun booleanLiteralFlips() {
        val src = "fun f() = listOf(true, false)"
        assertEquals(listOf("true→false", "false→true"), changes(src, MutationOperator.BOOLEAN_LITERAL))
    }

    @Test
    fun annotationArgumentsAreNeverMutated() {
        val src = """
            annotation class Flag(val on: Boolean)
            @Flag(true) fun f() = 1 + 2
        """.trimIndent()
        assertEquals(emptyList(), changes(src, MutationOperator.BOOLEAN_LITERAL))
        assertEquals(listOf("+→-"), changes(src, MutationOperator.ARITHMETIC))
    }

    // ---- boundary / negate_conditional ------------------------------------

    @Test
    fun boundaryShiftsComparisons() {
        val src = "fun f(a: Int, b: Int) = listOf(a < b, a <= b, a > b, a >= b)"
        assertEquals(listOf("<→<=", "<=→<", ">→>=", ">=→>"), changes(src, MutationOperator.BOUNDARY))
    }

    @Test
    fun negateConditionalInvertsComparisons() {
        val src = "fun f(a: Any, b: Any, x: Int, y: Int) = listOf(a == b, a != b, a === b, a !== b, x < y, x <= y, x > y, x >= y)"
        assertEquals(
            listOf("==→!=", "!=→==", "===→!==", "!==→===", "<→>=", "<=→>", ">→<=", ">=→<"),
            changes(src, MutationOperator.NEGATE_CONDITIONAL),
        )
    }

    @Test
    fun typeArgumentsAreNotComparisons() {
        val src = "fun f(): List<Int> = listOf<Int>()"
        assertEquals(emptyList(), sites(src))
    }

    @Test
    fun inAndIsChecksAreNotMutated() {
        val src = "fun f(x: Any, xs: List<Int>) = listOf(x is Int, x !is String, 1 in xs, 2 !in xs)"
        assertEquals(emptyList(), sites(src))
    }

    // ---- increment ---------------------------------------------------------

    @Test
    fun incrementSwapsPrefixAndPostfix() {
        val src = """
            fun f() {
                var i = 0
                i++
                i--
                ++i
                --i
            }
        """.trimIndent()
        assertEquals(listOf("++→--", "--→++", "++→--", "--→++"), changes(src, MutationOperator.INCREMENT))
    }

    // ---- invert_negative ---------------------------------------------------

    @Test
    fun invertNegativeRemovesUnaryMinus() {
        val src = "fun f(a: Int) = listOf(-a, -1, a - 1)"
        assertEquals(listOf("-→", "-→"), changes(src, MutationOperator.INVERT_NEGATIVE))
    }

    @Test
    fun removedTokenBetweenIdentifiersBecomesASpace() {
        val src = "fun f(a: Boolean, b: Int): Int { if (a) return-b; return 0 }"
        val site = sites(src, MutationOperator.INVERT_NEGATIVE).single()
        assertEquals(" ", site.replacement)
        assertTrue(site.applyTo(src).contains("return b"))
    }

    // ---- logical -----------------------------------------------------------

    @Test
    fun logicalSwapsAndOr() {
        val src = "fun f(a: Boolean, b: Boolean) = listOf(a && b, a || b)"
        assertEquals(listOf("&&→||", "||→&&"), changes(src, MutationOperator.LOGICAL))
    }

    // ---- remove_not --------------------------------------------------------

    @Test
    fun removeNotDropsPrefixBang() {
        val src = "fun f(a: Boolean, b: Boolean?) = listOf(!a, b!!, a != true)"
        assertEquals(listOf("!→"), changes(src, MutationOperator.REMOVE_NOT))
    }

    // ---- remove_call -------------------------------------------------------

    @Test
    fun removeCallDropsCallStatementsInBlocks() {
        val src = """
            class C {
                fun f(o: StringBuilder?, xs: MutableList<Int>) {
                    xs.add(1)
                    o?.append("x")
                    xs.clear()
                }
            }
        """.trimIndent()
        assertEquals(
            listOf("xs.add(1)→", "o?.append(\"x\")→", "xs.clear()→"),
            changes(src, MutationOperator.REMOVE_CALL),
        )
    }

    @Test
    fun removeCallKeepsMultiLineStatementVerbatim() {
        val src = "fun f(xs: MutableList<Int>) {\n    xs.addAll(\n        listOf(1, 2),\n    )\n}"
        val site = sites(src, MutationOperator.REMOVE_CALL).single()
        assertEquals("xs.addAll(\n        listOf(1, 2),\n    )", site.original)
        assertEquals("", site.replacement)
        assertEquals(2, site.line)
        assertEquals(5, site.column)
    }

    @Test
    fun removeCallSkipsLoggingAndPrinting() {
        val src = """
            class C(val logger: Any, val log: Any) {
                fun f() {
                    println("x")
                    print(1)
                    System.out.println("x")
                    System.err.print("x")
                    logger.toString()
                    log.hashCode()
                    Log.d("tag", "m")
                    this.logger.toString()
                    work()
                }
                fun work() {}
            }
            object Log { fun d(tag: String, message: String) {} }
        """.trimIndent()
        assertEquals(listOf("work()→"), changes(src, MutationOperator.REMOVE_CALL))
    }

    @Test
    fun removeCallIgnoresNonCallStatements() {
        val src = """
            fun f(xs: MutableList<Int>): Int {
                val a = xs.size
                var b = 0
                b = xs.size
                xs.size
                xs[0]
                return a + b
            }
        """.trimIndent()
        assertEquals(emptyList(), changes(src, MutationOperator.REMOVE_CALL))
    }

    @Test
    fun removeCallNeverTouchesUnbracedBodies() {
        val src = """
            fun f(c: Boolean, xs: MutableList<Int>, n: Int) {
                if (c) xs.clear() else xs.add(1)
                for (x in xs) xs.remove(x)
                while (c) xs.clear()
                when (n) { 1 -> xs.clear() }
            }
        """.trimIndent()
        assertEquals(emptyList(), changes(src, MutationOperator.REMOVE_CALL))
    }

    @Test
    fun removeCallKeepsTheLastStatementOfALambda() {
        val src = """
            fun f(xs: MutableList<Int>, out: MutableList<Int>) {
                xs.forEach {
                    out.add(it)
                    out.add(it + 1)
                }
            }
        """.trimIndent()
        // The whole forEach statement and the first add; the lambda's last expression stays.
        assertEquals(
            listOf(2, 3),
            sites(src, MutationOperator.REMOVE_CALL).map { it.line },
        )
    }

    @Test
    fun removeCallKeepsTheValueOfBranchesUsedAsExpressions() {
        val src = """
            fun f(c: Boolean, xs: MutableList<Int>): Boolean {
                val v = if (c) {
                    xs.clear()
                    xs.add(1)
                } else {
                    xs.add(2)
                }
                val w = when (c) {
                    true -> { xs.clear(); xs.add(3) }
                    else -> xs.add(4)
                }
                val t = try { xs.clear(); xs.add(5) } catch (e: Exception) { xs.clear(); xs.add(6) } finally { xs.clear() }
                return v && w && t
            }
        """.trimIndent()
        assertEquals(
            listOf("3:9", "9:19", "12:19", "12:66", "12:100"),
            sites(src, MutationOperator.REMOVE_CALL).map { "${it.line}:${it.column}" },
        )
    }

    @Test
    fun removeCallDropsLastStatementsOfBranchesUsedAsStatements() {
        val src = """
            fun f(c: Boolean, n: Int, xs: MutableList<Int>) {
                if (c) {
                    xs.clear()
                } else {
                    xs.add(1)
                }
                when (n) {
                    1 -> { xs.add(2) }
                }
                for (x in 0..n) { xs.add(x) }
                try { xs.add(3) } catch (e: Exception) { xs.add(4) }
            }
        """.trimIndent()
        assertEquals(6, sites(src, MutationOperator.REMOVE_CALL).size)
    }

    @Test
    fun removeCallInInitBlocksAccessorsAndConstructors() {
        val src = """
            class C(val xs: MutableList<Int>) {
                init { xs.add(1) }
                constructor() : this(mutableListOf()) { xs.add(2) }
                val first: Int
                    get() { xs.add(3); return xs.first() }
            }
        """.trimIndent()
        assertEquals(3, sites(src, MutationOperator.REMOVE_CALL).size)
    }

    // ---- positions and ids ------------------------------------------------

    @Test
    fun columnsCountCodePointsNotUtf16Units() {
        // 😀 is two UTF-16 units but one code point; é is one of each.
        val src = "fun f(a: Int) = \"é😀\".length + a"
        val site = sites(src, MutationOperator.ARITHMETIC).single()
        assertEquals(1, site.line)
        assertEquals(src.indexOf('+'), site.startOffset)
        assertEquals(src.codePointCount(0, src.indexOf('+')) + 1, site.column)
        assertEquals(29, site.column)
    }

    @Test
    fun idCombinesFileLineColumnAndOperator() {
        val src = "fun f(a: Int, b: Int): Boolean {\n    return a < b\n}"
        val ids = sites(src).map { it.id }
        assertEquals(listOf("T.kt:2:14:boundary", "T.kt:2:14:negate_conditional"), ids)
    }

    @Test
    fun crlfSourcesMapOffsetsBackToTheOriginalText() {
        val src = "package p\r\n\r\nfun f(a: Int) =\r\n    a + 1\r\n\r\nfun g(xs: MutableList<Int>) {\r\n    xs.addAll(\r\n        listOf(1),\r\n    )\r\n}\r\n"
        val found = MutantGenerator().generate(parser.parse("T.kt", src), "T.kt", src)
        val plus = found.single { it.operator == MutationOperator.ARITHMETIC }
        assertEquals(4, plus.line)
        assertEquals(7, plus.column)
        assertEquals("+", src.substring(plus.startOffset, plus.endOffset))
        val call = found.single { it.operator == MutationOperator.REMOVE_CALL }
        assertEquals(7, call.line)
        assertEquals("xs.addAll(\r\n        listOf(1),\r\n    )", call.original)
        assertEquals(call.original, src.substring(call.startOffset, call.endOffset))
    }

    @Test
    fun loneCarriageReturnsCountAsLineBreaks() {
        val src = "fun f(a: Int) =\r    a - 1\r"
        val site = MutantGenerator().generate(parser.parse("T.kt", src), "T.kt", src).single()
        assertEquals(2, site.line)
        assertEquals("-", src.substring(site.startOffset, site.endOffset))
    }

    @Test
    fun commentsAndStringTextAreNeverMutated() {
        val src = """
            // a + b && !c
            /* true < false */
            fun f() = "a + b && !c == true"
        """.trimIndent()
        assertEquals(emptyList(), sites(src))
    }

    @Test
    fun stringTemplateExpressionsAreRealCode() {
        val src = "fun f(a: Int, b: Int) = \"sum \${a + b}\""
        assertEquals(listOf("+→-"), changes(src, MutationOperator.ARITHMETIC))
    }

    @Test
    fun applyToSplicesTheReplacement() {
        val src = "fun f(a: Int, b: Int) = a < b"
        val site = sites(src, MutationOperator.NEGATE_CONDITIONAL).single()
        assertEquals("fun f(a: Int, b: Int) = a >= b", site.applyTo(src))
    }
}
