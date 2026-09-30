package dev.slopguard.mutation

import dev.slopguard.core.errors.SlopguardError
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceGuardTest {
    private val base: File = Files.createTempDirectory("guard").toFile().canonicalFile
    private val tempRoot = File(base, "tmp").apply { mkdirs() }
    private val project = File(base, "project").apply { mkdirs() }
    private val source = File(project, "Calc.kt")
    private val originalBytes = "fun add(a: Int, b: Int) = a + b\n".toByteArray()
    private val mutantBytes = "fun add(a: Int, b: Int) = a - b\n".toByteArray()
    private val originalMtime = 1_600_000_000_000L

    init {
        source.writeBytes(originalBytes)
        source.setLastModified(originalMtime)
    }

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun acquire(pid: Long = 1111, isAlive: (Long) -> Boolean = { false }) =
        WorkspaceGuard.acquire(project, tempRoot, pid, isAlive)

    private fun guardDir() = WorkspaceGuard.directoryFor(project, tempRoot)

    /** Leave the guard directory as a crashed run (pid 4242) would, mid-mutant. */
    private fun simulateCrash(fileContent: ByteArray) {
        val dir = guardDir().apply { mkdirs() }
        File(dir, WorkspaceGuard.LOCK).writeText("4242")
        File(dir, WorkspaceGuard.ORIGINAL).apply {
            writeBytes(originalBytes)
            setLastModified(originalMtime)
        }
        File(dir, WorkspaceGuard.JOURNAL).writeText(
            """{"file":"${source.absolutePath}","mutantSha256":"${WorkspaceGuard.sha256(mutantBytes)}"}""",
        )
        source.writeBytes(fileContent)
    }

    // ---- lock --------------------------------------------------------------

    @Test
    fun guardDirectoryLivesInTheTempRootKeyedByRealProjectPath() {
        val dir = guardDir()
        assertEquals(File(tempRoot, "slopguard-mutate"), dir.parentFile)
        assertTrue(dir.name.matches(Regex("[0-9a-f]{16}")))
        val link = File(base, "link")
        Files.createSymbolicLink(link.toPath(), project.toPath())
        assertEquals(dir, WorkspaceGuard.directoryFor(link, tempRoot))
        assertFalse(dir.path.startsWith(project.path))
    }

    @Test
    fun lockHoldsThePidAndIsExclusiveWhileTheHolderLives() {
        val first = acquire(pid = 1111)
        assertEquals("1111", File(first.directory, WorkspaceGuard.LOCK).readText())
        val error = assertFailsWith<SlopguardError> { acquire(pid = 2222, isAlive = { it == 1111L }) }
        assertEquals("mutation_in_progress", error.code.wire)
        assertEquals("Another slopguard mutate run (pid 1111) is using ${project.path}.", error.message)
        first.release()
        assertFalse(File(first.directory, WorkspaceGuard.LOCK).exists())
        assertFalse(first.directory.exists(), "release removes the empty guard directory")
        acquire(pid = 2222, isAlive = { it == 1111L }).release()
    }

    @Test
    fun deadHolderMeansAStaleLock() {
        acquire(pid = 1111)
        val second = acquire(pid = 2222, isAlive = { false })
        assertEquals("2222", File(second.directory, WorkspaceGuard.LOCK).readText())
        assertEquals(emptyList(), second.notes)
        second.release()
    }

    @Test
    fun ourOwnPidOrAnUnreadableLockIsStale() {
        acquire(pid = 1111)
        acquire(pid = 1111, isAlive = { true }).release()
        guardDir().apply { mkdirs() }.resolve(WorkspaceGuard.LOCK).writeText("not a pid")
        acquire(pid = 3333, isAlive = { true }).release()
    }

    @Test
    fun anUnusableTempRootIsAnError() {
        val notADirectory = File(base, "file").apply { writeText("x") }
        val error = assertFailsWith<java.io.IOException> { WorkspaceGuard.acquire(project, notADirectory, 1111) { false } }
        assertTrue(error.message!!.contains(notADirectory.path), error.message)
    }

    @Test
    fun aMissingProjectStillHasAGuardDirectory() {
        val dir = WorkspaceGuard.directoryFor(File(base, "not-created-yet"), tempRoot)
        assertTrue(dir.name.matches(Regex("[0-9a-f]{16}")))
        assertFalse(dir == guardDir())
    }

    @Test
    fun realLivenessProbe() {
        assertTrue(WorkspaceGuard.processIsAlive(ProcessHandle.current().pid()))
        assertFalse(WorkspaceGuard.processIsAlive(Long.MAX_VALUE))
    }

    // ---- recovery ----------------------------------------------------------

    @Test
    fun staleLockRestoresAFileThatStillHoldsTheMutant() {
        simulateCrash(mutantBytes)
        val guard = acquire(pid = 5555)
        assertContentEquals(originalBytes, source.readBytes())
        assertEquals(originalMtime, source.lastModified())
        assertEquals(listOf("Restored ${source.absolutePath}, which an interrupted mutate run left mutated."), guard.notes)
        assertFalse(File(guard.directory, WorkspaceGuard.JOURNAL).exists())
        assertFalse(File(guard.directory, WorkspaceGuard.ORIGINAL).exists())
        guard.release()
    }

    @Test
    fun staleLockKeepsTheBackupWhenTheFileChangedSince() {
        val edited = "fun add(a: Int, b: Int) = b + a // edited\n".toByteArray()
        simulateCrash(edited)
        val guard = acquire(pid = 5555)
        assertContentEquals(edited, source.readBytes())
        val kept = guard.directory.listFiles()!!.single { it.name.startsWith("original-") }
        assertContentEquals(originalBytes, kept.readBytes())
        assertEquals(
            listOf(
                "An interrupted mutate run left a backup of ${source.absolutePath} at ${kept.path}. " +
                    "The file has changed since, so it was not restored.",
            ),
            guard.notes,
        )
        guard.release()
        assertTrue(kept.exists())
        assertTrue(guard.directory.exists(), "a kept backup keeps the guard directory")
    }

    @Test
    fun staleLockIsSilentWhenTheFileAlreadyHoldsTheOriginal() {
        simulateCrash(originalBytes)
        val guard = acquire(pid = 5555)
        assertEquals(emptyList(), guard.notes)
        assertContentEquals(originalBytes, source.readBytes())
        guard.release()
    }

    @Test
    fun staleLockWithoutABackupPointsToVersionControl() {
        simulateCrash(mutantBytes)
        File(guardDir(), WorkspaceGuard.ORIGINAL).delete()
        val guard = acquire(pid = 5555)
        assertContentEquals(mutantBytes, source.readBytes())
        assertEquals(
            listOf(
                "An interrupted mutate run left ${source.absolutePath} mutated and its backup is missing. " +
                    "Restore the file from version control.",
            ),
            guard.notes,
        )
        guard.release()
    }

    @Test
    fun tornJournalIsIgnored() {
        simulateCrash(mutantBytes)
        File(guardDir(), WorkspaceGuard.JOURNAL).writeText("""{"file": "/x""")
        val guard = acquire(pid = 5555)
        assertEquals(emptyList(), guard.notes)
        guard.release()
    }

    @Test
    fun journalStringsAreUnescaped() {
        val pretty = "{\n  \"file\": \"/a \\\"b\\\"\\\\c\\u00e9\\n\",\n  \"mutantSha256\": \"ab\"\n}"
        assertEquals("/a \"b\"\\cé\n", WorkspaceGuard.jsonString(pretty, "file"))
        assertEquals("ab", WorkspaceGuard.jsonString("""{"file":"x","mutantSha256":"ab"}""", "mutantSha256"))
        assertNull(WorkspaceGuard.jsonString("{}", "file"))
    }

    // ---- mutants -----------------------------------------------------------

    @Test
    fun withMutantJournalsBacksUpWritesInPlaceAndRestores() {
        val guard = acquire()
        val link = File(project, "hardlink.kt")
        Files.createLink(link.toPath(), source.toPath())
        val inode = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()

        val seen = guard.withMutant(source, originalBytes, mutantBytes) {
            val journal = File(guard.directory, WorkspaceGuard.JOURNAL).readText()
            assertEquals(source.absolutePath, WorkspaceGuard.jsonString(journal, "file"))
            assertEquals(WorkspaceGuard.sha256(mutantBytes), WorkspaceGuard.jsonString(journal, "mutantSha256"))
            assertContentEquals(originalBytes, File(guard.directory, WorkspaceGuard.ORIGINAL).readBytes())
            assertContentEquals(mutantBytes, link.readBytes())
            source.readText()
        }

        assertEquals(String(mutantBytes), seen)
        assertContentEquals(originalBytes, source.readBytes())
        assertContentEquals(originalBytes, link.readBytes())
        assertEquals(inode, Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(originalMtime, source.lastModified())
        assertFalse(File(guard.directory, WorkspaceGuard.JOURNAL).exists())
        guard.release()
        assertFalse(File(guard.directory, WorkspaceGuard.ORIGINAL).exists())
    }

    @Test
    fun withMutantRestoresWhenTheBodyThrows() {
        val guard = acquire()
        val error = assertFailsWith<IllegalStateException> {
            guard.withMutant(source, originalBytes, mutantBytes) { error("test run blew up") }
        }
        assertEquals("test run blew up", error.message)
        assertContentEquals(originalBytes, source.readBytes())
        assertEquals(originalMtime, source.lastModified())
        assertFalse(File(guard.directory, WorkspaceGuard.JOURNAL).exists())
        guard.release()
    }

    @Test
    fun aReadOnlyFileFailsWithoutARestoreError() {
        val guard = acquire()
        source.setWritable(false)
        try {
            val error = assertFailsWith<Exception> {
                guard.withMutant(source, originalBytes, mutantBytes) { error("must not run") }
            }
            assertFalse(error is SlopguardError, error.toString())
            assertContentEquals(originalBytes, source.readBytes())
            assertFalse(File(guard.directory, WorkspaceGuard.JOURNAL).exists())
        } finally {
            source.setWritable(true)
        }
        guard.release()
        assertFalse(guard.directory.exists())
    }

    @Test
    fun aChangedFileIsNeverMutated() {
        val guard = acquire()
        val error = assertFailsWith<SourceChangedException> {
            guard.withMutant(source, "something else".toByteArray(), mutantBytes) { error("must not run") }
        }
        assertEquals(source, error.file)
        assertContentEquals(originalBytes, source.readBytes())
        assertFalse(File(guard.directory, WorkspaceGuard.JOURNAL).exists())
        guard.release()
    }

    @Test
    fun aFileEditedBetweenTwoMutantsIsLeftAlone() {
        val guard = acquire()
        guard.withMutant(source, originalBytes, mutantBytes) { }
        val edited = "edited between two mutants".toByteArray()
        source.writeBytes(edited)
        val error = assertFailsWith<SourceChangedException> {
            guard.withMutant(source, originalBytes, mutantBytes) { error("must not run") }
        }
        assertEquals(source, error.file)
        assertContentEquals(edited, source.readBytes())
        guard.release()
        assertContentEquals(edited, source.readBytes())
    }

    @Test
    fun interruptRestoresReleasesAndStopsFurtherMutants() {
        val guard = acquire()
        var restored: File? = null
        guard.withMutant(source, originalBytes, mutantBytes) { restored = guard.interrupt() }
        assertEquals(source, restored)
        assertContentEquals(originalBytes, source.readBytes())
        assertFalse(File(guard.directory, WorkspaceGuard.LOCK).exists())
        assertFailsWith<MutationInterruptedException> {
            guard.withMutant(source, originalBytes, mutantBytes) { error("must not run") }
        }
        assertNull(guard.interrupt())
    }

    @Test
    fun aFailedRestoreKeepsTheLockAndBackupForRecovery() {
        val guard = acquire()
        val error = assertFailsWith<SlopguardError> {
            guard.withMutant(source, originalBytes, mutantBytes) { project.deleteRecursively() }
        }
        assertEquals("restore_failed", error.code.wire)
        assertTrue(error.message.contains(source.path))
        assertTrue(error.message.contains(File(guard.directory, WorkspaceGuard.ORIGINAL).path))
        assertFailsWith<SlopguardError> { guard.release() }
        assertTrue(File(guard.directory, WorkspaceGuard.LOCK).exists())
        assertContentEquals(originalBytes, File(guard.directory, WorkspaceGuard.ORIGINAL).readBytes())
    }

    @Test
    fun movingToAnotherFileBacksUpThatFile() {
        val other = File(project, "Other.kt").apply { writeText("val x = 1\n") }
        val guard = acquire()
        guard.withMutant(source, originalBytes, mutantBytes) {}
        guard.withMutant(other, "val x = 1\n".toByteArray(), "val x = 2\n".toByteArray()) {
            assertEquals("val x = 1\n", File(guard.directory, WorkspaceGuard.ORIGINAL).readText())
        }
        assertEquals("val x = 1\n", other.readText())
        assertNull(guard.restore())
        guard.release()
    }
}
