package app.malachite.core.fs

import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.AudioPayload
import app.malachite.core.audio.TagData
import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The only gate through which the app modifies, moves or deletes music files.
 *
 * Guarantees:
 *  1. One change at a time (a single global lock) – two healers can never write the same file
 *     simultaneously, and every change re-reads the file's *current* state.
 *  2. Changes are made on a copy. The copy is checked (format, tags, duration and a hash of the
 *     audio data that must be identical to the original's) before it replaces the original.
 *  3. Replacement is two renames recorded in a journal, so a crash or a dead battery at any
 *     moment can be recovered on the next start ([recover]).
 *  4. Optionally the original is kept in a backup folder until the user clears backups.
 */
class SafeFiles(
    /** `<main>/.malachite` */
    private val dataDir: File,
) {
    private val workDir = File(dataDir, "work").apply { mkdirs() }
    private val journalDir = File(dataDir, "journal").apply { mkdirs() }
    val backupRoot = File(dataDir, "backup")
    private val trashRoot = File(dataDir, "trash")

    /** [f] is a file in this folder's trash (paths read back from undo records are checked with it). */
    fun inTrash(f: File): Boolean = PathGuard.inside(trashRoot, f)

    /** [f] is in this folder's backups of original files. */
    fun inBackups(f: File): Boolean = PathGuard.inside(backupRoot, f)

    /** Folder used for backups of the current healing session; null = do not keep originals. */
    @Volatile
    var backupSession: File? = null

    fun startBackupSession(label: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(backupRoot, "${stamp}_$label").also { it.mkdirs(); backupSession = it }
    }

    sealed class Result {
        data class Ok(val file: File, val detail: String = "") : Result()
        data class Skipped(val reason: String) : Result()
        data class Failed(val reason: String) : Result()

        val isOk get() = this is Ok
    }

    @Serializable
    private data class JournalEntry(val target: String, val work: String, val old: String, val keepOld: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- tags & covers

    /**
     * Applies [edit] to the music file [file]. The [mainRoot] is used to keep a backup copy
     * under the same relative path.
     */
    fun writeTags(file: File, edit: TagEdit, mainRoot: File? = null): Result =
        if (edit.isEmpty) Result.Skipped("nothing to change") else rewrite(file, edit, mainRoot)

    /**
     * Removes an ID3 tag glued in front of an M4A file (left by apps that took it for MP3).
     * Players that sniff the container may refuse such files; the music itself stays identical.
     */
    fun stripForeignPrefix(file: File, mainRoot: File? = null): Result {
        if (AudioFormat.detect(file) != AudioFormat.M4A || TagIO.id3PrefixLength(file) == 0L) return Result.Skipped("nothing to strip")
        return rewrite(file, TagEdit(), mainRoot)
    }

    private fun rewrite(file: File, edit: TagEdit, mainRoot: File?): Result { return GATE.withLock {
        if (!file.isFile) return Result.Failed("file not found")
        if (!file.canWrite()) return Result.Failed("no write permission")
        val format = AudioFormat.detect(file)
        if (!format.taggable) return Result.Skipped("format ${format.name} cannot hold tags safely")
        if (!AudioFormat.extensionMatches(file.extension, format)) {
            return Result.Skipped("extension .${file.extension} does not match real format ${format.extension}; fix the format first")
        }
        val before = TagIO.read(file, format) ?: return Result.Failed("could not read existing tags")
        val fpBefore = runCatching { AudioPayload.fingerprint(file, format) }.getOrNull()
        val work = File(workDir, UUID.randomUUID().toString() + "." + format.extension)
        try {
            val prefix = if (format == AudioFormat.M4A) TagIO.id3PrefixLength(file) else 0L
            if (prefix > 0) {
                // the glued-on ID3 tag is dropped on the way: the result is a clean M4A file
                TagIO.copyFrom(file, prefix, work)
            } else copyVerified(file, work)
            TagIO.writeInPlace(work, format, edit)
            val problem = verify(work, format, before, fpBefore, edit)
            if (problem != null) return Result.Failed(problem)
            replace(file, work, mainRoot)
            return Result.Ok(file)
        } catch (e: Throwable) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (work.exists()) work.delete()
        }
    } }

    private fun verify(work: File, format: AudioFormat, before: TagData, fpBefore: String?, edit: TagEdit): String? {
        if (AudioFormat.detect(work) != format) return "format changed after writing"
        val after = TagIO.read(work, format) ?: return "tags unreadable after writing"
        if (fpBefore != null) {
            val fpAfter = AudioPayload.fingerprint(work, format)
            if (fpAfter != fpBefore) return "audio data would change – refused"
        }
        val tolerance = maxOf(600L, before.durationMs / 100)
        if (before.durationMs > 0 && kotlin.math.abs(after.durationMs - before.durationMs) > tolerance) {
            return "duration changed (${before.durationMs} -> ${after.durationMs} ms)"
        }
        fun check(name: String, expected: String?, actual: String): String? =
            if (expected != null && expected.trim() != actual.trim()) "$name not stored correctly" else null
        check("title", edit.title, after.title)?.let { return it }
        check("artist", edit.artist, after.artist)?.let { return it }
        check("album", edit.album, after.album)?.let { return it }
        check("album artist", edit.albumArtist, after.albumArtist)?.let { return it }
        if (edit.artwork != null) {
            val art = TagIO.readArtwork(work, format) ?: return "cover not stored"
            if (!art.contentEquals(edit.artwork.bytes)) return "cover stored incorrectly"
        }
        return null
    }

    // ---------------------------------------------------------------- moving / renaming

    /**
     * Moves [src] to [dst] (creating folders). If [dst] exists, a free name "name (2).ext" is used.
     * Falls back to verified copy+delete when a rename is impossible (e.g. SD card).
     */
    fun move(src: File, dst: File): Result { return GATE.withLock {
        if (!src.isFile) return Result.Failed("file not found")
        if (src.canonicalPath == dst.canonicalPath) return Result.Ok(dst)
        val target = freeName(dst, src)
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) {
            carryCover(src.parentFile, target.parentFile)
            cleanupEmptyDirs(src.parentFile)
            return Result.Ok(target)
        }
        return try {
            val tmp = File(target.parentFile, ".${target.name}.part")
            copyVerified(src, tmp)
            if (!tmp.renameTo(target)) { tmp.delete(); return Result.Failed("could not create ${target.name}") }
            if (!src.delete()) return Result.Ok(target, "copied, but the original could not be deleted")
            cleanupEmptyDirs(src.parentFile)
            Result.Ok(target)
        } catch (e: IOException) {
            Result.Failed(e.message ?: "copy failed")
        }
    } }

    /** Copies [src] into the library at [dst] (verified), leaving [src] untouched. */
    fun copyInto(src: File, dst: File): Result { return GATE.withLock {
        if (!src.isFile) return Result.Failed("file not found")
        val target = freeName(dst, null)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.part")
        try {
            copyVerified(src, tmp)
            if (!tmp.renameTo(target)) { tmp.delete(); return Result.Failed("could not create ${target.name}") }
            target.setLastModified(src.lastModified())
            Result.Ok(target)
        } catch (e: IOException) {
            tmp.delete()
            Result.Failed(e.message ?: "copy failed")
        }
    } }

    /** Moves a file into the app trash (recoverable) instead of deleting it. */
    fun trash(file: File, mainRoot: File?): Result { return GATE.withLock {
        if (!file.exists()) return Result.Skipped("already gone")
        val rel = mainRoot?.let { relativeOrNull(file, it) } ?: file.name
        val dst = freeName(File(File(trashRoot, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())), rel), null)
        dst.parentFile?.mkdirs()
        if (file.renameTo(dst)) {
            cleanupEmptyDirs(file.parentFile)
            Result.Ok(dst)
        } else {
            // Different storage (file outside main folder): delete only after a verified copy.
            try {
                copyVerified(file, dst)
                if (file.delete()) Result.Ok(dst) else Result.Failed("could not delete original")
            } catch (e: IOException) {
                Result.Failed(e.message ?: "copy failed")
            }
        }
    } }

    /** Deletes a file outside the library (after migration). */
    fun delete(file: File): Result { return GATE.withLock {
        if (!file.exists()) return Result.Skipped("already gone")
        if (file.delete()) { cleanupEmptyDirs(file.parentFile); Result.Ok(file) } else Result.Failed("delete failed")
    } }

    /** When the last song leaves a folder, its "cover.*" goes along (or is dropped if the target has one). */
    private fun carryCover(from: File?, to: File?) {
        if (from == null || to == null || from.canonicalPath == to.canonicalPath) return
        val left = from.listFiles() ?: return
        if (left.any { it.isFile && it.extension.lowercase() in app.malachite.core.audio.AudioFormat.SCAN_EXTENSIONS }) return
        for (c in left.filter { it.isFile && it.name.lowercase().startsWith("cover.") }) {
            val dst = File(to, c.name)
            if (to.listFiles { f -> f.name.lowercase().startsWith("cover.") }?.isNotEmpty() == true) c.delete()
            else if (!c.renameTo(dst)) c.delete()
        }
    }

    private fun freeName(dst: File, self: File?): File {
        if (!dst.exists() || (self != null && dst.canonicalPath == self.canonicalPath)) return dst
        val base = dst.nameWithoutExtension
        val ext = dst.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            val f = File(dst.parentFile, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private var protectedDirs: Set<String> = emptySet()

    /** Folders that must never be removed even when empty (main folder, data folder). */
    fun protect(vararg dirs: File) {
        protectedDirs = dirs.map { it.canonicalPath }.toSet()
    }

    fun cleanupEmptyDirs(start: File?) {
        var d = start
        repeat(3) {
            val dir = d ?: return
            if (dir.canonicalPath in protectedDirs) return
            val children = dir.list() ?: return
            if (children.isNotEmpty()) return
            if (!dir.delete()) return
            d = dir.parentFile
        }
    }

    // ---------------------------------------------------------------- replacement + journal

    private fun replace(target: File, work: File, mainRoot: File?) {
        val session = backupSession
        val keepOld = session != null
        val old = if (session != null) {
            val rel = mainRoot?.let { relativeOrNull(target, it) } ?: target.name
            freeName(File(session, rel), null).also { it.parentFile?.mkdirs() }
        } else File(target.parentFile, ".${target.name}.malachite-old")
        val entry = File(journalDir, UUID.randomUUID().toString() + ".json")
        entry.writeText(json.encodeToString(JournalEntry.serializer(), JournalEntry(target.path, work.path, old.path, keepOld)))
        try {
            if (!target.renameTo(old)) {
                // Backup on another storage: copy it instead, then replace in-place.
                if (keepOld) copyVerified(target, old)
                val inPlaceOld = File(target.parentFile, ".${target.name}.malachite-old")
                if (!target.renameTo(inPlaceOld)) throw IOException("could not move the original aside")
                if (!work.renameTo(target)) {
                    copyVerified(work, target)
                }
                inPlaceOld.delete()
            } else {
                if (!work.renameTo(target)) {
                    try {
                        copyVerified(work, target)
                    } catch (e: IOException) {
                        // put the original back
                        target.delete()
                        old.renameTo(target)
                        throw e
                    }
                }
                if (!keepOld) old.delete()
            }
        } finally {
            entry.delete()
        }
    }

    /** Finishes or rolls back operations interrupted by a crash. Call on app start. */
    fun recover(): Int { return GATE.withLock {
        var fixed = 0
        journalDir.listFiles()?.forEach { f ->
            val e = runCatching { json.decodeFromString(JournalEntry.serializer(), f.readText()) }.getOrNull()
            if (e != null) {
                val target = File(e.target)
                val work = File(e.work)
                val old = File(e.old)
                val inPlaceOld = File(target.parentFile, ".${target.name}.malachite-old")
                when {
                    target.exists() -> {
                        if (!e.keepOld && old.exists() && old != target) old.delete()
                        if (inPlaceOld.exists()) inPlaceOld.delete()
                    }
                    work.exists() -> { if (work.renameTo(target)) fixed++ }
                    old.exists() -> { if (old.renameTo(target)) fixed++ }
                    inPlaceOld.exists() -> { if (inPlaceOld.renameTo(target)) fixed++ }
                }
                if (work.exists()) work.delete()
            }
            f.delete()
        }
        workDir.listFiles()?.forEach { it.delete() }
        fixed
    } }

    fun clearBackups(): Long {
        val size = sizeOf(backupRoot)
        backupRoot.deleteRecursively()
        backupSession = null
        return size
    }

    fun clearTrash(): Long {
        val size = sizeOf(trashRoot)
        trashRoot.deleteRecursively()
        return size
    }

    fun backupSize() = sizeOf(backupRoot)
    fun trashSize() = sizeOf(trashRoot)

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** One global gate for all modifications of music files. */
        val GATE = ReentrantLock(true)

        fun relativeOrNull(file: File, root: File): String? {
            val f = file.canonicalPath
            val r = root.canonicalPath.trimEnd('/') + "/"
            return if (f.startsWith(r)) f.removePrefix(r) else null
        }

        /** Copies with fsync and verifies length + SHA-256 of both sides. */
        fun copyVerified(src: File, dst: File) {
            val mdSrc = MessageDigest.getInstance("SHA-256")
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        mdSrc.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (dst.length() != src.length()) { dst.delete(); throw IOException("copy size mismatch") }
            if (!sha256(dst).contentEquals(mdSrc.digest())) { dst.delete(); throw IOException("copy checksum mismatch") }
        }

        fun sha256(f: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
    }
}
