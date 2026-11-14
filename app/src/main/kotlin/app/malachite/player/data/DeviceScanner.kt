package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.AudioPayload
import app.malachite.core.audio.TagIO
import app.malachite.core.fs.SafeFiles
import app.malachite.core.library.FolderHints
import app.malachite.core.library.ImportFilter
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.LibraryScanner
import app.malachite.core.library.TrackInfo
import java.io.File

/**
 * Looks for music on the whole device (outside the main folder) and copies it in.
 * Voice messages, recordings, ringtones and notification sounds are rejected by
 * [ImportFilter]. Uses MediaStore *and* an optional walk over the file system, because
 * MediaStore misses files with a wrong extension or in ".nomedia" folders.
 */
class DeviceScanner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) {
    /**
     * [privilegedSource] is set for files inside other apps' private folders (Android/data):
     * they are copied out with root/Shizuku only at import time.
     */
    data class Candidate(val file: File, val track: TrackInfo, val verdict: ImportFilter.Verdict, val privilegedSource: String? = null)

    /** [already]: songs on the device that are in the library already (they are not offered again). */
    data class Found(val music: List<Candidate>, val rejected: List<Candidate>, val already: Int = 0) {
        val rejectedByReason get() = rejected.groupingBy { it.verdict }.eachCount()
    }

    data class ImportResult(val imported: Int, val skipped: Int, val failed: Int, val deletedOriginals: Int, val errors: List<String>)

    // ------------------------------------------------------------------ one scan / import at a time

    /**
     * Only one search or import may run at a time. Without this an automatic check could start
     * during a long import and copy the same songs a second time ("Песня (2).mp3").
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** Our own copying makes Android's media index change; automatic checks ignore that echo. */
    @Volatile var quietUntil = 0L
        private set

    val isBusy get() = lock.isLocked

