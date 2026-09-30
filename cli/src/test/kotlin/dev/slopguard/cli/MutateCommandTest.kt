package dev.slopguard.cli

import dev.slopguard.core.mutation.MutationOperator
import dev.slopguard.coverage.CoverageTool
import dev.slopguard.mutation.MutationArgs
import dev.slopguard.mutation.MutationInterruptedException
import dev.slopguard.mutation.MutationPipeline
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutateCommandTest {
    private class Captured(val code: Int, val out: String, val err: String)

    private val dir: File = Files.createTempDirectory("mutatecli").toFile().canonicalFile
    private val guards: File = Files.createTempDirectory("mutatecli-guards").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
        guards.deleteRecursively()
    }

    private fun run(vararg args: String): Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = MutateCommand.run(args.toList(), PrintStream(out), PrintStream(err)) { MutationPipeline(guards).run(it) }
        return Captured(code, out.toString(), err.toString())
    }

    /** A project whose fake Gradle wrapper passes every run, so every mutant survives. */
    private fun project(gradlew: String = "exit 0"): File {
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        File(dir, "gradlew").apply {
            writeText("#!/bin/sh\n$gradlew\n")
            setExecutable(true)
        }
        File(dir, "src/main/kotlin/demo").apply { mkdirs() }.resolve("Calc.kt").writeText(
            "package demo\n\nfun add(a: Int, b: Int) = a + b\nfun isPositive(x: Int) = x > 0\n",
        )
        return File(dir, "src/main/kotlin")
    }

    @Test
    fun cliDispatchesMutateAndListsItInHelp() {
        val out = ByteArrayOutputStream()
        val code = Cli.run(arrayOf("mutate", "--path", project().path, "--dry-run", "--quiet"), PrintStream(out), PrintStream(ByteArrayOutputStream()))
        assertEquals(0, code)
        assertContains(out.toString(), "mutation report (schema 1)")
        assertContains(Cli.usage(), "mutate    ")
        assertContains(Cli.usage(), "--fail-under <score>")
    }

    @Test
    fun dryRunTextListsPendingMutants() {
        val r = run("--path", project().path, "--dry-run")
        assertEquals(0, r.code)
        assertContains(r.out, "runner:    (not run)")
        assertContains(r.out, "Mutants (3, not run)")
        assertContains(r.out, "  demo/Calc.kt:3:29  arithmetic  `+` → `-`  add")
        assertContains(r.out, "  pending:        3")
        assertContains(r.err, "slopguard: generated 3 mutant(s) in 1 file(s)")
        assertFalse(File(dir, "invocations.log").exists())
    }

    @Test
    fun dryRunJsonHasNullRunFieldsAndRespectsOperators() {
        val r = run("--path", project().path, "--dry-run", "--json", "--quiet", "--operators", "boundary", "--operators=negate_conditional")
        assertEquals(0, r.code)
        assertContains(r.out, "\"reportType\": \"mutation\"")
        assertContains(r.out, "\"runner\": null")
        assertContains(r.out, "\"mutationScore\": null")
        assertContains(r.out, "\"id\": \"demo/Calc.kt:4:28:boundary\"")
        assertContains(r.out, "\"id\": \"demo/Calc.kt:4:28:negate_conditional\"")
        assertFalse(r.out.contains("arithmetic\""))
        assertEquals("", r.err)
    }

    @Test
    fun failUnderExitsTwoWhenTheScoreIsBelow() {
        val r = run("--path", project().path, "--no-coverage", "--quiet", "--fail-under", "90")
        assertEquals(2, r.code)
        assertContains(r.out, "score:          0.00%")
        assertEquals("slopguard-kotlin: mutation score 0.00% is below --fail-under 90\n", r.err)
    }

    @Test
    fun failUnderPassesWhenTheScoreIsReachedAndIgnoresDryRuns() {
        val src = project("if grep -qE 'a - b|x >= 0|x <= 0' src/main/kotlin/demo/Calc.kt; then exit 1; fi; exit 0")
        val r = run("--path", src.path, "--no-coverage", "--quiet", "--json", "--fail-under", "100")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "\"mutationScore\": 100")
        assertContains(r.out, "\"killed\": 3")
        assertEquals(0, run("--path", src.path, "--dry-run", "--quiet", "--fail-under", "100").code)
    }

    @Test
    fun aNullScoreNeverFails() {
        val src = project()
        File(src, "demo/Calc.kt").writeText("package demo\nfun one() = 1\n")
        val r = run("--path", src.path, "--quiet", "--fail-under", "50")
        assertEquals(0, r.code)
        assertContains(r.out, "score:          n/a")
    }

    @Test
    fun invalidFlagsAreInvalidArguments() {
        val cases = listOf(
            listOf("--operators", "nope,boundary") to "unknown operator(s): nope",
            listOf("--timeout", "0") to "not a positive number: 0",
            listOf("--timeout", "-3") to "not a positive number: -3",
            listOf("--timeout", "soon") to "not a positive number: soon",
            listOf("--timeout", "NaN") to "not a positive number: NaN",
            listOf("--fail-under", "high") to "not a number: high",
            listOf("--coverage-tool", "cobertura") to "expected one of kover|jacoco",
            listOf("--threshold", "30") to "Invalid value for --threshold: unknown flag",
            listOf("--fail-over", "5") to "Invalid value for --fail-over: unknown flag",
            listOf("--coverage-file", "x.xml") to "Invalid value for --coverage-file: unknown flag",
            listOf("--timeout") to "Invalid value for --timeout: missing value",
        )
        for ((args, expected) in cases) {
            val r = run(*args.toTypedArray())
            assertEquals(1, r.code, args.toString())
            assertContains(r.err, "slopguard-kotlin: [invalid_argument]", message = args.toString())
            assertContains(r.err, expected, message = args.toString())
        }
    }

    @Test
    fun jsonModeReportsArgumentErrorsAsJson() {
        val r = run("--json", "--timeout", "zero")
        assertEquals(1, r.code)
        assertContains(r.err, "\"code\": \"invalid_argument\"")
        assertTrue(r.err.trimStart().startsWith("{"))
    }

    @Test
    fun runErrorsUseTheEnvelope() {
        val r = run("--path", File(dir, "missing").path, "--json")
        assertEquals(1, r.code)
        assertContains(r.err, "\"code\": \"file_not_found\"")
        val failing = run("--path", project("echo broken; exit 1").path, "--quiet")
        assertEquals(1, failing.code)
        assertContains(failing.err, "slopguard-kotlin: [baseline_failed] The test suite fails without any mutation (exit 1)")
    }

    @Test
    fun everyFlagReachesThePipeline() {
        var seen: MutationArgs? = null
        val code = MutateCommand.run(
            listOf(
                "-p", "src", "--include", "**/a/**", "--exclude", "**/b/**", "--no-default-excludes",
                "--operators", "logical", "--project-dir", "proj", "--coverage-tool", "jacoco",
                "--gradle-test-task", ":app:test", "--gradle-report-task", "jacocoReport", "--no-coverage",
                "--timeout=2.5", "--dry-run", "-v",
            ),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(ByteArrayOutputStream()),
        ) { args ->
            seen = args
            MutationPipeline(guards).run(MutationArgs(sourcePath = project().path, dryRun = true, reporter = args.reporter))
        }
        assertEquals(0, code)
        val args = seen!!
        assertEquals("src", args.sourcePath)
        assertEquals(listOf("**/a/**"), args.analysisOptions.includeGlobs)
        assertEquals(listOf("**/b/**"), args.analysisOptions.excludeGlobs)
        assertFalse(args.analysisOptions.useDefaultExcludes)
        assertEquals(listOf(MutationOperator.LOGICAL), args.operators)
        assertEquals("proj", args.projectDir)
        assertEquals(CoverageTool.JACOCO, args.coverageTool)
        assertEquals(":app:test", args.testTask)
        assertEquals("jacocoReport", args.reportTask)
        assertFalse(args.coverage)
        assertEquals(2.5, args.timeoutSeconds)
        assertTrue(args.dryRun)
        assertTrue(args.reporter.isVerbose)
    }

    @Test
    fun helpPrintsTheUsageAndStartsNoRun() {
        for (flag in listOf("--help", "-h")) {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val code = MutateCommand.run(listOf("--path", "src", "--json", flag), PrintStream(out), PrintStream(err)) {
                error("--help must not start a mutation run")
            }
            assertEquals(0, code, flag)
            assertContains(out.toString(), "MUTATE OPTIONS", message = flag)
            assertEquals(Cli.usage() + System.lineSeparator(), out.toString(), flag)
            assertEquals("", err.toString(), flag)
        }
    }

    @Test
    fun anInterruptedRunPrintsNoReport() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = MutateCommand.run(listOf("--quiet"), PrintStream(out), PrintStream(err)) { throw MutationInterruptedException() }
        assertEquals(130, code)
        assertEquals("", out.toString())
        assertEquals("", err.toString())
    }

    @Test
    fun pathFlagsExpandTheHomeDirectory() {
        val home = System.getProperty("user.home")
        assertEquals(home, Cli.expandTilde("~"))
        assertEquals("$home/src", Cli.expandTilde("~/src"))
        assertEquals("~user/src", Cli.expandTilde("~user/src"))
        assertEquals("src/~", Cli.expandTilde("src/~"))
        var seen: MutationArgs? = null
        MutateCommand.run(listOf("--path", "~/x", "--project-dir", "~", "--quiet"), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())) {
            seen = it
            throw MutationInterruptedException()
        }
        assertEquals("$home/x", seen!!.sourcePath)
        assertEquals(home, seen!!.projectDir)
    }
}
