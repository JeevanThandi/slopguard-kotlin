package dev.slopguard.coverage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectRootDiscoveryTest {
    @Test
    fun walksUpToNearestGradleMarker() {
        val root = File.createTempFile("proj", "").let { it.delete(); it.mkdirs(); it }
        File(root, "settings.gradle.kts").writeText("")
        val nested = File(root, "src/main/kotlin").apply { mkdirs() }

        assertEquals(root.canonicalFile, ProjectRootDiscovery.discover(nested)?.canonicalFile)
        root.deleteRecursively()
    }

    @Test
    fun prefersInnerProjectOverOuter() {
        val outer = File.createTempFile("outer", "").let { it.delete(); it.mkdirs(); it }
        File(outer, "settings.gradle.kts").writeText("")
        val inner = File(outer, "modules/inner").apply { mkdirs() }
        File(inner, "build.gradle.kts").writeText("")
        val src = File(inner, "src").apply { mkdirs() }

        assertEquals(inner.canonicalFile, ProjectRootDiscovery.discover(src)?.canonicalFile)
        outer.deleteRecursively()
    }
}