    /** Runs [block] exclusively; with [wait] = false returns null instead of waiting (automatic checks). */
    fun <T> exclusive(wait: Boolean, block: () -> T): T? {
        if (wait) lock.lock() else if (!lock.tryLock()) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // Verdicts for files that are not music, so they are not read again on every check.
    private val seenFile = File(context.filesDir, "device_seen.txt")
    private var seen: MutableMap<String, String>? = null

    private fun seenMap(): MutableMap<String, String> = seen ?: HashMap<String, String>().also { m ->
        runCatching {
            if (seenFile.isFile) seenFile.forEachLine { line ->
                val i = line.lastIndexOf('\t')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        seen = m
    }

    private fun saveSeen() {
        val m = seen ?: return
        runCatching { app.malachite.core.library.LibraryStore.writeAtomic(seenFile, m.entries.joinToString("\n") { it.key + "\t" + it.value }) }
    }

    private fun seenKey(f: File, rulesHash: Int) = "${f.length()}|${f.lastModified()}|$rulesHash"

    /** What Android's media index says about a file (album may be just the folder name). */
    data class IndexInfo(val album: String?, val artist: String?)

    @Volatile private var index: Map<String, IndexInfo> = emptyMap()

    /** How the last list was made (for the log): files from the media index, more from the folder walk, a problem. */
    private var listedFromIndex = 0
    private var listedFromWalk = 0
    private var listProblem: String? = null

    /** Paths of audio files outside the main folder. */
    fun listDeviceAudio(deep: Boolean = settings.value.deepScan): List<File> {
        val root = library.root ?: return emptyList()
        val mainCanon = root.canonicalPath
        val out = LinkedHashMap<String, File>()
        listProblem = null
        // 1) MediaStore
        runCatching {
            val cols = mutableListOf(
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.IS_NOTIFICATION, MediaStore.Audio.Media.IS_RINGTONE,
                MediaStore.Audio.Media.IS_ALARM, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST,
            )
            val info = HashMap<String, IndexInfo>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cols += MediaStore.Audio.Media.IS_RECORDING
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(), null, null, null)?.use { c ->
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iN = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
                val iR = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
                val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
                val iRec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) c.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING) else -1
                val iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: continue
                    if (c.getInt(iN) != 0 || c.getInt(iR) != 0 || c.getInt(iA) != 0) continue
                    if (iRec >= 0 && c.getInt(iRec) != 0) continue
                    val f = File(path)
                    if (!f.isFile || f.canonicalPath.startsWith("$mainCanon/")) continue
                    out[f.absolutePath] = f
                    info[f.absolutePath] = IndexInfo(
                        if (iAlbum >= 0) c.getString(iAlbum) else null,
                        if (iArtist >= 0) c.getString(iArtist) else null,
                    )
                }
            }
            index = info
        }.onFailure { e ->
            listProblem = "индекс музыки Android недоступен (${e.javaClass.simpleName})" // i18n:off
            AppLog.w("scan", "Индекс музыки Android недоступен", e)
        }
        listedFromIndex = out.size
        // 2) File system walk (finds misnamed files and folders MediaStore skips)
        if (deep && Storage.hasAllFilesAccess(context)) {
            for (r in Storage.roots(context)) {
                r.walkTopDown()
                    .onEnter { d ->
                        val n = d.name
                        val canon = runCatching { d.canonicalPath }.getOrDefault(d.absolutePath)
                        canon != mainCanon && !(n.startsWith(".") && d != r) &&
                            !(n == "data" && d.parentFile?.name == "Android") && !(n == "obb" && d.parentFile?.name == "Android")
                    }
                    .maxDepth(12)
                    .filter { it.isFile && LibraryScanner.isAudioName(it) }
                    .forEach { out.putIfAbsent(it.absolutePath, it) }
            }
        }
        listedFromWalk = out.size - listedFromIndex
        return out.values.toList()
    }

    /** Classifies everything found on the device (music vs voice/system/etc.). */
    fun find(deep: Boolean = settings.value.deepScan, onProgress: (Int, Int) -> Unit = { _, _ -> }): Found =
        exclusive(wait = true) { findLocked(deep, onProgress) }!!

    /** For automatic checks: returns null if a search / import is already running. */
    fun tryFind(deep: Boolean): Found? = exclusive(wait = false) { findLocked(deep) { _, _ -> } }

    private fun findLocked(deep: Boolean, onProgress: (Int, Int) -> Unit): Found {
        val root = library.root ?: return Found(emptyList(), emptyList()).also { AppLog.w("scan", "Поиск музыки: главная папка не открыта") } // i18n:off
        val data = library.store?.loadLibrary() ?: library.data.value
        val rules = settings.value.importRules
        val rulesHash = rules.hashCode()
        val seenMap = seenMap()
        val all = listDeviceAudio(deep)
        val already = all.count { sourceKey(it) in data.importedSources }
        val files = all.filter { f ->
            val key = sourceKey(f)
            key !in data.importedSources && f.absolutePath !in data.ignoredSources
        }
        val music = ArrayList<Candidate>()
        val rejected = ArrayList<Candidate>()
        for ((i, f) in files.withIndex()) {
            if (i % 10 == 0) onProgress(i, files.size)
            val pre = ImportFilter.checkPath(f.absolutePath, rules, root.absolutePath)
            if (pre != ImportFilter.Verdict.MUSIC) {
                rejected += Candidate(f, TrackInfo(path = f.name), pre)
                continue
            }
            // Known non-music file that did not change: no need to open it again.
            val key = seenKey(f, rulesHash)
            seenMap[f.absolutePath]?.let { v ->
                if (v.startsWith("$key|")) {
                    val verdict = runCatching { ImportFilter.Verdict.valueOf(v.substringAfterLast('|')) }.getOrNull()
                    if (verdict != null && verdict != ImportFilter.Verdict.MUSIC) {
                        rejected += Candidate(f, TrackInfo(path = f.name), verdict); return@let
                    }
                }
            }
            if (rejected.lastOrNull()?.file == f) continue
            val format = AudioFormat.detect(f)
            // Videos (Telegram clips, screen recordings) are rejected before their tags are read.
            if (isVideo(f, format)) {
                rejected += Candidate(f, TrackInfo(path = f.name), ImportFilter.Verdict.VIDEO)
                seenMap[f.absolutePath] = "$key|${ImportFilter.Verdict.VIDEO}"
                continue
            }
            val tags = TagIO.read(f, format)
            val v = ImportFilter.check(f, format, tags, rules, root.absolutePath)
            if (v == ImportFilter.Verdict.MUSIC) {
                music += Candidate(f, describe(f, f.absolutePath, format, tags), v)
            } else {
                rejected += Candidate(f, TrackInfo(path = f.name, durationMs = tags?.durationMs ?: 0), v)
                seenMap[f.absolutePath] = "$key|$v"
            }
        }
        onProgress(files.size, files.size)
        saveSeen()
        findPrivileged(root, data, rules, music, rejected)
        logSearch(deep, files.size, music, rejected)
        return Found(music, rejected, already)
    }

    /**
     * What a search saw, in the log: without it "no songs on a friend's phone" cannot be told apart
     * from no permission, an unreadable media index or music that was rejected as clutter.
     */
    private fun logSearch(deep: Boolean, fresh: Int, music: List<Candidate>, rejected: List<Candidate>) {
        val base = Storage.primaryRoot().absolutePath
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else null
        val lines = ArrayList<String>()
        lines += "Поиск музыки на устройстве: аудиофайлов ${listedFromIndex + listedFromWalk} " + // i18n:off
            "(индекс Android $listedFromIndex, обход папок ${if (deep) listedFromWalk.toString() else "выключен"}), " + // i18n:off
            "новых $fresh, музыка ${music.size}, пропущено ${rejected.size}" // i18n:off
        lines += "    доступ ко всем файлам: ${if (Storage.hasAllFilesAccess(context)) "есть" else "НЕТ"}" + // i18n:off
            (audioPermission?.let { ", разрешение «Музыка и аудио»: ${if (it) "есть" else "нет"}" } ?: "") + // i18n:off
            ", главная папка: ${library.root?.absolutePath ?: "нет"}" + (listProblem?.let { ", $it" } ?: "") // i18n:off
        if (rejected.isNotEmpty()) {
            lines += "    пропущено: " + rejected.groupingBy { it.verdict }.eachCount().entries // i18n:off
                .sortedByDescending { it.value }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }
            rejected.groupingBy { (it.privilegedSource ?: it.file.parent ?: "").removePrefix(base) to it.verdict }.eachCount().entries
                .sortedByDescending { it.value }.take(12)
                .forEach { (k, n) -> lines += "      ${k.first.ifEmpty { "/" }} — ${k.second.name.lowercase()}: $n" }
        }
        if (music.isNotEmpty()) {
            music.groupingBy { (it.privilegedSource?.substringBeforeLast('/') ?: it.file.parent ?: "").removePrefix(base) }.eachCount().entries
                .sortedByDescending { it.value }.take(8)
                .forEach { (k, n) -> lines += "      музыка: ${k.ifEmpty { "/" }} — $n" } // i18n:off
        }
        AppLog.i("scan", lines.joinToString("\n"))
    }

    /** A real video (more than a cover frame), checked without reading the whole file. */
    private fun isVideo(f: File, format: AudioFormat): Boolean {
        val ext = f.extension.lowercase()
        if (format == AudioFormat.M4A || format == AudioFormat.THREE_GP) return app.malachite.core.audio.Mp4Probe.isVideo(f)
        if (format == AudioFormat.MATROSKA || ext in AudioFormat.VIDEO_CAPABLE) return AndroidTags.hasVideo(f)
        return false
    }

    /** Android/data via root / Shizuku – classified by folder and name only (files are not readable yet). */
    private fun findPrivileged(
        root: File,
        data: app.malachite.core.library.LibraryData,
        rules: ImportFilter.Rules,
        music: MutableList<Candidate>,
        rejected: MutableList<Candidate>,
    ) {
        val s = settings.value
        if (!s.scanAppData || !Privileged.isUsable(s.privilegedMode)) return
        val paths = runCatching { Privileged.findInAppData(s.privilegedMode, Storage.primaryRoot()) }.getOrDefault(emptyList())
        for (p in paths) {
            if (data.importedSources.keys.any { it.startsWith("$p|") } || p in data.ignoredSources) continue
            val f = File(p)
            // The user enabled this to get e.g. "Telegram Audio": messenger folders are allowed here,
            // but cache-like file names are still not music.
            val v = if (ImportFilter.isMessengerName(f.name)) ImportFilter.Verdict.MESSENGER
            else ImportFilter.checkPath(p.replace("/Android/data/", "/AppData/"), rules.copy(importFromMessengers = true), root.absolutePath)
            val t = TrackInfo(path = f.name, title = f.nameWithoutExtension, format = AudioFormat.fromExtension(f.extension).name)
            if (v == ImportFilter.Verdict.MUSIC) music += Candidate(f, t, v, p) else rejected += Candidate(f, t, v, p)
        }
    }

    private fun sourceKey(f: File) = f.absolutePath + "|" + f.length()

    private fun String?.real(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }

    /**
     * Where the song belongs. Tags are read by our parser first; empty fields are completed from
     * Android's own reader, from the media index (what Mi Music and other players show) and from
     * the folders the file lies in (".../Boris_Kuzmin/Время выткало нить/…"). Nothing is written
     * into the file here – healing ("Пустые альбомы и исполнители") does that later on request.
     */
    fun describe(f: File, originalPath: String, format: AudioFormat, tags: app.malachite.core.audio.TagData?): TrackInfo {
        val needMore = tags == null || tags.album.isBlank() || (tags.artist.isBlank() && tags.albumArtist.isBlank()) || tags.title.isBlank()
        val android = if (needMore) AndroidTags.read(f) else null
        val idx = index[originalPath]
        val parentName = File(originalPath).parentFile?.name.orEmpty()

        var artist = tags?.artist.real() ?: android?.artist.real() ?: idx?.artist.real()
        val albumArtist = tags?.albumArtist.real() ?: android?.albumArtist.real()
        // YouTube downloads: artist tag = uploader ("icarus"), real "Artist - Title" in the title.
        // Trusted only when that artist is a folder on the way to the file or already in the library.
        var ytTitle: String? = null
        FolderHints.fromYoutubeTitle(tags?.title, tags?.comment)?.let { (a, t) ->
            val folders = FolderHints.folders(originalPath)
            val known = folders.any { app.malachite.core.text.Similarity.sameName(FolderHints.cleanFolderName(it), a) != null } ||
                library.artists.value.any { app.malachite.core.text.Similarity.sameName(it.name, a) != null }
            if (known) { artist = a; ytTitle = t }
        }
        // The media index puts the folder name into "album" when the file has none – that case is
        // handled by the folder logic below, which also checks the artist folder above it.
        var album = tags?.album.real() ?: android?.album.real() ?: idx?.album.real()?.takeIf { it != parentName }
        if (album == null || artist == null && albumArtist == null) {
            val hint = FolderHints.fromPath(originalPath, artist ?: albumArtist)
            val useFolders = settings.value.albumFromFolders
            if (hint != null && (hint.confident || useFolders)) {
                if (album == null) album = hint.album
                if (artist == null && albumArtist == null) artist = hint.artist
            }
        }
        if (artist == null && albumArtist == null) artist = FolderHints.artistFromFileName(f.name)
        val rawTitle = ytTitle ?: tags?.title.real() ?: android?.title.real() ?: FolderHints.titleFromFileName(File(originalPath).name, artist ?: albumArtist)
        // "Свидетельство О Смерти — Подводная лодка" -> "Подводная лодка" (for the file name; the tag is fixed by healing)
        val title = FolderHints.stripArtistPrefix(rawTitle, artist ?: albumArtist)
        return TrackInfo(
            path = f.name,
            title = title,
            artist = artist.orEmpty(),
            album = album.orEmpty(),
            albumArtist = albumArtist.orEmpty(),
            // the year decides the "Album (1994)" folder; a download date like 20250227 does not count,
            // the "Released on: …" line of a YouTube description does
            year = tags?.let { tg -> if (LibraryLayout.reliableYear(tg.year) == null && tg.releaseYear.isNotEmpty()) tg.releaseYear else tg.year }
                ?: android?.year.orEmpty(),
            track = tags?.track?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            durationMs = tags?.durationMs ?: 0,
            size = f.length(),
            format = format.name,
            hasArtwork = tags?.hasArtwork ?: false,
        )
    }

    /**
     * Copies [candidates] into Artist/Album folders (verified byte-for-byte). When
     * "full move" is enabled, the originals are deleted afterwards.
     */
    fun import(candidates: List<Candidate>, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): ImportResult =
        exclusive(wait = true) {
            try {
                importLocked(candidates, onProgress)
            } finally {
                quietUntil = System.currentTimeMillis() + 120_000
            }
        }!!

    private fun importLocked(candidates: List<Candidate>, onProgress: (Int, Int, String) -> Unit): ImportResult {
        val root = library.root ?: return ImportResult(0, 0, 0, 0, listOf(tr("Нет главной папки")))
        val safe = library.safe ?: return ImportResult(0, 0, 0, 0, listOf(tr("Нет главной папки")))
        val store = library.store ?: return ImportResult(0, 0, 0, 0, listOf(tr("Нет главной папки")))
        val opts = settings.value.layoutOptions()
        var imported = 0; var skipped = 0; var failed = 0; var deleted = 0
        val errors = ArrayList<String>()
        val touched = ArrayList<File>()
        val mode = settings.value.privilegedMode
        // Fresh from disk: an automatic check may have imported some of these meanwhile.
        val already = store.loadLibrary().importedSources.keys
        // Album years, singles and known artists are judged together with what is already in the library.
        val context = LibraryLayout.Context(library.tracks.value + candidates.map { it.track })
        for ((i, c0) in candidates.withIndex()) {
            onProgress(i, candidates.size, c0.file.name)
            val key0 = if (c0.privilegedSource != null) null else sourceKey(c0.file)
            if (key0 != null && key0 in already) { skipped++; continue }
            if (c0.privilegedSource != null && already.any { it.startsWith(c0.privilegedSource + "|") }) { skipped++; continue }
            // Files from other apps' private folders are first copied out with root / Shizuku.
            var staged: File? = null
            val c = if (c0.privilegedSource != null) {
                val dest = File(store.dataDir, "incoming/" + System.nanoTime() + "_" + c0.file.name)
                if (!Privileged.copyOut(mode, c0.privilegedSource, dest)) { failed++; errors += tr("${c0.file.name}: не удалось скопировать из закрытой папки"); continue }
                staged = dest
                val format = AudioFormat.detect(dest)
                c0.copy(file = dest, track = describe(dest, c0.privilegedSource, format, TagIO.read(dest, format)))
            } else c0
            if (!c.file.isFile) { skipped++; continue }
            val title = c.track.title.ifBlank { FolderHints.titleFromFileName(c0.file.name, c.track.artist) }
            val rel = LibraryLayout.expectedPath(c.track.copy(path = c.file.name, title = title), opts, context)
            val sourceFile = c0.privilegedSource?.let { File(it) } ?: c.file
            val res = safe.copyInto(c.file, File(root, rel))
            staged?.delete()
            when (res) {
                is SafeFiles.Result.Ok -> {
                    imported++
                    val newRel = res.file.relativeTo(root).invariantSeparatorsPath
                    val key = if (c0.privilegedSource != null) c0.privilegedSource + "|" + res.file.length() else sourceKey(c.file)
                    store.updateLibrary {
                        it.copy(importedSources = it.importedSources + (key to newRel))
                            .update(newRel) { u -> u.copy(addedAt = System.currentTimeMillis(), importedFrom = sourceFile.absolutePath) }
                    }
                    touched += res.file
                    if (c0.privilegedSource == null) copyAlbumCover(c.file.parentFile, res.file.parentFile, c.track.album)
                    // Read the switch for every file: turning it off stops deletions immediately, even mid-import.
                    if (settings.value.deleteOriginals) {
                        val ok = if (c0.privilegedSource != null) Privileged.delete(mode, c0.privilegedSource) else safe.delete(c.file).isOk
                        if (ok) { deleted++; touched += sourceFile } else errors += tr("Не удалось удалить оригинал ${c0.file.name}")
                    }
                }
                is SafeFiles.Result.Skipped -> skipped++
                is SafeFiles.Result.Failed -> { failed++; errors += "${c.file.name}: ${res.reason}" }
            }
        }
        onProgress(candidates.size, candidates.size, "")
        library.notifySystem(touched)
        library.reloadData()
        AppLog.i("import", "Собрано $imported, пропущено $skipped, ошибок $failed, оригиналов удалено $deleted" +
            if (errors.isEmpty()) "" else "\n" + errors.take(100).joinToString("\n") { "    $it" })
        return ImportResult(imported, skipped, failed, deleted, errors)
    }

    /**
     * The cover picture of an album folder ("cover.jpg", "6439b2e9….1000x1000x1.jpg",
     * "ВремяВыткалоНить.jpeg") is copied along as "cover.<ext>" – other players show it too,
     * and healing embeds it into the files without internet.
     */
    private fun copyAlbumCover(srcDir: File?, dstDir: File?, album: String) {
        if (srcDir == null || dstDir == null || album.isBlank()) return
        if (dstDir.listFiles { f -> f.name.lowercase().startsWith("cover.") }?.isNotEmpty() == true) return
        // only from a folder that is this album's folder
        if (app.malachite.core.text.TextFold.strictKey(FolderHints.cleanFolderName(srcDir.name)) != app.malachite.core.text.TextFold.strictKey(album)) return
        val img = FolderHints.coverImageIn(srcDir, null, album) ?: return
        runCatching { library.safe?.copyInto(img, File(dstDir, "cover." + img.extension.lowercase())) }
    }

    /** How many originals of already imported songs still exist outside the main folder. */
    fun countImportedOriginals(): Int = library.data.value.importedSources.keys.count { k ->
        val path = k.substringBeforeLast('|')
        !path.contains("/Android/data/") && File(path).isFile
    }

    /** Deletes originals of songs that were imported earlier (when "full move" is turned on later). */
    fun deleteImportedOriginals(): Int {
        val safe = library.safe ?: return 0
        var n = 0
        val touched = ArrayList<File>()
        for ((key, rel) in library.data.value.importedSources) {
            if (!settings.value.deleteOriginals) break // revoked meanwhile
            val path = key.substringBeforeLast('|')
            val f = File(path)
            val copy = library.file(rel)
            // only when the copy inside the main folder still exists and holds the same audio
            if (f.isFile && copy != null && copy.isFile && sameAudio(f, copy)) {
                if (safe.delete(f).isOk) { n++; touched += f }
            }
        }
        library.notifySystem(touched)
        return n
    }

    private fun sameAudio(a: File, b: File): Boolean {
        if (a.length() == b.length()) return true
        val fa = runCatching { AudioPayload.fingerprint(a) }.getOrNull() ?: return false
        return fa == runCatching { AudioPayload.fingerprint(b) }.getOrNull()
    }

    fun ignore(files: List<File>) {
        library.store?.updateLibrary { d -> d.copy(ignoredSources = d.ignoredSources + files.map { it.absolutePath }) }
        library.reloadData()
    }
}
