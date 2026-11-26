package app.malachite.core.heal

import app.malachite.core.i18n.tr
import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.cover.CoverFinder
import app.malachite.core.fs.SafeFiles
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.LibraryScanner
import app.malachite.core.library.LibraryStore
import app.malachite.core.library.TrackInfo
import app.malachite.core.lyrics.Lrc
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.core.text.TextFold
import java.io.File

/** Turns downloaded image bytes into a cover suitable for embedding (resized JPEG). */
fun interface ImagePrep {
    fun prepare(bytes: ByteArray): TagEdit.Artwork?

    /** [square]: cut to a centred square first (video thumbnails are 16:9 with the cover in the middle). */
    fun prepare(bytes: ByteArray, square: Boolean): TagEdit.Artwork? = prepare(bytes)
}

data class HealProgress(val kind: HealKind, val done: Int, val total: Int, val message: String)

data class HealReport(
    val kind: HealKind,
    val changed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val log: List<String> = emptyList(),
    /** Old relative path -> new relative path. */
    val moves: Map<String, String> = emptyMap(),
    /** Relative path a file had -> where it lies in the trash now (absolute), for undoing. */
    val trashed: Map<String, String> = emptyMap(),
    /** Cover jobs that found no confident match – the user can choose by hand. */
    val coverReview: List<CoverJob> = emptyList(),
    /**
     * Songs without an album whose cover was found through the song itself: the album of that
     * very release, offered in "empty albums" (the cover and the album come from the same record).
     */
    val albumHints: List<TagSuggestion> = emptyList(),
) {
    operator fun plus(o: HealReport) = copy(
        changed = changed + o.changed, skipped = skipped + o.skipped, failed = failed + o.failed,
        log = log + o.log, moves = moves + o.moves, trashed = trashed + o.trashed, coverReview = coverReview + o.coverReview,
        albumHints = albumHints + o.albumHints,
    )
}

/**
 * Executes healing plans one file at a time through [SafeFiles] (which serialises every write
 * app-wide). Every step re-reads the *current* state of the file, so running "names" and
 * "covers" back-to-back – or the user editing a song meanwhile – never mixes stale data.
 */
