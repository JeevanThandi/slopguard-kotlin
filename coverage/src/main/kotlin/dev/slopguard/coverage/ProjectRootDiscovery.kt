package dev.slopguard.coverage

import java.io.File

/** Finds the nearest enclosing Gradle project root by walking up the tree. */
object ProjectRootDiscovery {
    private const val MAX_DEPTH = 64

    private val ROOT_MARKERS = listOf(
        "settings.gradle.kts",
        "settings.gradle",
        "gradlew",
        "build.gradle.kts",
        "build.gradle",
    )

    /** @return the nearest ancestor (inclusive) containing a Gradle marker, or null. */
    fun discover(start: File): File? {
        var dir: File? = if (start.isDirectory) start.absoluteFile else start.absoluteFile.parentFile
        var depth = 0
        while (dir != null && depth < MAX_DEPTH) {
            if (ROOT_MARKERS.any { File(dir, it).exists() }) return dir
            dir = dir.parentFile
            depth++
        }
        return null
    }
}
