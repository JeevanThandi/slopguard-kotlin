package dev.slopguard.core.analysis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobTest {
    @Test
    fun starMatchesAcrossSeparators() {
        assertTrue(Glob.matchesAny(listOf("**/build/**"), "app/build/tmp/Foo.kt"))
        assertTrue(Glob.matchesAny(listOf("*/build/*"), "app/build/Foo.kt"))
    }

    @Test
    fun suffixGlobMatchesTestFiles() {
        assertTrue(Glob.matchesAny(listOf("**/*Test.kt"), "src/main/FooTest.kt"))
        assertFalse(Glob.matchesAny(listOf("**/*Test.kt"), "src/main/Foo.kt"))
    }

    @Test
    fun defaultExcludesDropBuildAndTests() {
        val excludes = DirectoryAnalyzer.DEFAULT_EXCLUDE_GLOBS
        assertTrue(Glob.matchesAny(excludes, "core/build/generated/Foo.kt"))
        assertTrue(Glob.matchesAny(excludes, "src/test/kotlin/FooTest.kt"))
        assertTrue(Glob.matchesAny(excludes, "src/androidTest/kotlin/Foo.kt"))
        assertFalse(Glob.matchesAny(excludes, "src/main/kotlin/Foo.kt"))
    }

    @Test
    fun emptyGlobsMatchNothing() {
        assertFalse(Glob.matchesAny(emptyList(), "anything.kt"))
    }
}
