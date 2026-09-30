package dev.slopguard.mutation

import dev.slopguard.core.errors.SlopguardError
import dev.slopguard.core.formatting.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** A planned source file no longer holds the bytes it was planned from. */
class SourceChangedException(val file: File) : RuntimeException("${file.path} changed while mutate was running")

/**
 * Keeps in-place mutation safe. `mutate` edits one source file at a time, and
 * the guard makes sure the original always comes back:
 *
 * - Before a file's first mutant, its bytes and timestamps are kept in memory
 *   and backed up to `original` in the guard directory; before each mutant,
 *   `journal.json` names the file and the mutant's sha256. After the test run
 *   the original bytes and timestamps are written back and the journal is
 *   deleted. Writes truncate the file in place, so inode, mode and hard links
 *   survive.
 * - A `lock` file (exclusive create, holding the pid) allows one run per
 *   project. A lock whose pid is dead belongs to an interrupted run: its
 *   journal restores the file — but only when the file still holds exactly that
 *   mutant, so later edits are never overwritten.
 *
 * The guard directory is `<temp>/slopguard-mutate/<first 16 hex chars of
 * sha256(real project path)>`, never inside the project, and its layout is
 * shared with every slopguard port. Every method is thread-safe, so the
 * shutdown hook can restore while the main thread runs tests.
 */
