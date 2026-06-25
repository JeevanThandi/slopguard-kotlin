package dev.slopguard.core.analysis

import dev.slopguard.core.MethodMetric
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplexityVisitorTest {
    private val parser = KotlinParser()

    @AfterTest
    fun tearDown() = parser.close()

    private fun analyze(source: String): List<MethodMetric> {
        val ktFile = parser.parse("Test.kt", source)
        return ComplexityVisitor("Test.kt", ktFile).analyze().first
    }

    private fun method(source: String, name: String): MethodMetric =
        analyze(source).first { it.name == name || it.qualifiedName == name }

    @Test
    fun straightLineMethodIsBaseline() {
        val m = method("fun f(x: Int): Int = x + 1", "f")
        assertEquals(1, m.complexity)
        assertEquals(0, m.cognitiveComplexity)
    }

    @Test
    fun ifElseIfElseChain() {
        val src = """
            fun f(n: Int): String {
                if (n > 0) return "pos"
                else if (n < 0) return "neg"
                else return "zero"
            }
        """.trimIndent()
        val m = method(src, "f")
        // cyclomatic: 2 ifs -> base1 + 2 = 3
        assertEquals(3, m.complexity)
        // cognitive: if(+1) + else-if(+1) + else(+1) = 3
        assertEquals(3, m.cognitiveComplexity)
    }

    @Test
    fun nestingAmplifiesCognitive() {
        val src = """
            fun f(xs: List<Int>) {
                for (x in xs) {          // +1 (nesting 0)
                    if (x > 0) {         // +1 + 1 (nesting 1) = 2
                        while (x > 1) {  // +1 + 2 (nesting 2) = 3
                            println(x)
                        }
                    }
                }
            }
        """.trimIndent()
        val m = method(src, "f")
        assertEquals(4, m.complexity) // base1 + for + if + while
        assertEquals(6, m.cognitiveComplexity) // 1 + 2 + 3
    }

    @Test
    fun booleanRunsCollapse() {
        val same = method("fun f(a: Boolean, b: Boolean, c: Boolean) = a && b && c", "f")
        // one run -> +1 cognitive; two && -> +2 cyclomatic
        assertEquals(3, same.complexity)
        assertEquals(1, same.cognitiveComplexity)

        val mixed = method("fun g(a: Boolean, b: Boolean, c: Boolean) = a && b || c", "g")
        // transition -> +2 cognitive
        assertEquals(3, mixed.complexity)
        assertEquals(2, mixed.cognitiveComplexity)
    }

    @Test
    fun wholeWhenCountsOnceForCognitive() {
        val src = """
            fun f(n: Int): Int {
                return when (n) {
                    0 -> 1
                    1 -> 2
                    2 -> 3
                    else -> 0
                }
            }
        """.trimIndent()
        val m = method(src, "f")
        // cyclomatic: 3 non-else entries -> base1 + 3 = 4
        assertEquals(4, m.complexity)
        // cognitive: whole when = +1
        assertEquals(1, m.cognitiveComplexity)
    }

    @Test
    fun elvisCountsCyclomaticNotCognitive() {
        val m = method("fun f(x: Int?): Int = x ?: 0", "f")
        assertEquals(2, m.complexity)
        assertEquals(0, m.cognitiveComplexity)
    }

    @Test
    fun lambdaBumpsNestingWithoutOwnEntry() {
        val src = """
            fun f(xs: List<Int>): List<Int> {
                return xs.map { if (it > 0) it else -it }
            }
        """.trimIndent()
        val methods = analyze(src)
        assertEquals(1, methods.size) // the lambda is not its own method
        val m = methods.first()
        // if inside lambda: nesting bumped to 1 -> if (+1+1) + else (+1) = 3
        assertEquals(3, m.cognitiveComplexity)
    }

    @Test
    fun catchClauseCounts() {
        val src = """
            fun f() {
                try {
                    risky()
                } catch (e: Exception) {
                    handle(e)
                } finally {
                    cleanup()
                }
            }
        """.trimIndent()
        val m = method(src, "f")
        assertEquals(2, m.complexity) // base1 + catch
        assertEquals(1, m.cognitiveComplexity) // catch +1, try/finally free
    }

    @Test
    fun classifiesTypesAndMembership() {
        val src = """
            class Outer {
                fun a() { }
                interface Inner {
                    fun b(): Int = 1
                }
            }
            object Singleton {
                fun c() { }
            }
        """.trimIndent()
        val methods = analyze(src)
        val a = methods.first { it.name == "a" }
        assertEquals("Outer", a.typeName)
        assertEquals("Outer.a", a.qualifiedName)
        val b = methods.first { it.name == "b" }
        assertEquals("Inner", b.typeName)
        assertEquals("Outer.Inner.b", b.qualifiedName)
    }

    @Test
    fun topLevelFunctionHasNoType() {
        val m = method("fun free() { }", "free")
        assertNull(m.typeName)
        assertTrue(m.qualifiedName == "free")
    }

    @Test
    fun abstractMethodsAreSkipped() {
        val src = """
            interface I {
                fun required(): Int
                fun provided(): Int = 1
            }
        """.trimIndent()
        val methods = analyze(src)
        assertEquals(1, methods.size)
        assertEquals("provided", methods.first().name)
    }

    @Test
    fun customAccessorsBecomeGetterSetter() {
        val src = """
            class C {
                var x: Int = 0
                    get() = if (field > 0) field else 0
                    set(value) { field = if (value < 0) 0 else value }
            }
        """.trimIndent()
        val methods = analyze(src)
        assertTrue(methods.any { it.name == "x.get" })
        assertTrue(methods.any { it.name == "x.set" })
    }
}
