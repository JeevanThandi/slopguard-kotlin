package dev.slopguard.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private class Captured(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Captured(code, out.toString(), err.toString())
    }

    private fun sampleProject(): File {
        val dir = File.createTempFile("cliproj", "").let { it.delete(); it.mkdirs(); it }
        val src = File(dir, "src/main/kotlin/demo").apply { mkdirs() }
        File(src, "Hot.kt").writeText(
            """
            package demo
            class Hot {
                fun tangle(a: Boolean, b: Boolean, c: Boolean, n: Int): Int {
                    var total = 0
                    if (a && b || c) {
                        for (i in 0..n) {
                            if (i % 2 == 0) total += i
                            else if (i % 3 == 0) total -= i
                        }
                    }
                    return total
                }
            }
            """.trimIndent(),
        )
        return dir
    }

    @Test
    fun versionEmitsJson() {
        val r = run("version")
        assertEquals(0, r.code)
        assertContains(r.out, "\"name\": \"slopguard-kotlin\"")
        assertContains(r.out, "\"schemaVersion\": \"2\"")
    }

    @Test
    fun helpIsZeroExit() {
        assertEquals(0, run("--help").code)
    }

    @Test
    fun analyzeHelpPrintsTheUsage() {
        for (flag in listOf("--help", "-h")) {
            val r = run("analyze", flag)
            assertEquals(0, r.code, flag)
            assertContains(r.out, "ANALYZE OPTIONS", message = flag)
            assertEquals(Cli.usage() + System.lineSeparator(), r.out, flag)
            assertEquals("", r.err, flag)
        }
    }

    @Test
    fun mutateHelpPrintsTheUsage() {
        for (flag in listOf("--help", "-h")) {
            val r = run("mutate", flag)
            assertEquals(0, r.code, flag)
            assertContains(r.out, "MUTATE OPTIONS", message = flag)
            assertEquals(Cli.usage() + System.lineSeparator(), r.out, flag)
            assertEquals("", r.err, flag)
        }
    }

    @Test
    fun subcommandHelpWinsOverTheOtherFlags() {
        // With --help or -h nothing runs, so the missing path is never checked.
        val cases = listOf(
            listOf("analyze", "--path", "/nope/does/not/exist", "--json", "--help"),
            listOf("mutate", "--path", "/nope/does/not/exist", "--json", "-h"),
        )
        for (args in cases) {
            val r = run(*args.toTypedArray())
            assertEquals(0, r.code, args.toString())
            assertEquals(Cli.usage() + System.lineSeparator(), r.out, args.toString())
            assertEquals("", r.err, args.toString())
        }
    }

    @Test
    fun analyzeNoCoveragePretty() {
        val dir = sampleProject()
        val r = run("analyze", "--path", dir.absolutePath, "--no-coverage", "--quiet")
        assertEquals(0, r.code)
        assertContains(r.out, "slopguard-kotlin 0.2.0")
        assertContains(r.out, "coverage: unavailable")
        dir.deleteRecursively()
    }

    @Test
    fun analyzeJsonEmitsSchema2() {
        val dir = sampleProject()
        val r = run("analyze", "--path", dir.absolutePath, "--no-coverage", "--quiet", "--json")
        assertEquals(0, r.code)
        assertContains(r.out, "\"schemaVersion\": \"2\"")
        assertContains(r.out, "\"tool\": \"slopguard-kotlin\"")
        assertContains(r.out, "\"qualifiedName\": \"Hot.tangle\"")
        dir.deleteRecursively()
    }

    @Test
    fun failOverReturnsTwoWhenExceeded() {
        val dir = sampleProject()
        // The tangle method is complex and untested -> high wCRAP -> exceeds a tiny threshold.
        val r = run("analyze", "--path", dir.absolutePath, "--no-coverage", "--quiet", "--fail-over", "1")
        assertEquals(2, r.code)
        dir.deleteRecursively()
    }

    @Test
    fun failOverReturnsZeroWhenUnderLimit() {
        val dir = sampleProject()
        val r = run("analyze", "--path", dir.absolutePath, "--no-coverage", "--quiet", "--fail-over", "100000")
        assertEquals(0, r.code)
        dir.deleteRecursively()
    }

    @Test
    fun unknownFlagIsError() {
        val r = run("analyze", "--bogus")
        assertEquals(1, r.code)
        assertContains(r.err, "[invalid_argument]")
    }

    @Test
    fun missingPathIsFileNotFound() {
        val r = run("analyze", "--path", "/nope/does/not/exist", "--no-coverage", "--json")
        assertEquals(1, r.code)
        assertContains(r.err, "\"code\": \"file_not_found\"")
    }

    @Test
    fun coverageToolFlagParses() {
        val dir = sampleProject()
        // --no-coverage short-circuits the tool, but the flag must still parse.
        val r = run("analyze", "--path", dir.absolutePath, "--coverage-tool", "kover", "--no-coverage", "--quiet")
        assertEquals(0, r.code)
        dir.deleteRecursively()
    }

    @Test
    fun unknownCoverageToolIsError() {
        val r = run("analyze", "--coverage-tool", "cobertura", "--quiet")
        assertEquals(1, r.code)
        assertContains(r.err, "invalid_argument")
    }

    @Test
    fun badThresholdIsInvalidArgument() {
        val r = run("analyze", "--threshold", "abc")
        assertEquals(1, r.code)
        assertTrue(r.err.contains("invalid_argument"))
    }
}
