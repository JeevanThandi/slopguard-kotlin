package dev.slopguard.mutation

import java.io.File
import java.nio.file.Files

/**
 * A throwaway Gradle-shaped project whose `gradlew` is a shell script. Tests
 * script the build's behaviour (pass, fail, fail to compile, hang, write a
 * coverage report) without running real Gradle. Every invocation's arguments
 * are appended to `invocations.log`.
 */
class FakeGradleProject(gradlewBody: String) {
    val root: File = Files.createTempDirectory("fakegradle").toFile().canonicalFile
    val sourceDir: File = File(root, "src/main/kotlin/demo").apply { mkdirs() }
    val invocations: File = File(root, "invocations.log")
    val tempRoot: File = File(root.parentFile, "${root.name}-guards").apply { mkdirs() }

    init {
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        File(root, "gradlew").apply {
            writeText("#!/bin/sh\necho \"${'$'}@\" >> \"${invocations.path}\"\n$gradlewBody\n")
            setExecutable(true)
        }
    }

    fun source(name: String, text: String): File = File(sourceDir, name).apply { writeText(text) }

    fun invocationLines(): List<String> = if (invocations.exists()) invocations.readLines() else emptyList()

    fun delete() {
        root.deleteRecursively()
        tempRoot.deleteRecursively()
    }

    companion object {
        /** A JaCoCo-format report for `demo/<file>` with the given covered and missed lines. */
        fun reportXml(file: String, covered: List<Int>, missed: List<Int>): String {
            val lines = covered.map { """<line nr="$it" mi="0" ci="2"/>""" } + missed.map { """<line nr="$it" mi="2" ci="0"/>""" }
            return """<?xml version="1.0" encoding="UTF-8"?>
                |<report name="demo"><package name="demo"><sourcefile name="$file">${lines.joinToString("")}</sourcefile></package></report>
            """.trimMargin()
        }
    }
}
