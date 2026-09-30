package dev.slopguard.mutation

import dev.slopguard.core.ProgressReporter
import dev.slopguard.core.Verbosity
import dev.slopguard.core.mutation.MutantStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `mutate` against the real `sample-apps/todolist` with its real Gradle wrapper
 * and Kover. It runs on a temporary copy: mutants are written in place, and
 * other builds may use the checked-in fixture at the same time.
 */
class SampleAppEndToEndTest {
    private val sampleApp = File(System.getProperty("slopguard.sampleApp") ?: "../sample-apps/todolist").canonicalFile

    @Test
    fun everyMutantInTodoIsKilledAndTheCopyIsRestored() {
        val copy = Files.createTempDirectory("todolist").toFile().canonicalFile
        val guards = Files.createTempDirectory("todolist-guards").toFile()
        try {
            sampleApp.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile }
                .forEach { file ->
                    val target = File(copy, file.relativeTo(sampleApp).path)
                    file.copyTo(target)
                    if (file.canExecute()) target.setExecutable(true)
                }
            val todo = File(copy, "src/main/kotlin/com/example/todo/Todo.kt")
            val before = todo.readBytes()

            val report = MutationPipeline(guards).run(
                MutationArgs(
                    sourcePath = todo.path,
                    projectDir = copy.path,
                    reporter = ProgressReporter(Verbosity.NORMAL),
                ),
            )

            assertEquals(
                listOf("Todo.kt:7:30:boolean_literal", "Todo.kt:9:44:remove_not", "Todo.kt:19:16:boolean_literal", "Todo.kt:20:19:remove_not"),
                report.mutants.map { it.id },
            )
            assertTrue(report.mutants.all { it.status == MutantStatus.KILLED }, report.mutants.toString())
            assertEquals(100.0, report.summary.mutationScore)
            assertTrue(report.coverageAvailable, report.notes.toString())
            assertEquals(emptyList(), report.notes)
            assertEquals("gradle", report.runner)
            assertEquals(listOf(null, "Todo.toggled", "TodoFilter.matches", "TodoFilter.matches"), report.mutants.map { it.method })
            assertContentEquals(before, todo.readBytes())
            assertFalse(File(WorkspaceGuard.directoryFor(copy, guards), WorkspaceGuard.LOCK).exists())
        } finally {
            copy.deleteRecursively()
            guards.deleteRecursively()
        }
    }
}
