package dev.slopguard.core.mutation

import dev.slopguard.core.MethodKind
import dev.slopguard.core.MethodMetric
import dev.slopguard.core.analysis.AnalysisOptions
import dev.slopguard.core.analysis.KotlinParser
import dev.slopguard.core.errors.SlopguardError
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationPlannerTest {
    private val parser = KotlinParser()
    private val tmp: File = Files.createTempDirectory("planner").toFile()

    @AfterTest
    fun tearDown() {
        parser.close()
        tmp.deleteRecursively()
    }

    private fun plan(source: String, operators: Collection<MutationOperator> = MutationOperator.ALL) =
        MutationPlanner(parser).plan(source, "T.kt", operators)

    private fun metric(name: String, start: Int, end: Int) =
        MethodMetric(name, name, null, MethodKind.FUNCTION, "T.kt", start, end, 1, 0)

    @Test
    fun namesTheEnclosingMethodLikeTheCrapReport() {
        val src = """
            class Store(private var count: Int = 0) {
                fun bump(by: Int): Int {
                    count += by
                    return count
                }
                val isEmpty: Boolean
                    get() = count == 0
            }
            val limit = 1 + 2
        """.trimIndent()
        val byId = plan(src).associate { it.site.id to it.method }
        assertEquals("Store.bump", byId["T.kt:3:15:arithmetic"])
        assertEquals("Store.isEmpty.get", byId["T.kt:7:23:negate_conditional"])
        assertNull(byId["T.kt:9:15:arithmetic"])
    }

    @Test
    fun innermostMethodWinsAndTiesGoToTheLaterStart() {
        val methods = listOf(metric("outer", 1, 20), metric("inner", 5, 9), metric("tieEarly", 10, 12), metric("tieLate", 11, 13))
        assertEquals("inner", MutationPlanner.enclosingMethod(methods, 6))
        assertEquals("tieLate", MutationPlanner.enclosingMethod(methods, 11))
        assertEquals("outer", MutationPlanner.enclosingMethod(methods, 2))
        assertNull(MutationPlanner.enclosingMethod(methods, 21))
    }

    @Test
    fun markersSwitchMutantsToIgnored() {
        val src = """
            fun f(a: Int, b: Int): Boolean {
                // slopguard-ignore-mutant(boundary)
                val x = a > b
                val y = a < b // slopguard-ignore-mutant
                return x && y
            }
        """.trimIndent()
        val ignored = plan(src).filter { it.ignored }.map { it.site.id }
        assertEquals(listOf("T.kt:3:15:boundary", "T.kt:4:15:boundary", "T.kt:4:15:negate_conditional"), ignored)
        assertFalse(plan(src).single { it.site.id == "T.kt:3:15:negate_conditional" }.ignored)
    }

    @Test
    fun operatorsFilterAndOrderIsStable() {
        val src = "fun f(a: Int, b: Int) = a < b && !(a == b)"
        val all = plan(src).map { it.site.id }
        assertEquals(
            listOf(
                "T.kt:1:27:boundary", "T.kt:1:27:negate_conditional", "T.kt:1:31:logical",
                "T.kt:1:34:remove_not", "T.kt:1:38:negate_conditional",
            ),
            all,
        )
        val onlyLogical = plan(src, listOf(MutationOperator.LOGICAL)).map { it.site.id }
        assertEquals(listOf("T.kt:1:31:logical"), onlyLogical)
    }

    @Test
    fun planPathWalksLikeAnalyzeAndSortsFiles() {
        val src = File(tmp, "src/main/kotlin/demo").apply { mkdirs() }
        File(src, "b.kt").writeText("fun b(x: Int) = x + 1\n")
        File(src, "a.kt").writeText("fun a() = 1\n")
        File(src, "Z.kt").writeText("fun z(x: Boolean) = !x\n")
        File(src, "aTest.kt").writeText("fun t() = 1 + 1\n")
        File(tmp, "build/generated").apply { mkdirs() }.resolve("G.kt").writeText("fun g() = 1 + 1\n")

        val plan = MutationPlanner.planPath(tmp.absolutePath, AnalysisOptions(), MutationOperator.ALL)
        assertEquals(tmp.absoluteFile, plan.sourceRoot)
        assertEquals(
            listOf("src/main/kotlin/demo/Z.kt", "src/main/kotlin/demo/a.kt", "src/main/kotlin/demo/b.kt"),
            plan.files.map { it.relativePath },
        )
        assertEquals(
            listOf("src/main/kotlin/demo/Z.kt:1:21:remove_not", "src/main/kotlin/demo/b.kt:1:19:arithmetic"),
            plan.mutants.map { it.site.id },
        )
        val b = plan.files.last()
        assertTrue(b.bytes.contentEquals(File(src, "b.kt").readBytes()))
        assertEquals("fun b(x: Int) = x + 1\n", b.source)
    }

    @Test
    fun planPathOnASingleFileUsesItsParentAsRoot() {
        val file = File(tmp, "One.kt").apply { writeText("fun one(x: Int) = x * 2\n") }
        val plan = MutationPlanner.planPath(file.absolutePath, AnalysisOptions(), MutationOperator.ALL)
        assertEquals(tmp.absoluteFile, plan.sourceRoot)
        assertEquals(listOf("One.kt:1:21:arithmetic"), plan.mutants.map { it.site.id })
    }

    @Test
    fun planPathRejectsANonKotlinFile() {
        val text = File(tmp, "notes.txt").apply { writeText("a + b") }
        val error = assertFailsWith<SlopguardError> { MutationPlanner.planPath(text.path, AnalysisOptions(), MutationOperator.ALL) }
        assertEquals("unsupported", error.code.wire)
    }

    @Test
    fun planPathReportsMissingPaths() {
        val error = assertFailsWith<SlopguardError> {
            MutationPlanner.planPath(File(tmp, "missing").absolutePath, AnalysisOptions(), MutationOperator.ALL)
        }
        assertEquals("file_not_found", error.code.wire)
    }
}