class HealExecutor(
    private val root: File,
    private val safe: SafeFiles,
    private val store: LibraryStore,
    private val covers: CoverFinder,
    private val lyrics: LyricsFinder,
    private val imagePrep: ImagePrep,
    private val layoutOptions: LibraryLayout.Options = LibraryLayout.Options(),
) {
    private val scanner = LibraryScanner(root, layoutOptions)

    /** The live index, updated after every change. */
    val index = LinkedHashMap<String, TrackInfo>()

    fun load(tracks: List<TrackInfo>) {
        index.clear()
        tracks.forEach { index[it.path] = it }
    }

    /**
     * A file of the main folder. The plans can come from a file other apps could change (an
     * unfinished healing kept on disk): a path leading outside the main folder is refused.
     */
    private fun file(rel: String): File =
        app.malachite.core.fs.PathGuard.child(root, rel) ?: throw java.io.IOException("path outside the main folder: $rel")

    private fun refresh(rel: String) {
        val f = file(rel)
        if (f.isFile) index[rel] = scanner.read(f, rel) else index.remove(rel)
    }

    private fun moved(from: String, to: String) {
        index.remove(from)
        refresh(to)
    }

    private fun relOf(f: File) = f.relativeTo(root).invariantSeparatorsPath

    /**
     * Where an interrupted task stopped (the app was closed or updated): the number of its item
     * loop (from 1) and the item it was on. Earlier loops are skipped, that loop starts at the
     * item (it is done again: it may have been half-done), later loops run whole.
     */
    var resumeAt: Pair<Int, Int>? = null

    /** Item loops started by the task being run, for [resumeAt]. */
    var loopsStarted = 0
        private set

    private inline fun <T> loop(
        kind: HealKind,
        items: List<T>,
        crossinline progress: (HealProgress) -> Unit,
        noinline cancelled: () -> Boolean,
        label: (T) -> String,
        body: (T) -> HealReport,
    ): HealReport {
        var report = HealReport(kind)
        val n = ++loopsStarted
        val from = resumeAt?.let { (loopNo, item) -> if (n < loopNo) items.size else if (n == loopNo) item.coerceIn(0, items.size) else 0 } ?: 0
        for ((i, item) in items.withIndex()) {
            if (i < from) continue
            if (cancelled()) { report = report.copy(log = report.log + tr("Остановлено пользователем")); break }
            progress(HealProgress(kind, i, items.size, label(item)))
            report += try { body(item) } catch (e: Throwable) {
                HealReport(kind, failed = 1, log = listOf(tr("${label(item)}: ошибка ${e.message}")))
            }
        }
        progress(HealProgress(kind, items.size, items.size, tr("Готово")))
        return report
    }

    // ------------------------------------------------------------------ formats

    fun applyFormats(fixes: List<FormatFix>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.FORMATS, fixes, progress, cancelled, { it.path }) { fix ->
            val f = file(fix.path)
            val real = AudioFormat.detect(f)
            if (real != fix.real) return@loop HealReport(HealKind.FORMATS, skipped = 1, log = listOf(tr("${fix.path}: формат изменился, пропущено")))
            when (val res = safe.move(f, file(fix.newPath))) {
                is SafeFiles.Result.Ok -> {
                    val to = relOf(res.file)
                    moved(fix.path, to)
                    // m4a that was dressed up as mp3 often also carries an ID3 tag in front – drop it
                    val stripped = real == AudioFormat.M4A && safe.stripForeignPrefix(res.file, root) is SafeFiles.Result.Ok
                    HealReport(
                        HealKind.FORMATS, changed = 1, moves = mapOf(fix.path to to),
                        log = listOf("${fix.path} → .${real.extension}" + if (stripped) tr(" (убран лишний ID3-заголовок)") else ""),
                    )
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.FORMATS, skipped = 1, log = listOf("${fix.path}: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.FORMATS, failed = 1, log = listOf("${fix.path}: ${res.reason}"))
            }
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ names

    fun applyNames(fixes: List<NameFix>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val chosen = fixes.filter { it.selected && it.canonical.isNotBlank() }
        val artistMap = HashMap<String, String>()
        val albumMap = HashMap<String, String>() // raw album -> canonical (scoped by fix tracks)
        val albumTracks = HashMap<String, Set<String>>()
        val titleMap = HashMap<String, String>() // path -> new title
        for (f in chosen) when (f.field) {
            NameField.ARTIST -> f.variants.forEach { artistMap[it.raw] = f.canonical }
            NameField.ALBUM -> f.variants.forEach { v ->
                albumMap[f.id + "|" + v.raw] = f.canonical
                albumTracks[f.id] = f.tracks.toSet()
            }
            NameField.TITLE -> f.tracks.forEach { titleMap[it] = f.canonical }
        }
        val affected = chosen.flatMap { it.tracks }.distinct()
        val r = loop(HealKind.NAMES, affected, progress, cancelled, { it }) { path ->
            val f = file(path)
            val cur = TagIO.read(f) ?: return@loop HealReport(HealKind.NAMES, failed = 1, log = listOf(tr("$path: не читаются теги")))
            val newArtist = HealPlanner.applyArtist(cur.artist, artistMap)
            val newAlbumArtist = artistMap[cur.albumArtist.trim()]?.takeIf { it != cur.albumArtist }
            val albumFix = chosen.firstOrNull { it.field == NameField.ALBUM && path in (albumTracks[it.id] ?: emptySet()) }
            val newAlbum = albumFix?.let { fx -> albumMap[fx.id + "|" + cur.album.trim()] }?.takeIf { it != cur.album }
            val newTitle = titleMap[path]?.takeIf { it != cur.title }
            val edit = TagEdit(title = newTitle, artist = newArtist, album = newAlbum, albumArtist = newAlbumArtist)
            if (edit.isEmpty) return@loop HealReport(HealKind.NAMES, skipped = 1)
            when (val res = safe.writeTags(f, edit, root)) {
                is SafeFiles.Result.Ok -> {
                    refresh(path)
                    val what = listOfNotNull(
                        newArtist?.let { tr("исполнитель «${cur.artist}» → «$it»") },
                        newAlbumArtist?.let { tr("исполнитель альбома → «$it»") },
                        newAlbum?.let { tr("альбом «${cur.album}» → «$it»") },
                        newTitle?.let { tr("название «${cur.title}» → «$it»") },
                    ).joinToString(", ")
                    HealReport(HealKind.NAMES, changed = 1, log = listOf("$path: $what"))
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.NAMES, skipped = 1, log = listOf("$path: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.NAMES, failed = 1, log = listOf("$path: ${res.reason}"))
            }
        }
        finish(r)
        // Files go to the folder of their corrected artist / album.
        val moves = HealPlanner.layout(affected.mapNotNull { index[it] }, layoutOptions, LibraryLayout.Context(index.values))
        val l = applyLayout(moves, progress, cancelled)
        return r + l.copy(kind = HealKind.NAMES)
    }

    // ------------------------------------------------------------------ not music

    /** Moves non-music files to the trash and remembers their sources so they are never imported again. */
    fun applyJunk(list: List<JunkItem>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val chosen = list.filter { it.selected }
        val ignored = ArrayList<String>()
        val r = loop(HealKind.JUNK, chosen, progress, cancelled, { it.path.substringAfterLast('/') }) { j ->
            when (val res = safe.trash(file(j.path), root)) {
                is SafeFiles.Result.Ok -> {
                    index.remove(j.path)
                    j.sourcePath?.let { ignored += it }
                    HealReport(HealKind.JUNK, changed = 1, log = listOf(tr("${j.path} → корзина (${j.reason})")), trashed = mapOf(j.path to res.file.path))
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.JUNK, skipped = 1, log = listOf("${j.path}: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.JUNK, failed = 1, log = listOf("${j.path}: ${res.reason}"))
            }
        }
        if (ignored.isNotEmpty()) store.updateLibrary { d -> d.copy(ignoredSources = d.ignoredSources + ignored) }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ missing album / artist

    /**
     * Writes the suggested album / artist into files where that tag is still empty, then moves
     * each file into its Artist/Album folder. Formats that cannot hold tags are only moved
     * (the folder then tells the player the album).
     */
    fun applyMissingTags(list: List<TagSuggestion>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val chosen = list.filter { it.selected }
        val r = loop(HealKind.MISSING, chosen, progress, cancelled, { it.path.substringAfterLast('/') }) { sug ->
            val f = file(sug.path)
            if (!f.isFile) return@loop HealReport(HealKind.MISSING, skipped = 1)
            val cur = TagIO.read(f)
            val newAlbum = sug.album?.takeIf { cur == null || cur.album.isBlank() || (sug.overwrite && cur.album != it) }
            val newArtist = sug.artist?.takeIf { cur == null || (cur.artist.isBlank() && cur.albumArtist.isBlank()) || (sug.overwriteArtist && cur.artist != it) }
            // the album artist held the same junk: it follows
            val newAlbumArtist = newArtist?.takeIf {
                sug.overwriteArtist && cur != null && cur.albumArtist.isNotBlank() && cur.albumArtist != it &&
                    // "Разные исполнители" stays the album artist of a real compilation
                    OddTags.artistProblem(cur.albumArtist) != null && !OddTags.isCompilationName(cur.albumArtist)
            }
                // "Разные исполнители" was put there because of the junk album ("AyuGram" looked like a
                // compilation): with the album gone, so is the compilation
                ?: "".takeIf { newAlbum == "" && cur != null && app.malachite.core.cover.AlbumFinder.isCompilation("", cur.albumArtist) }
            // never replace a real year, only an empty one or a download date like 20250227
            val newYear = sug.year?.takeIf { cur == null || LibraryLayout.reliableYear(cur.year) == null }
            // a new title only while the file still holds the one the suggestion was made for
            val newTitle = sug.title?.takeIf { cur != null && cur.title != it && OddTags.splitArtistAndSong(cur.artist, cur.title) != null }
            if (newAlbum == null && newArtist == null && newYear == null && newTitle == null) return@loop HealReport(HealKind.MISSING, skipped = 1)
            val res = safe.writeTags(f, TagEdit(title = newTitle, album = newAlbum, artist = newArtist, albumArtist = newAlbumArtist, year = newYear), root)
            var rep = when (res) {
                is SafeFiles.Result.Ok -> HealReport(HealKind.MISSING, changed = 1, log = listOf(
                    "${sug.path}: " + listOfNotNull(
                        newArtist?.let { if (it.isEmpty()) tr("исполнитель «${cur?.artist}» стёрт") else tr("исполнитель «$it»") },
                        newTitle?.let { tr("название «$it»") }, newAlbum?.let { if (it.isEmpty()) tr("альбом стёрт") else tr("альбом «$it»") },
                        newAlbumArtist?.takeIf { it.isEmpty() }?.let { tr("«${cur?.albumArtist}» убран") },
                        newYear?.let { tr("год $it") + (cur?.year?.takeIf { y -> y.isNotBlank() }?.let { y -> tr(" вместо $y") } ?: "") },
                    ).joinToString(", ") + " (${sug.source})",
                ))
                is SafeFiles.Result.Skipped -> HealReport(HealKind.MISSING, skipped = 1, log = listOf(tr("${sug.path}: ${res.reason} — файл будет только перемещён")))
                is SafeFiles.Result.Failed -> return@loop HealReport(HealKind.MISSING, failed = 1, log = listOf("${sug.path}: ${res.reason}"))
            }
            refresh(sug.path)
            // move into Artist/Album
            val fresh = index[sug.path] ?: return@loop rep
            val target = LibraryLayout.expectedPath(
                fresh.copy(album = sug.album ?: fresh.album, artist = if (fresh.artist.isBlank() || (sug.overwriteArtist && newArtist != null)) sug.artist ?: "" else fresh.artist),
                layoutOptions,
                LibraryLayout.Context(index.values),
            )
            if (target != sug.path) {
                when (val mv = safe.move(f, file(target))) {
                    is SafeFiles.Result.Ok -> {
                        val to = relOf(mv.file)
                        moved(sug.path, to)
                        rep = rep.copy(moves = rep.moves + (sug.path to to), log = rep.log + "${sug.path} → $to")
                    }
                    is SafeFiles.Result.Skipped -> rep = rep.copy(log = rep.log + "${sug.path}: ${mv.reason}")
                    is SafeFiles.Result.Failed -> rep = rep.copy(failed = rep.failed + 1, log = rep.log + "${sug.path}: ${mv.reason}")
                }
            }
            rep
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ recognised by sound

    /**
     * Writes what the songs turned out to be by their sound: the title where the old one meant
     * nothing, the artist and the album where they were missing or junk. Each file is read again
     * first (nothing the user typed meanwhile is overwritten), then moved to its Artist/Album folder
     * under its new name.
     */
    fun applySoundMatches(list: List<SoundMatch>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val chosen = list.filter { it.selected }
        val r = loop(HealKind.SOUND_ID, chosen, progress, cancelled, { it.oldTitle.ifBlank { it.path.substringAfterLast('/') } }) { m ->
            val f = file(m.path)
            if (!f.isFile) return@loop HealReport(HealKind.SOUND_ID, skipped = 1)
            val cur = TagIO.read(f)
            val curTitle = cur?.title.orEmpty()
            val newTitle = m.title?.takeIf { cur == null || curTitle.isBlank() || app.malachite.core.soundid.AcoustId.meaninglessTitle(curTitle) }
            val newArtist = m.artist?.takeIf { cur == null || cur.artist.isBlank() || OddTags.artistProblem(cur.artist, curTitle) != null }
            val newAlbum = m.album?.takeIf { cur == null || cur.album.isBlank() || OddTags.albumProblem(cur.album, curTitle, cur.artist) != null }
            // "Разные исполнители" left as the album artist of a song that is no compilation
            val newAlbumArtist = "".takeIf { newArtist != null && cur != null && OddTags.isCompilationName(cur.albumArtist) && newAlbum != null }
            if (newTitle == null && newArtist == null && newAlbum == null) return@loop HealReport(HealKind.SOUND_ID, skipped = 1)
            var rep = when (val res = safe.writeTags(f, TagEdit(title = newTitle, artist = newArtist, album = newAlbum, albumArtist = newAlbumArtist), root)) {
                is SafeFiles.Result.Ok -> HealReport(HealKind.SOUND_ID, changed = 1, log = listOf(
                    "${m.path}: " + listOfNotNull(
                        newArtist?.let { tr("исполнитель «$it»") }, newTitle?.let { tr("название «$it»") }, newAlbum?.let { tr("альбом «$it»") },
                    ).joinToString(", ") + tr(" (по звуку, совпадение ${(m.score * 100).toInt()}%)"),
                ))
                is SafeFiles.Result.Skipped -> return@loop HealReport(HealKind.SOUND_ID, skipped = 1, log = listOf("${m.path}: ${res.reason}"))
                is SafeFiles.Result.Failed -> return@loop HealReport(HealKind.SOUND_ID, failed = 1, log = listOf("${m.path}: ${res.reason}"))
            }
            refresh(m.path)
            val fresh = index[m.path] ?: return@loop rep
            val target = LibraryLayout.expectedPath(fresh, layoutOptions, LibraryLayout.Context(index.values))
            if (target != m.path) {
                when (val mv = safe.move(f, file(target))) {
                    is SafeFiles.Result.Ok -> {
                        val to = relOf(mv.file)
                        moved(m.path, to)
                        rep = rep.copy(moves = rep.moves + (m.path to to), log = rep.log + "${m.path} → $to")
                    }
                    is SafeFiles.Result.Skipped -> rep = rep.copy(log = rep.log + "${m.path}: ${mv.reason}")
                    is SafeFiles.Result.Failed -> rep = rep.copy(failed = rep.failed + 1, log = rep.log + "${m.path}: ${mv.reason}")
                }
            }
            rep
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ file names

    /**
     * Renames files with meaningless names to the title stored inside them. The title is
     * re-read from the file right before renaming, so a title fixed by "names" in the
     * meantime is respected. The file stays in its folder.
     */
    fun applyFilenames(renames: List<FileRename>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.FILENAMES, renames, progress, cancelled, { it.oldName }) { fr ->
            val f = file(fr.path)
            if (!f.isFile) return@loop HealReport(HealKind.FILENAMES, skipped = 1)
            val fresh = scanner.read(f, fr.path)
            if (fresh.title.isBlank() || HealPlanner.isJunkName(fresh.title)) {
                return@loop HealReport(HealKind.FILENAMES, skipped = 1, log = listOf(tr("${fr.oldName}: внутри файла нет нормального названия")))
            }
            val wanted = LibraryLayout.fileBaseName(fresh, layoutOptions)
            if (f.nameWithoutExtension == wanted) return@loop HealReport(HealKind.FILENAMES, skipped = 1)
            val target = File(f.parentFile, wanted + (if (f.extension.isEmpty()) "" else "." + f.extension))
            when (val res = safe.move(f, target)) {
                is SafeFiles.Result.Ok -> {
                    val to = relOf(res.file)
                    moved(fr.path, to)
                    HealReport(HealKind.FILENAMES, changed = 1, moves = mapOf(fr.path to to), log = listOf("${fr.oldName} → ${res.file.name}"))
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.FILENAMES, skipped = 1, log = listOf("${fr.oldName}: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.FILENAMES, failed = 1, log = listOf("${fr.oldName}: ${res.reason}"))
            }
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ duplicates

    fun applyDuplicates(groups: List<DuplicateGroup>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val chosen = groups.filter { it.selected }
        val r = loop(HealKind.DUPLICATES, chosen, progress, cancelled, { it.keep }) { g ->
            if (!file(g.keep).isFile) return@loop HealReport(HealKind.DUPLICATES, skipped = 1, log = listOf(tr("${g.keep}: оригинал не найден, ничего не удалено")))
            var rep = HealReport(HealKind.DUPLICATES)
            val moves = HashMap<String, String>()
            for (dup in g.remove) {
                when (val res = safe.trash(file(dup), root)) {
                    is SafeFiles.Result.Ok -> {
                        index.remove(dup)
                        moves[dup] = g.keep // favourites / lyrics / playlists now point to the kept copy
                        rep += HealReport(HealKind.DUPLICATES, changed = 1, log = listOf(tr("$dup → корзина (оставлен ${g.keep})")), trashed = mapOf(dup to res.file.path))
                    }
                    is SafeFiles.Result.Skipped -> rep += HealReport(HealKind.DUPLICATES, skipped = 1, log = listOf("$dup: ${res.reason}"))
                    is SafeFiles.Result.Failed -> rep += HealReport(HealKind.DUPLICATES, failed = 1, log = listOf("$dup: ${res.reason}"))
                }
            }
            rep.copy(moves = moves)
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ covers

    /**
     * Embeds covers. Albums with a donor track reuse its cover; others are searched online and
     * applied automatically only when the match is confident – the rest go to [HealReport.coverReview].
     */
    fun applyCovers(jobs: List<CoverJob>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.COVERS, jobs, progress, cancelled, { "${it.artist} — ${it.album.ifBlank { it.title }}" }) { job ->
            var hint: TagSuggestion? = null
            val art: TagEdit.Artwork? = job.imageFile?.let { img ->
                // a picture lying next to the songs: only from inside the main folder, and only
                // one that is a cover (square-ish, not a camera's photo) – checked again here
                File(img).takeIf { app.malachite.core.fs.PathGuard.inside(root, it) && app.malachite.core.cover.ImageFacts.read(it)?.coverLike == true }
                    ?.let { runCatching { it.readBytes() }.getOrNull() }?.let { imagePrep.prepare(it) }
            } ?: job.donor?.let { d ->
                TagIO.readArtwork(file(d))?.let { imagePrep.prepare(it) }
            } ?: run {
                val single = job.tracks.singleOrNull()
                val dur = single?.let { index[it]?.durationMs } ?: 0L
                // the link the downloader left in the file (YouTube…) points at this very recording
                val link = single?.let { runCatching { TagIO.read(file(it))?.comment }.getOrNull() }.orEmpty()
                val best = covers.best(job.artist, job.album, job.title, dur, link) ?: return@loop HealReport(HealKind.COVERS, skipped = 1, coverReview = listOf(job), log = listOf(tr("${job.artist} — ${job.album.ifBlank { job.title }}: нет уверенного совпадения, нужен ручной выбор")))
                hint = albumHint(job, best)
                covers.download(best)?.let { imagePrep.prepare(it, best.square) }
            }
            if (art == null) return@loop HealReport(HealKind.COVERS, failed = 1, log = listOf(tr("${job.album.ifBlank { job.title }}: не удалось загрузить картинку")))
            embedCover(job.tracks, art).let { rep -> if (hint != null) rep.copy(albumHints = listOf(hint!!)) else rep }
        }
        finish(r)
        return r
    }

    /**
     * A cover found through the song names the release it comes from. When the song has no
     * album, that release is its album – unless it is a single named like the song or a
     * compilation ("Лучшее", "Various Artists").
     */
    private fun albumHint(job: CoverJob, c: CoverFinder.Candidate): TagSuggestion? {
        if (job.album.isNotBlank() || !c.note.startsWith(CoverFinder.BY_SONG) || job.tracks.size != 1) return null
        if (c.source == CoverFinder.YOUTUBE) return null
        val album = c.album.trim()
        if (album.isEmpty() || app.malachite.core.cover.AlbumFinder.isCompilation(album)) return null
        if (TextFold.strictKey(album) == TextFold.strictKey(app.malachite.core.text.Watermarks.searchTitle(job.title, job.artist))) return null
        return TagSuggestion(
            job.tracks.single(), album, null,
            tr("альбом той записи, с которой взята обложка (${c.source}, совпадение ${(c.score * 100).toInt()}%)"),
            confident = c.score >= 0.9,
        )
    }

    /** Embeds an already chosen cover into [tracks] (used by manual choice too). */
    fun embedCover(tracks: List<String>, art: TagEdit.Artwork): HealReport {
        var rep = HealReport(HealKind.COVERS)
        for (p in tracks) {
            val f = file(p)
            when (val res = safe.writeTags(f, TagEdit(artwork = art), root)) {
                is SafeFiles.Result.Ok -> { refresh(p); rep += HealReport(HealKind.COVERS, changed = 1, log = listOf(tr("$p: обложка вшита в файл"))) }
                is SafeFiles.Result.Skipped -> rep += HealReport(HealKind.COVERS, skipped = 1, log = listOf("$p: ${res.reason}"))
                is SafeFiles.Result.Failed -> rep += HealReport(HealKind.COVERS, failed = 1, log = listOf("$p: ${res.reason}"))
            }
        }
        return rep
    }

    // ------------------------------------------------------------------ layout

    private fun writeOne(kind: HealKind, path: String, edit: TagEdit, what: String): HealReport =
        when (val res = safe.writeTags(file(path), edit, root)) {
            is SafeFiles.Result.Ok -> { refresh(path); HealReport(kind, changed = 1, log = listOf("$path: $what")) }
            is SafeFiles.Result.Skipped -> HealReport(kind, skipped = 1, log = listOf("$path: ${res.reason}"))
            is SafeFiles.Result.Failed -> HealReport(kind, failed = 1, log = listOf("$path: ${res.reason}"))
        }

    /** "01 - Песня" -> the title "Песня" and, where the tag has none, the track number 1. */
    fun applyTitleNumbers(fixes: List<TitleNumberFix>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.TITLE_NUMBERS, fixes.filter { it.selected }, progress, cancelled, { it.oldTitle }) { f ->
            writeOne(HealKind.TITLE_NUMBERS, f.path, TagEdit(title = f.newTitle, track = f.track?.toString()), tr("«${f.oldTitle}» → «${f.newTitle}»"))
        }
        finish(r)
        return r
    }

    /** Names in normal letters instead of CAPITALS or all small letters. */
    fun applyLetterCase(fixes: List<CaseFix>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val jobs = fixes.filter { it.selected }.flatMap { f -> f.paths.map { f to it } }
        val r = loop(HealKind.LETTER_CASE, jobs, progress, cancelled, { it.first.old }) { (f, path) ->
            val edit = when (f.field) {
                CaseField.TITLE -> TagEdit(title = f.new)
                CaseField.ARTIST -> TagEdit(artist = f.new)
                CaseField.ALBUM -> TagEdit(album = f.new)
            }
            writeOne(HealKind.LETTER_CASE, path, edit, "«${f.old}» → «${f.new}»")
        }
        finish(r)
        return r
    }

    /** Track numbers from the albums' lists of songs. */
    fun applyTrackNumbers(albums: List<AlbumNumbering>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val jobs = albums.filter { it.selected }.flatMap { it.fixes }
        val r = loop(HealKind.TRACK_NUMBERS, jobs, progress, cancelled, { it.title }) { f ->
            writeOne(HealKind.TRACK_NUMBERS, f.path, TagEdit(track = f.track.toString()), tr("номер трека ${f.track}"))
        }
        finish(r)
        return r
    }

    /**
     * Heavy covers are made lighter (the same picture, at most about 1000 px); small ones are
     * replaced by a bigger cover found online – only when a confident match is bigger indeed.
     */
    fun applyCoverQuality(issues: List<CoverIssue>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.COVER_QUALITY, issues.filter { it.selected }, progress, cancelled, { "${it.artist} — ${it.album.ifBlank { it.title }}" }) { issue ->
            val name = issue.album.ifBlank { issue.title }
            if (issue.files.isNotEmpty()) return@loop putAwayPhotos(issue.files)
            if (issue.photo) return@loop replacePhoto(issue, name)
            val art = if (issue.heavy) {
                TagIO.readArtwork(file(issue.paths.first()))?.let { imagePrep.prepare(it) }
            } else {
                val single = issue.paths.singleOrNull()
                val dur = single?.let { index[it]?.durationMs } ?: 0L
                val old = maxOf(issue.width, issue.height)
                covers.best(issue.artist, issue.album, issue.title, dur, "")?.let { best ->
                    covers.download(best)?.let { imagePrep.prepare(it, best.square) }
                }?.takeIf {
                    val side = maxOf(it.width, it.height)
                    // a picture of another album: the album's own cover, unless it is a tiny one; else only a bigger one
                    if (issue.shared) side >= minOf(old, 300) else side > old
                }
            } ?: return@loop HealReport(
                HealKind.COVER_QUALITY, skipped = 1,
                log = listOf(
                    when {
                        issue.heavy -> tr("$name: картинка не читается")
                        issue.shared -> tr("$name: своей обложки альбома не нашлось, осталась общая")
                        else -> tr("$name: обложки лучше не нашлось")
                    },
                ),
            )
            var rep = HealReport(HealKind.COVER_QUALITY)
            val note = if (issue.shared) tr("своя обложка альбома ${art.width}×${art.height} вместо общей на ${issue.sharedBy} альбомов") else tr("обложка ${art.width}×${art.height}")
            for (p in issue.paths) rep += writeOne(HealKind.COVER_QUALITY, p, TagEdit(artwork = art), note)
            rep
        }
        finish(r)
        return r
    }

    /** Personal photos that got into the main folder go to the player's trash (they can be brought back from there). */
    private fun putAwayPhotos(files: List<String>): HealReport {
        var rep = HealReport(HealKind.COVER_QUALITY)
        for (rel in files) {
            val f = File(root, rel)
            if (!app.malachite.core.fs.PathGuard.inside(root, f)) continue
            rep += when (val res = safe.trash(f, root)) {
                is SafeFiles.Result.Ok -> HealReport(HealKind.COVER_QUALITY, changed = 1, log = listOf(tr("$rel: личное фото убрано из главной папки в корзину плеера")))
                is SafeFiles.Result.Skipped -> HealReport(HealKind.COVER_QUALITY, skipped = 1, log = listOf("$rel: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.COVER_QUALITY, failed = 1, log = listOf("$rel: ${res.reason}"))
            }
        }
        return rep
    }

    /**
     * A personal photo inside songs: the album's (or the song's) real cover takes its place when
     * one is found online with confidence; otherwise the picture is just taken out of the files.
     */
    private fun replacePhoto(issue: CoverIssue, name: String): HealReport {
        val single = issue.paths.singleOrNull()
        val dur = single?.let { index[it]?.durationMs } ?: 0L
        val found = runCatching {
            covers.best(issue.artist, issue.album, issue.title, dur, "")?.let { best -> covers.download(best)?.let { imagePrep.prepare(it, best.square) } }
        }.getOrNull()
        var rep = HealReport(HealKind.COVER_QUALITY)
        for (p in issue.paths) {
            rep += if (found != null) writeOne(HealKind.COVER_QUALITY, p, TagEdit(artwork = found), tr("личное фото заменено обложкой ${found.width}×${found.height}"))
            else writeOne(HealKind.COVER_QUALITY, p, TagEdit(removeArtwork = true), tr("личное фото убрано из файла"))
        }
        return rep
    }

    /** Gives every song of the chosen compilations one album artist, so that the album is whole again. */
    fun applyCompilations(fixes: List<CompilationFix>, albumArtist: String, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val paths = fixes.filter { it.selected }.flatMap { it.paths }.distinct()
        val r = loop(HealKind.COMPILATIONS, paths, progress, cancelled, { it.substringAfterLast('/') }) { path ->
            val f = file(path)
            val cur = TagIO.read(f) ?: return@loop HealReport(HealKind.COMPILATIONS, failed = 1, log = listOf(tr("$path: не читаются теги")))
            if (cur.albumArtist.trim() == albumArtist) return@loop HealReport(HealKind.COMPILATIONS, skipped = 1)
            when (val res = safe.writeTags(f, TagEdit(albumArtist = albumArtist), root)) {
                is SafeFiles.Result.Ok -> { refresh(path); HealReport(HealKind.COMPILATIONS, changed = 1, log = listOf(tr("$path: исполнитель альбома → «$albumArtist»"))) }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.COMPILATIONS, skipped = 1, log = listOf("$path: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.COMPILATIONS, failed = 1, log = listOf("$path: ${res.reason}"))
            }
        }
        finish(r)
        return r
    }

    /** Files that cannot be played go to the trash (recoverable). */
    fun applyBroken(list: List<BrokenFile>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.BROKEN, list.filter { it.selected }, progress, cancelled, { it.path.substringAfterLast('/') }) { b ->
            when (val res = safe.trash(file(b.path), root)) {
                is SafeFiles.Result.Ok -> {
                    index.remove(b.path)
                    HealReport(HealKind.BROKEN, changed = 1, log = listOf(tr("${b.path} → корзина (${b.reason})")), trashed = mapOf(b.path to res.file.path))
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.BROKEN, skipped = 1, log = listOf("${b.path}: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.BROKEN, failed = 1, log = listOf("${b.path}: ${res.reason}"))
            }
        }
        finish(r)
        return r
    }

    fun applyLayout(moves: List<LayoutMove>, progress: (HealProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): HealReport {
        val r = loop(HealKind.LAYOUT, moves, progress, cancelled, { it.from }) { m ->
            if (!file(m.from).isFile) return@loop HealReport(HealKind.LAYOUT, skipped = 1)
            when (val res = safe.move(file(m.from), file(m.to))) {
                is SafeFiles.Result.Ok -> {
                    val to = relOf(res.file)
                    moved(m.from, to)
                    HealReport(HealKind.LAYOUT, changed = 1, moves = mapOf(m.from to to), log = listOf("${m.from} → $to"))
                }
                is SafeFiles.Result.Skipped -> HealReport(HealKind.LAYOUT, skipped = 1, log = listOf("${m.from}: ${res.reason}"))
                is SafeFiles.Result.Failed -> HealReport(HealKind.LAYOUT, failed = 1, log = listOf("${m.from}: ${res.reason}"))
            }
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ lyrics

    fun applyLyrics(
        paths: List<String>,
        embedIntoFile: Boolean,
        progress: (HealProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): HealReport {
        val r = loop(HealKind.LYRICS, paths, progress, cancelled, { index[it]?.displayTitle ?: it }) { p ->
            val t = index[p] ?: return@loop HealReport(HealKind.LYRICS, skipped = 1)
            // a song that has words already is here for words in another alphabet: only real words replace them
            val had = store.loadLibrary().user(p).lyricsId != null
            val c = lyrics.best(t.displayTitle, t.displayArtist, t.album, t.durationMs)?.takeIf { !had || it.lyrics.lines.isNotEmpty() }
                ?: return@loop HealReport(
                    HealKind.LYRICS, skipped = 1,
                    log = listOf(if (had) tr("${t.displayTitle}: текст записан другим алфавитом, а настоящего не нашлось") else tr("${t.displayTitle}: текст не найден")),
                )
            if (c.lyrics.lines.isEmpty()) {
                store.updateLibrary { it.update(p) { u -> u.copy(noLyrics = true) } }
                return@loop HealReport(HealKind.LYRICS, skipped = 1, log = listOf(tr("${t.displayTitle}: инструментал")))
            }
            store.saveLyrics(t, c.lyrics, c.source)
            if (embedIntoFile) {
                val text = if (c.lyrics.synced) Lrc.write(c.lyrics) else c.lyrics.plainText
                safe.writeTags(file(p), TagEdit(lyrics = text), root)
                refresh(p)
            }
            HealReport(
                HealKind.LYRICS, changed = 1,
                log = listOf(
                    tr("${t.displayTitle}: ${if (c.synced) tr("синхронизированный") else tr("обычный")} текст (${c.source})") +
                        (if (had) tr(" вместо текста другим алфавитом") else ""),
                ),
            )
        }
        finish(r)
        return r
    }

    // ------------------------------------------------------------------ bookkeeping

    private fun finish(r: HealReport) {
        if (r.moves.isNotEmpty()) store.applyMoves(r.moves, index)
        store.saveIndex(index.values.toList())
    }

    companion object {
        /** Collapses chains a->b, b->c into a->c. */
        fun compose(moves: Map<String, String>): Map<String, String> {
            val out = HashMap<String, String>()
            for ((from, to0) in moves) {
                var to = to0
                var guard = 0
                while (moves.containsKey(to) && moves[to] != to && guard++ < 10) to = moves.getValue(to)
                out[from] = to
            }
            return out
        }
    }
}