class WorkspaceGuard private constructor(
    val directory: File,
    /** Plain-sentence notes from recovering an interrupted run. */
    val notes: List<String>,
) {
    private class CurrentFile(val file: File, val original: ByteArray, val modified: FileTime, val accessed: FileTime)

    private val monitor = Any()
    private var current: CurrentFile? = null
    private var mutated = false
    private var released = false
    private var interrupted = false

    /**
     * Write [mutant] into [file], run [body], and restore the original —
     * whatever [body] does. Before every mutant, the file's bytes must still
     * equal [expected] (the bytes the mutants were planned from), so an edit
     * made during the run is never overwritten.
     *
     * @throws SourceChangedException when [file] no longer holds [expected]; nothing was written.
     * @throws MutationInterruptedException after [interrupt].
     */
    fun <T> withMutant(file: File, expected: ByteArray, mutant: ByteArray, body: () -> T): T {
        synchronized(monitor) {
            if (interrupted || released) throw MutationInterruptedException()
            if (current?.file != file) {
                begin(file, expected)
            } else if (readOrNull(file)?.contentEquals(expected) != true) {
                throw SourceChangedException(file)
            }
            val journal = mapOf("file" to file.absolutePath, "mutantSha256" to sha256(mutant))
            writeAtomically(File(directory, JOURNAL), Json.encode(journal).toByteArray())
            mutated = true
            try {
                file.writeBytes(mutant)
            } catch (e: Exception) {
                restoreAfterFailedWrite()
                throw e
            }
        }
        try {
            return body()
        } finally {
            restore()
        }
    }

    /** Put the original back if a mutant is in place. Returns the restored file, or null. */
    fun restore(): File? = synchronized(monitor) { restoreLocked() }

    /**
     * The first step of the signal path: refuse further mutants. Returns the
     * file that holds a mutant right now, or null.
     */
    fun stopMutating(): File? = synchronized(monitor) {
        interrupted = true
        if (mutated) current?.file else null
    }

    /**
     * Restore anything mutated, then delete the journal, backup and lock.
     * Idempotent. When the restore fails, the lock, journal and backup stay, so
     * the next run can recover the file.
     */
    fun release() {
        synchronized(monitor) { releaseLocked() }
    }

    /**
     * The signal path: stop further mutants, restore, release. Returns the file
     * that held a mutant, or null.
     */
    fun interrupt(): File? = synchronized(monitor) {
        interrupted = true
        val restored = restoreLocked()
        releaseLocked()
        restored
    }

    private fun releaseLocked() {
        if (released) return
        restoreLocked()
        released = true
        listOf(JOURNAL, ORIGINAL, LOCK).forEach { File(directory, it).delete() }
        // Best effort: a kept recovery backup, or another run's new lock, keeps the directory.
        directory.delete()
    }

    private fun restoreLocked(): File? {
        val file = current ?: return null
        if (!mutated) return null
        try {
            file.file.writeBytes(file.original)
            Files.getFileAttributeView(file.file.toPath(), BasicFileAttributeView::class.java)
                .setTimes(file.modified, file.accessed, null)
        } catch (e: Exception) {
            throw SlopguardError.restoreFailed(file.file.path, File(directory, ORIGINAL).path, e.message ?: e.toString())
        }
        mutated = false
        File(directory, JOURNAL).delete()
        return file.file
    }

    /**
     * Writing the mutant failed. A write that failed midway left a damaged file,
     * so restore it; a file that could not be opened (read-only) still holds
     * the original, and then there is nothing to restore.
     */
    private fun restoreAfterFailedWrite() {
        try {
            restoreLocked()
        } catch (e: SlopguardError) {
            val file = current ?: throw e
            if (readOrNull(file.file)?.contentEquals(file.original) != true) throw e
            mutated = false
            File(directory, JOURNAL).delete()
        }
    }

    private fun begin(file: File, expected: ByteArray) {
        restoreLocked()
        val attributes = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }
            .getOrNull() ?: throw SourceChangedException(file)
        val original = readOrNull(file)
        if (original == null || !original.contentEquals(expected)) throw SourceChangedException(file)
        val backup = File(directory, ORIGINAL)
        writeAtomically(backup, original)
        // The backup carries the original mtime, so recovery can put it back too.
        backup.setLastModified(attributes.lastModifiedTime().toMillis())
        current = CurrentFile(file, original, attributes.lastModifiedTime(), attributes.lastAccessTime())
    }

    companion object {
        const val LOCK = "lock"
        const val JOURNAL = "journal.json"
        const val ORIGINAL = "original"

        private const val LOCK_ATTEMPTS = 3

        /** `$TMPDIR` when set (what the sibling ports use), else `java.io.tmpdir`. */
        fun defaultTempRoot(): File =
            File(System.getenv("TMPDIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir"))

        /** `<tempRoot>/slopguard-mutate/<first 16 hex chars of sha256(real project path)>`. */
        fun directoryFor(projectRoot: File, tempRoot: File = defaultTempRoot()): File {
            val real = runCatching { projectRoot.toPath().toRealPath().toString() }
                .getOrDefault(projectRoot.absoluteFile.normalize().path)
            return File(File(tempRoot, "slopguard-mutate"), sha256(real.toByteArray()).substring(0, 16))
        }

        /**
         * Take the project's lock, recovering an interrupted run's leftovers first.
         *
         * @throws SlopguardError `mutation_in_progress` when a live process holds the lock.
         */
        fun acquire(
            projectRoot: File,
            tempRoot: File = defaultTempRoot(),
            pid: Long = ProcessHandle.current().pid(),
            isAlive: (Long) -> Boolean = ::processIsAlive,
        ): WorkspaceGuard {
            val directory = directoryFor(projectRoot, tempRoot)
            val lock = File(directory, LOCK)
            if (tryLock(lock, pid)) return WorkspaceGuard(directory, emptyList())

            val holder = readPid(lock)
            if (holder != null && holder != pid && isAlive(holder)) {
                throw SlopguardError.mutationInProgress(holder, projectRoot.path)
            }
            val notes = recoverInterruptedRun(directory)
            lock.delete()
            if (tryLock(lock, pid)) return WorkspaceGuard(directory, notes)
            // Only when another run took the lock between the stale-lock cleanup and the retry.
            throw SlopguardError.mutationInProgress(readPid(lock) ?: 0, projectRoot.path)
        }

        fun processIsAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        /** Create [lock] exclusively. False when it already exists. */
        private fun tryLock(lock: File, pid: Long): Boolean {
            repeat(LOCK_ATTEMPTS) {
                lock.parentFile.mkdirs()
                try {
                    Files.newOutputStream(lock.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        .use { it.write(pid.toString().toByteArray()) }
                    return true
                } catch (e: FileAlreadyExistsException) {
                    return false
                } catch (e: NoSuchFileException) {
                    // Another run's release removed the empty guard directory: create it again.
                }
            }
            throw IOException("Could not create the mutate guard directory ${lock.parentFile.path}")
        }

        private fun readPid(lock: File): Long? =
            readOrNull(lock)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.matches(Regex("\\d+")) }?.toLongOrNull()

        /** Undo what an interrupted run left behind. Returns notes for the report. */
        private fun recoverInterruptedRun(directory: File): List<String> {
            val journalFile = File(directory, JOURNAL)
            val backup = File(directory, ORIGINAL)
            val journal = readJournal(journalFile)
            val notes = if (journal == null) emptyList() else recoverFile(journal, backup, directory)
            journalFile.delete()
            backup.delete()
            return notes
        }

        private fun recoverFile(journal: Journal, backupFile: File, directory: File): List<String> {
            val target = File(journal.file)
            val current = readOrNull(target)
            val backup = readOrNull(backupFile)
            val holdsMutant = current != null && sha256(current) == journal.mutantSha256
            if (backup == null) {
                return if (holdsMutant) {
                    listOf(
                        "An interrupted mutate run left ${journal.file} mutated and its backup is missing. " +
                            "Restore the file from version control.",
                    )
                } else {
                    emptyList()
                }
            }
            if (holdsMutant) {
                target.writeBytes(backup)
                target.setLastModified(backupFile.lastModified())
                return listOf("Restored ${journal.file}, which an interrupted mutate run left mutated.")
            }
            if (current != null && current.contentEquals(backup)) return emptyList()
            val kept = File(directory, "original-${System.currentTimeMillis()}")
            Files.move(backupFile.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return listOf(
                "An interrupted mutate run left a backup of ${journal.file} at ${kept.path}. " +
                    "The file has changed since, so it was not restored.",
            )
        }

        private class Journal(val file: String, val mutantSha256: String)

        /** Reads `{"file": …, "mutantSha256": …}`. A torn or foreign journal carries no usable information. */
        private fun readJournal(journal: File): Journal? {
            val text = readOrNull(journal)?.toString(Charsets.UTF_8) ?: return null
            val file = jsonString(text, "file") ?: return null
            val sha = jsonString(text, "mutantSha256") ?: return null
            return Journal(file, sha)
        }

        /** The string value of top-level [key] in a flat JSON object, unescaped. */
        internal fun jsonString(json: String, key: String): String? {
            val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
            return unescape(match.groupValues[1])
        }

        private fun unescape(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val e = s[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        out.append(s.substring(i + 2, minOf(i + 6, s.length)).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(e)
                }
                i += 2
            }
            return out.toString()
        }

        /** Write via a temp file and rename, so a crash never leaves a half-written file. */
        private fun writeAtomically(target: File, bytes: ByteArray) {
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun readOrNull(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
