package app.malachite.player.heal

import app.malachite.core.i18n.tr
import android.content.Context
import app.malachite.core.audio.AudioPayload
import app.malachite.core.audio.TagEdit
import app.malachite.core.cover.CoverFinder
import app.malachite.core.heal.CoverJob
import app.malachite.core.heal.DuplicateGroup
import app.malachite.core.heal.FileRename
import app.malachite.core.heal.FormatFix
import app.malachite.core.heal.HealExecutor
import app.malachite.core.heal.HealKind
import app.malachite.core.heal.HealPlanner
import app.malachite.core.heal.HealProgress
import app.malachite.core.heal.HealReport
import app.malachite.core.heal.LayoutMove
import app.malachite.core.heal.JunkItem
import app.malachite.core.heal.NameFix
import app.malachite.core.heal.TagSuggestion
import app.malachite.core.library.LibraryLayout
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.player.data.AndroidImagePrep
import app.malachite.player.data.AndroidTags
import app.malachite.player.data.layoutOptions
import app.malachite.player.data.CoverLoader
import app.malachite.player.data.LibraryRepository
import app.malachite.player.data.SettingsRepository
import app.malachite.player.service.LongTaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Everything the healing screen shows. */
data class HealState(
    val analyzing: HealKind? = null,
    val running: HealKind? = null,
    val queued: List<HealKind> = emptyList(),
    val progress: HealProgress? = null,
    val formats: List<FormatFix>? = null,
    val junk: List<JunkItem>? = null,
    val missing: List<TagSuggestion>? = null,
    val names: List<NameFix>? = null,
    val filenames: List<FileRename>? = null,
    val duplicates: List<DuplicateGroup>? = null,
    val covers: List<CoverJob>? = null,
    val layout: List<LayoutMove>? = null,
    val lyrics: List<String>? = null,
    val compilations: List<app.malachite.core.heal.CompilationFix>? = null,
    val broken: List<app.malachite.core.heal.BrokenFile>? = null,
    val coverIssues: List<app.malachite.core.heal.CoverIssue>? = null,
    val numbering: List<app.malachite.core.heal.AlbumNumbering>? = null,
    val titleNumbers: List<app.malachite.core.heal.TitleNumberFix>? = null,
    val letterCase: List<app.malachite.core.heal.CaseFix>? = null,
    /** Songs whose loudness is not measured yet. */
    val loudness: List<String>? = null,
    val reports: List<HealReport> = emptyList(),
    val coverReview: List<CoverJob> = emptyList(),
    val message: String? = null,
    /** Tasks that were not finished when the app was closed or updated: they can go on. */
    val interrupted: List<HealKind> = emptyList(),
    /** Songs recognised by their sound, waiting to be ticked (the sure ones are pre-ticked). */
    val soundIds: List<app.malachite.core.heal.SoundMatch>? = null,
    /** Recognition by sound was wanted, but there is no AcoustID key in the settings. */
    val soundNoKey: Boolean = false,
    /** The AcoustID key did not work (shown once in a window). */
    val soundKeyProblem: app.malachite.core.soundid.AcoustId.KeyTrouble? = null,
    val soundKeyDetail: String = "",
)

/**
 * Runs healing jobs strictly one after another (a queue). Even if the user starts
 * "heal names" and "heal covers" at the same moment, the second waits for the first, and
 * every file change also goes through the single global gate in SafeFiles – two healers can
 * never write to the same file at the same time.
 */
class HealingEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val coverFinder: CoverFinder,
    private val lyricsFinder: LyricsFinder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Albums learned from covers found through the song (path -> suggestion). */
    private val albumHints = java.util.concurrent.ConcurrentHashMap<String, TagSuggestion>()

    /** Empty-album plan plus what cover search learned about songs without an album. */
    private fun missingPlan(tracks: List<app.malachite.core.library.TrackInfo>, data: app.malachite.core.library.LibraryData): List<TagSuggestion> {
        val plan = HealPlanner.missingTags(tracks, data) { androidTags(it) }
        val withAlbum = plan.filter { it.album != null }.map { it.path }.toSet()
        val blank = tracks.filter { it.album.isBlank() }.map { it.path }.toSet()
        albumHints.keys.retainAll(blank)
        return plan + albumHints.values.filter { it.path in blank && it.path !in withAlbum }
    }
    private val _state = MutableStateFlow(HealState())
    val state: StateFlow<HealState> = _state.asStateFlow()

    /**
     * [undo]: the job takes back an earlier task – it is not recorded and not counted itself.
     * [task]: the job as data, kept on disk until it is finished (null: not kept, e.g. a cover
     * chosen by hand). [resume]: where an interrupted job goes on from.
     */
    private class Job(
        val kind: HealKind,
        val undo: Boolean = false,
        val id: String = java.util.UUID.randomUUID().toString(),
        val task: HealTask? = null,
        val resume: Pair<Int, Int>? = null,
        val run: (HealExecutor, (HealProgress) -> Unit, () -> Boolean) -> HealReport,
    )

    // ------------------------------------------------------------------ the queue on disk

    private val diskJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val savedLock = Any()
    /** Jobs waiting or running now, as kept on disk. */
    private val saved = LinkedHashMap<String, SavedHealJob>()
    /** Jobs left unfinished by an earlier run of the app, until they go on or are dropped. */
    private var leftOver: List<SavedHealJob> = emptyList()
    @Volatile private var lastQueueWrite = 0L

    private fun queueFile(): File? = library.store?.dataDir?.let { File(it, "heal-queue.json") }

    private val queueFileLock = Any()
    private val queueWriteDue = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Writes the queue (left-over jobs first, then the current ones). Never while the data is locked.
     * One write at a time, and what is written is read inside the same lock: an older picture of
     * the queue can never overwrite a newer one.
     */
    private fun persistQueue() {
        val f = queueFile() ?: return
        synchronized(queueFileLock) {
            val all = synchronized(savedLock) { leftOver + saved.values }
            lastQueueWrite = System.currentTimeMillis()
            runCatching {
                if (all.isEmpty()) f.delete()
                else app.malachite.core.library.LibraryStore.writeAtomicBytes(
                    f, app.malachite.core.fs.Sealed.encodeForWrite(diskJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SavedHealJob.serializer()), all)),
                )
            }.onFailure { app.malachite.player.data.AppLog.e("heal", "Очередь исцеления не сохранилась", it) }
        }
    }

    /** "Heal everything" adds ten tasks at once: they are written in one go, a moment later. */
    private fun persistQueueSoon() {
        if (!queueWriteDue.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(300)
            queueWriteDue.set(false)
            persistQueue()
        }
    }

    /** Reads what an earlier run left unfinished, when a main folder is opened. */
    private fun loadLeftOver() {
        val f = queueFile()
        val list = runCatching {
            if (f == null || !f.isFile) emptyList()
            else diskJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SavedHealJob.serializer()), app.malachite.core.fs.Sealed.readText(f))
        }.getOrElse { e ->
            app.malachite.player.data.AppLog.e("heal", "Очередь исцеления не прочиталась", e)
            emptyList()
        }
        synchronized(savedLock) { leftOver = list.filter { it.id !in saved } }
        _state.update { it.copy(interrupted = leftOver.map { j -> j.task.kind }) }
    }

    /** The unfinished tasks of an earlier run go on: each from where it stopped. */
    fun resumeInterrupted() {
        val jobs = synchronized(savedLock) { leftOver.also { leftOver = emptyList() } }
        _state.update { it.copy(interrupted = emptyList()) }
        for (j in jobs) enqueueTask(j.task, id = j.id, resume = if (j.task.resumable && j.loop > 0) j.loop to j.done else null)
        if (jobs.isEmpty()) persistQueue()
    }

    /** The unfinished tasks of an earlier run are dropped (what they did stays done). */
    fun forgetInterrupted() {
        synchronized(savedLock) { leftOver = emptyList() }
        _state.update { it.copy(interrupted = emptyList()) }
        persistQueueSoon()
    }

    private fun enqueueTask(task: HealTask, id: String = java.util.UUID.randomUUID().toString(), resume: Pair<Int, Int>? = null) {
        synchronized(savedLock) { saved[id] = SavedHealJob(id, task, resume?.first ?: 0, resume?.second ?: 0) }
        persistQueueSoon()
        _state.update { it.copy(queued = it.queued + task.kind) }
        queue.trySend(Job(task.kind, undo = task is HealTask.Undo, id = id, task = task, resume = resume) { e, p, c -> runTask(task, e, p, c) })
    }

    private fun runTask(t: HealTask, e: HealExecutor, p: (HealProgress) -> Unit, c: () -> Boolean): HealReport = when (t) {
        is HealTask.Formats -> e.applyFormats(t.list, p, c)
        is HealTask.Names -> e.applyNames(t.list, p, c)
        is HealTask.Junk -> e.applyJunk(t.list, p, c)
        is HealTask.Missing -> e.applyMissingTags(t.list, p, c)
        is HealTask.Filenames -> e.applyFilenames(t.list, p, c)
        is HealTask.Duplicates -> e.applyDuplicates(t.list, p, c)
        is HealTask.Covers -> {
            // covers taken from other songs of the album need no internet; the rest waits for it
            if (t.list.any { it.donor == null && it.imageFile == null }) waitForInternet(HealKind.COVERS, p, c)
            e.applyCovers(t.list, p, c)
        }
        is HealTask.Layout -> e.applyLayout(t.list, p, c)
        is HealTask.LayoutOf -> {
            val paths = t.paths.toSet()
            e.applyLayout(HealPlanner.layout(e.index.values.filter { it.path in paths }, settings.value.layoutOptions()), p, c)
        }
        is HealTask.Compilations -> e.applyCompilations(t.list, tr("Разные исполнители"), p, c)
        is HealTask.Broken -> e.applyBroken(t.list, p, c)
        is HealTask.CoverQuality -> {
            // light covers are made offline; better ones for small covers come from the internet
            if (t.list.any { it.selected && !it.heavy }) waitForInternet(HealKind.COVER_QUALITY, p, c)
            e.applyCoverQuality(t.list, p, c)
        }
        is HealTask.Numbering -> e.applyTrackNumbers(t.list, p, c)
        is HealTask.TitleNumbers -> e.applyTitleNumbers(t.list, p, c)
        is HealTask.LetterCase -> e.applyLetterCase(t.list, p, c)
        is HealTask.Lyrics -> {
            waitForInternet(HealKind.LYRICS, p, c)
            e.applyLyrics(t.paths, settings.value.lyricsEmbedInFile, p, c)
        }
        is HealTask.Loudness -> runLoudness(t.paths, p, c)
        is HealTask.Fresh -> runFresh(t.kind, t.only, e, p, c)
        is HealTask.OddTags -> runOddTags(if (t.everything) null else t.paths, e, p, c)
        is HealTask.SoundIds -> e.applySoundMatches(t.list, p, c)
        is HealTask.Undo -> runUndo(t.record, t.kind, p, c)
    }

    private fun undoStore(): UndoStore? = library.safe?.backupRoot?.parentFile?.let { UndoStore(File(it, "undo")) }

    private val _undo = MutableStateFlow<List<UndoRecord>>(emptyList())
    /** Healing tasks that can be taken back, newest first. */
    val undoable: StateFlow<List<UndoRecord>> = _undo.asStateFlow()

    fun refreshUndo() { scope.launch { _undo.value = undoStore()?.list().orEmpty() } }

    private val queue = Channel<Job>(Channel.UNLIMITED)
    @Volatile private var cancelRequested = false

    init {
        // what an earlier run left unfinished is offered again when the main folder is open
        scope.launch { library.openRoot.collect { root -> if (root != null) loadLeftOver() } }
        scope.launch {
            for (job in queue) {
                _state.update { it.copy(running = job.kind, queued = it.queued - job.kind, progress = null) }
                LongTaskService.start(context, tr("Исцеление: ${title(job.kind)}"))
                cancelRequested = false
                var session: File? = null
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val report = try {
                    val exec = executor() ?: throw IllegalStateException(tr("Главная папка не открыта"))
                    val sf = library.safe
                    if (!job.undo && settings.value.keepBackups && job.kind != HealKind.LYRICS) sf?.startBackupSession(job.kind.name.lowercase()) else sf?.backupSession = null
                    session = sf?.backupSession
                    exec.resumeAt = job.resume
                    job.run(exec, { p ->
                        _state.update { s -> s.copy(progress = p) }
                        LongTaskService.progress(context, p.message, p.done, p.total)
                        // where the job is, so that it goes on from here after the app is closed
                        if (job.task != null && exec.loopsStarted > 0) {
                            synchronized(savedLock) { saved[job.id]?.let { saved[job.id] = it.copy(loop = exec.loopsStarted, done = p.done) } }
                            if (System.currentTimeMillis() - lastQueueWrite > 2000) persistQueue()
                        }
                    }, { cancelRequested })
                } catch (e: Throwable) {
                    HealReport(job.kind, failed = 1, log = listOf(tr("Ошибка: ${e.message}")))
                } finally {
                    library.safe?.backupSession = null
                }
                // finished (or stopped by the user): it is not kept for later any more
                if (job.task != null) {
                    synchronized(savedLock) { saved.remove(job.id) }
                    persistQueue()
                }
                CoverLoader.invalidate()
                // files healing renamed or moved are the same songs, not new ones (they are not healed again)
                library.rescan(notNew = HealExecutor.compose(report.moves).values.toHashSet())
                report.albumHints.forEach { albumHints[it.path] = it }
                if (app.malachite.player.data.AppLog.enabled) {
                    app.malachite.player.data.AppLog.i(
                        "heal",
                        tr("${title(job.kind)}: исправлено ${report.changed}, пропущено ${report.skipped}, ошибок ${report.failed}") +
                            tr(" (за ${(android.os.SystemClock.elapsedRealtime() - startedAt) / 1000} с)") + "\n" +
                            report.log.take(300).joinToString("\n") { "    $it" },
                    )
                }
                if (report.moves.isNotEmpty()) {
                    val moves = HealExecutor.compose(report.moves)
                    movedTo.putAll(moves)
                    // the songs waiting to be ticked follow their files
                    _state.update { st -> st.copy(soundIds = st.soundIds?.map { m -> moves[m.path]?.let { to -> m.copy(path = to) } ?: m }) }
                    for ((from, to) in moves) albumHints.remove(from)?.let { albumHints[to] = it.copy(path = to) }
                    app.malachite.player.playback.QueueStore.remap(context, moves)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { app.malachite.player.MalachiteApp.graph.player.remapQueue(moves) }
                    library.notifySystem(moves.keys.map { File(library.root, it) } + moves.values.map { File(library.root, it) })
                }
                if (!job.undo) {
                    app.malachite.player.data.Stats.addHealed(report.kind.name, report.changed)
                    // what this task did, so that it can be taken back (lyrics are not files of the songs)
                    if (report.changed > 0 && job.kind != HealKind.LYRICS && job.kind != HealKind.LOUDNESS) {
                        undoStore()?.save(
                            UndoRecord(
                                id = java.util.UUID.randomUUID().toString(), kind = job.kind.name, time = System.currentTimeMillis(),
                                backupDir = session?.takeIf { d -> d.walkTopDown().any { it.isFile } }?.path,
                                moves = HealExecutor.compose(report.moves), trashed = report.trashed, changed = report.changed,
                            ),
                        )
                    }
                }
                _undo.value = undoStore()?.list().orEmpty()
                _state.update {
                    it.copy(
                        running = null,
                        progress = null,
                        reports = (listOf(report) + it.reports).take(30),
                        coverReview = if (job.kind == HealKind.COVERS) report.coverReview else it.coverReview,
                        // plans are stale after changes
                        formats = if (job.kind == HealKind.FORMATS) null else it.formats,
                        names = null, layout = null, filenames = null, missing = null,
                        junk = if (job.kind == HealKind.JUNK) null else it.junk,
                        duplicates = if (job.kind == HealKind.DUPLICATES) null else it.duplicates,
                        covers = if (job.kind == HealKind.COVERS) null else it.covers,
                        lyrics = if (job.kind == HealKind.LYRICS) null else it.lyrics,
                        compilations = null,
                        broken = if (job.kind == HealKind.BROKEN) null else it.broken,
                        coverIssues = null, numbering = null, titleNumbers = null, letterCase = null,
                        loudness = if (job.kind == HealKind.LOUDNESS) null else it.loudness,
                        message = if (job.undo) tr("Отменено исцеление «${title(job.kind)}»: возвращено ${report.changed}, не получилось ${report.failed}") else
                            tr("${title(job.kind)}: исправлено ${report.changed}, пропущено ${report.skipped}, ошибок ${report.failed}") +
                            (if (report.albumHints.isNotEmpty()) tr(". Для ${report.albumHints.size} песен по найденной обложке известен альбом — смотрите «Пустые альбомы»") else ""),
                    )
                }
                if (_state.value.queued.isEmpty()) LongTaskService.stop(context)
            }
        }
    }

    private fun executor(): HealExecutor? {
        val root = library.root ?: return null
        val sf = library.safe ?: return null
        val st = library.store ?: return null
        return HealExecutor(root, sf, st, coverFinder, lyricsFinder, AndroidImagePrep, settings.value.layoutOptions())
            .apply { load(library.tracks.value) }
    }

    fun cancel() { cancelRequested = true }
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ analysis (read-only)

    fun analyze(kind: HealKind) {
        if (_state.value.analyzing != null) return
        _state.update { it.copy(analyzing = kind) }
        scope.launch {
            val tracks = library.tracks.value
            try {
                when (kind) {
                    HealKind.FORMATS -> _state.update { it.copy(formats = HealPlanner.formats(tracks)) }
                    HealKind.JUNK -> _state.update { it.copy(junk = HealPlanner.junk(tracks, library.data.value, settings.value.importRules) { t -> isVideo(t) }) }
                    HealKind.MISSING -> _state.update { it.copy(missing = missingPlan(tracks, library.data.value)) }
                    HealKind.NAMES -> _state.update { it.copy(names = HealPlanner.names(tracks)) }
                    HealKind.FILENAMES -> _state.update { it.copy(filenames = HealPlanner.filenames(tracks, settings.value.layoutOptions())) }
                    HealKind.DUPLICATES -> {
                        val root = library.root
                        val d = HealPlanner.duplicates(tracks) { t -> root?.let { AudioPayload.fingerprint(File(it, t.path)) } }
                        _state.update { it.copy(duplicates = d) }
                    }
                    HealKind.COVERS -> _state.update { it.copy(covers = HealPlanner.covers(tracks, library.root, library.data.value)) }
                    HealKind.LAYOUT -> _state.update { it.copy(layout = HealPlanner.layout(tracks, settings.value.layoutOptions())) }
                    HealKind.LYRICS -> _state.update { it.copy(lyrics = lyricsPlan(tracks, library.data.value)) }
                    HealKind.COMPILATIONS -> _state.update { it.copy(compilations = HealPlanner.compilations(tracks)) }
                    HealKind.BROKEN -> _state.update { it.copy(broken = brokenPlan(tracks)) }
                    HealKind.COVER_QUALITY -> _state.update { it.copy(coverIssues = coverQualityPlan(tracks, library.data.value)) }
                    HealKind.TRACK_NUMBERS -> _state.update { it.copy(numbering = numberingPlan(tracks)) }
                    HealKind.TITLE_NUMBERS -> _state.update { it.copy(titleNumbers = titleNumbersPlan(tracks)) }
                    HealKind.LETTER_CASE -> _state.update { it.copy(letterCase = HealPlanner.letterCase(tracks)) }
                    HealKind.SOUND_ID -> analyzeSounds(tracks)
                    HealKind.LOUDNESS -> {
                        val data = library.data.value
                        _state.update { it.copy(loudness = tracks.filter { t -> data.user(t.path).loudness == null }.map { t -> t.path }) }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(message = tr("Анализ не удался: ${e.message}")) }
            }
            _state.update { it.copy(analyzing = null) }
        }
    }

    fun updateNames(list: List<NameFix>) = _state.update { it.copy(names = list) }
    fun updateMissing(list: List<TagSuggestion>) = _state.update { it.copy(missing = list) }

    // lazy: the engine is created while the app graph itself is still being built
    private val albumFinder by lazy { app.malachite.core.cover.AlbumFinder(app.malachite.player.MalachiteApp.graph.http) }

    /** Songs that still have no album anywhere (tags, folders) – asked online, one by one. */
    fun songsWithoutAlbumSuggestion(): List<app.malachite.core.library.TrackInfo> {
        val suggested = _state.value.missing.orEmpty().filter { it.album != null }.map { it.path }.toSet()
        return library.tracks.value.filter { it.album.isBlank() && it.path !in suggested && it.durationMs > 30_000 }
    }

    fun findAlbumsOnline() {
        if (_state.value.analyzing != null) return
        val todo = songsWithoutAlbumSuggestion()
        if (todo.isEmpty()) return
        _state.update { it.copy(analyzing = HealKind.MISSING) }
        scope.launch {
            val found = ArrayList<TagSuggestion>()
            for ((i, t) in todo.withIndex()) {
                _state.update { it.copy(progress = HealProgress(HealKind.MISSING, i, todo.size, tr("Ищу альбом: ${t.displayTitle}"))) }
                val g = runCatching { albumFinder.find(t.displayArtist, t.displayTitle, t.durationMs).firstOrNull() }.getOrNull() ?: continue
                found += TagSuggestion(
                    t.path, g.album, null,
                    tr("интернет (${g.source}") + (if (g.sources > 1) tr(" и ещё ${g.sources - 1}") else "") +
                        (g.year?.let { ", $it" } ?: "") + tr(", совпадение ${(g.score * 100).toInt()}%)"),
                    // two services naming the same album: pre-selected, still applied only by the user
                    confident = g.confident,
                    year = g.year?.takeIf { app.malachite.core.library.LibraryLayout.reliableYear(t.year) == null },
                )
            }
            _state.update { s ->
                val existing = s.missing.orEmpty().filterNot { m -> found.any { it.path == m.path } }
                s.copy(
                    analyzing = null, progress = null, missing = existing + found,
                    message = tr("Альбом найден в интернете для ${found.size} из ${todo.size} песен — проверьте и отметьте"),
                )
            }
        }
    }
    /**
     * Songs whose album or artist tag is junk: the site it came from, a placeholder, the song
     * written again ([app.malachite.core.heal.OddTags]); with the reason.
     */
    fun oddTagSongs(): List<Pair<app.malachite.core.library.TrackInfo, String>> {
        val suggested = _state.value.missing.orEmpty().map { it.path }.toSet()
        return library.tracks.value.filter { it.path !in suggested && it.durationMs > 30_000 }.mapNotNull { t ->
            oddReason(t)?.let { t to it }
        }
    }

    /** What is wrong with the album, artist or title tag of [t], or null. */
    private fun oddReason(t: app.malachite.core.library.TrackInfo): String? =
        app.malachite.core.heal.OddTags.albumProblem(t.album, t.displayTitle, t.displayArtist)
            ?: app.malachite.core.heal.OddTags.artistProblem(t.artist, t.displayTitle)
            ?: app.malachite.core.heal.OddTags.titleProblem(t.artist, t.title)

    /**
     * Looks the right album or artist up for the songs of [oddTagSongs]: the album by the song
     * and its artist, the artist by the song and its album. What is found joins the list of
     * suggestions; nothing is written until the user ticks it.
     */
    fun checkOddTagsOnline() {
        if (_state.value.analyzing != null) return
        val todo = oddTagSongs()
        if (todo.isEmpty()) return
        _state.update { it.copy(analyzing = HealKind.MISSING) }
        scope.launch {
            val (found, unclear) = lookUpOddTags(todo.map { it.first }) { i, t ->
                _state.update { it.copy(progress = HealProgress(HealKind.MISSING, i, todo.size, tr("Проверяю: ${t.displayTitle}"))) }
            }
            _state.update { s ->
                val existing = s.missing.orEmpty().filterNot { m -> found.any { it.path == m.path } }
                s.copy(
                    analyzing = null, progress = null, missing = existing + found,
                    message = tr("Проверено ${todo.size} песен: предложений ${found.size}") +
                        (if (unclear > 0) tr(", без ответа $unclear (там неверно и альбом, и исполнитель — их лучше поправить вручную)") else "") +
                        tr(". Проверьте и отметьте."),
                )
            }
        }
    }

    /**
     * The right album or artist for songs whose tag is junk ([app.malachite.core.heal.OddTags]):
     * the album by the song and its artist, the artist by the song and its album. The name of a
     * program ("AyuGram") in the album goes for sure even when nothing better is found. Returns
     * the suggestions and how many songs got no answer.
     */
    private fun lookUpOddTags(
        songs: List<app.malachite.core.library.TrackInfo>,
        online: Boolean = true,
        step: (Int, app.malachite.core.library.TrackInfo) -> Unit,
    ): Pair<List<TagSuggestion>, Int> {
        val found = ArrayList<TagSuggestion>()
        var unclear = 0
        for ((i, t) in songs.withIndex()) {
            step(i, t)
            val albumWhy = app.malachite.core.heal.OddTags.albumProblem(t.album, t.displayTitle, t.displayArtist)
            val artistWhy = app.malachite.core.heal.OddTags.artistProblem(t.artist, t.displayTitle)
            // "AyuGram", "Telegram Audio": certainly no album
            val programAlbum = albumWhy != null && app.malachite.core.library.FolderHints.isProgramFolder(t.album)
            // "Разные исполнители" as the artist of one song: a mark of compilations, certainly no artist
            val markArtist = artistWhy != null && app.malachite.core.heal.OddTags.isCompilationName(t.artist)
            fun erase(note: String) = TagSuggestion(t.path, "", null, note, confident = programAlbum, overwrite = true)
            val artistAlone = app.malachite.core.heal.OddTags.artistWithoutTitle(t.artist, t.displayTitle)
            val split = app.malachite.core.heal.OddTags.splitArtistAndSong(t.artist, t.title)
            when {
                // "Молчат Дома - Судно" as the title (and maybe as the artist too): taken apart
                split != null -> found += TagSuggestion(
                    t.path, null, split.first.takeIf { it != t.artist },
                    tr("«${t.title}»: исполнитель «${split.first}», название «${split.second}»"),
                    confident = true, overwriteArtist = split.first != t.artist, title = split.second,
                )
                // "Молчат Дома (Molchat Doma) - Судно (Sudno)" as the artist of "Судно (Sudno)": the song goes out of the artist
                artistAlone != null -> found += TagSuggestion(
                    t.path, null, artistAlone, tr("вместо «${t.artist}»: в исполнителе было ещё и название песни"),
                    confident = true, overwriteArtist = true,
                )
                // the artist is right: the album by the song and the artist
                albumWhy != null && artistWhy == null && t.displayArtist.isNotBlank() -> {
                    val g = if (!online) null else runCatching { albumFinder.find(t.displayArtist, t.displayTitle, t.durationMs).firstOrNull() }.getOrNull()
                    found += if (g != null && app.malachite.core.text.TextFold.looseKey(g.album) != app.malachite.core.text.TextFold.looseKey(t.album)) {
                        TagSuggestion(
                            t.path, g.album, null,
                            tr("вместо «${t.album}» ($albumWhy): интернет (${g.source}") + (if (g.sources > 1) tr(" и ещё ${g.sources - 1}") else "") +
                                tr(", совпадение ${(g.score * 100).toInt()}%)"),
                            confident = g.confident, overwrite = true,
                            year = g.year?.takeIf { app.malachite.core.library.LibraryLayout.reliableYear(t.year) == null },
                        )
                    } else {
                        // nothing better: the junk goes, the song joins the singles
                        erase(tr("«${t.album}» — $albumWhy; в интернете альбом не нашёлся: стереть, песня ляжет к синглам"))
                    }
                }
                // the album is right: the artist by the song and the album
                artistWhy != null && albumWhy == null && t.album.isNotBlank() -> {
                    val a = if (!online) null else runCatching { albumFinder.findArtist(t.displayTitle, t.album, t.durationMs).firstOrNull() }.getOrNull()
                    if (a != null) {
                        found += TagSuggestion(
                            t.path, null, a.artist,
                            tr("вместо «${t.artist}» ($artistWhy): интернет (${a.source}") + (if (a.sources > 1) tr(" и ещё ${a.sources - 1}") else "") +
                                tr(", совпадение ${(a.score * 100).toInt()}%)"),
                            confident = a.confident, overwriteArtist = true,
                        )
                    } else if (markArtist) {
                        found += TagSuggestion(t.path, null, "", tr("«${t.artist}» — $artistWhy; в интернете исполнитель не нашёлся: стереть"), confident = true, overwriteArtist = true)
                    } else unclear++
                }
                // nothing to go on, but the album is the folder of a program: at least that goes
                // (and "Разные исполнители" as the artist with it)
                programAlbum -> {
                    found += if (markArtist) TagSuggestion(
                        t.path, "", "", tr("«${t.album}» — $albumWhy, «${t.artist}» — $artistWhy: стереть оба"),
                        confident = true, overwrite = true, overwriteArtist = true,
                    ) else erase(tr("«${t.album}» — $albumWhy: стереть, песня ляжет к синглам"))
                    unclear++
                }
                // only the mark of compilations as the artist: it goes, the song waits to be recognised by its sound
                markArtist -> {
                    found += TagSuggestion(t.path, null, "", tr("«${t.artist}» — $artistWhy: стереть"), confident = true, overwriteArtist = true)
                    unclear++
                }
                // both wrong: too little to go on
                else -> unclear++
            }
        }
        return found to unclear
    }

    // ------------------------------------------------------------------ recognition by sound

    private val acoustId by lazy { app.malachite.core.soundid.AcoustId(app.malachite.player.MalachiteApp.graph.http) }

    /** Songs worth recognising by their sound: a meaningless title, or no (or a junk) artist. */
    fun soundCandidates(tracks: List<app.malachite.core.library.TrackInfo> = library.tracks.value) = tracks.filter { t ->
        t.durationMs == 0L || t.durationMs > 20_000
    }.filter { t -> app.malachite.core.soundid.AcoustId.worthListening(t, app.malachite.core.heal.OddTags.artistProblem(t.artist, t.displayTitle) != null) }

    fun updateSoundIds(list: List<app.malachite.core.heal.SoundMatch>) = _state.update { it.copy(soundIds = list) }
    fun applySoundIds(list: List<app.malachite.core.heal.SoundMatch>) = enqueueTask(HealTask.SoundIds(list))
    fun clearSoundKeyProblem() = _state.update { it.copy(soundKeyProblem = null, soundKeyDetail = "") }

    private class Heard(val matches: List<app.malachite.core.heal.SoundMatch>, val unknown: Int, val unreadable: Int, val trouble: app.malachite.core.soundid.AcoustId.Answer.KeyProblem?, val offline: Boolean)

    /**
     * Listens to [songs] one by one: the print of the first two minutes goes to AcoustID. Stops at
     * once when the key does not work (every other song would fail the same way), or after a few
     * failures in a row (no internet).
     */
    private fun listen(
        songs: List<app.malachite.core.library.TrackInfo>,
        step: (Int, app.malachite.core.library.TrackInfo) -> Unit,
        cancelled: () -> Boolean,
    ): Heard {
        val key = app.malachite.player.data.SoundIdKey.value
        val found = ArrayList<app.malachite.core.heal.SoundMatch>()
        var unknown = 0
        var unreadable = 0
        var failedInRow = 0
        for ((i, t) in songs.withIndex()) {
            if (cancelled()) break
            step(i, t)
            val f = library.file(t)
            val print = f?.let { app.malachite.player.data.SoundDecoder.print(it, t.durationMs) }
            if (print == null) { unreadable++; continue }
            when (val a = acoustId.lookup(key, print.print, print.durationSec)) {
                is app.malachite.core.soundid.AcoustId.Answer.KeyProblem -> {
                    app.malachite.player.data.AppLog.w("sound", "AcoustID: ключ не принят (${a.trouble}: ${a.detail})")
                    return Heard(found, unknown, unreadable, a, false)
                }
                is app.malachite.core.soundid.AcoustId.Answer.Failed -> {
                    app.malachite.player.data.AppLog.w("sound", "AcoustID не ответил для «${t.displayTitle}»: ${a.detail}")
                    if (++failedInRow >= 3) return Heard(found, unknown, unreadable, null, true)
                }
                is app.malachite.core.soundid.AcoustId.Answer.Found -> {
                    failedInRow = 0
                    val m = soundMatch(t, print.durationSec, a.matches)
                    if (m == null) {
                        unknown++
                        app.malachite.player.data.AppLog.i("sound", "«${t.displayTitle}»: по звуку не узнана")
                    } else {
                        found += m
                        app.malachite.player.data.AppLog.i(
                            "sound",
                            "«${t.displayTitle}» → «${m.artist ?: t.displayArtist} — ${m.title ?: t.displayTitle}»" +
                                (m.album?.let { " ($it)" } ?: "") + ", ${(m.score * 100).toInt()}%" + if (m.confident) "" else ", не уверенно",
                        )
                    }
                }
            }
        }
        return Heard(found, unknown, unreadable, null, false)
    }

    /** What to write into [t] from the service's [matches]: only fields that are missing or meaningless now. */
    private fun soundMatch(t: app.malachite.core.library.TrackInfo, durationSec: Int, matches: List<app.malachite.core.soundid.AcoustId.Match>): app.malachite.core.heal.SoundMatch? {
        val best = matches.firstOrNull { m -> m.durationSec == null || kotlin.math.abs(m.durationSec!! - durationSec) <= 10 } ?: matches.firstOrNull() ?: return null
        val titleJunk = t.title.isBlank() || app.malachite.core.soundid.AcoustId.meaninglessTitle(t.displayTitle)
        val artistJunk = t.artist.isBlank() || app.malachite.core.heal.OddTags.artistProblem(t.artist, t.displayTitle) != null
        val albumJunk = t.album.isBlank() || app.malachite.core.heal.OddTags.albumProblem(t.album, t.displayTitle, t.displayArtist) != null
        val title = best.title.takeIf { titleJunk }
        val artist = best.artist.takeIf { artistJunk }
        val album = best.album?.takeIf { albumJunk }
        if (title == null && artist == null && album == null) return null
        val lengthAgrees = best.durationSec == null || kotlin.math.abs(best.durationSec!! - durationSec) <= 7
        // a song with a real title must be the one the service names
        val titleAgrees = titleJunk || app.malachite.core.online.OnlineMatch.covered(t.displayTitle, best.title) >= 0.5 ||
            app.malachite.core.online.OnlineMatch.covered(best.title, t.displayTitle) >= 0.5
        val confident = best.score >= 0.85 && lengthAgrees && titleAgrees
        return app.malachite.core.heal.SoundMatch(t.path, t.displayTitle, title, artist, album, best.score, confident)
    }

    /** "Check" in the section: listens to the songs and shows what they are, nothing is written yet. */
    private fun analyzeSounds(tracks: List<app.malachite.core.library.TrackInfo>) {
        if (!app.malachite.player.data.SoundIdKey.present) {
            _state.update { it.copy(soundNoKey = true, soundIds = emptyList()) }
            return
        }
        val songs = soundCandidates(tracks)
        _state.update { it.copy(soundNoKey = false) }
        val heard = listen(songs, { i, t ->
            _state.update { it.copy(progress = HealProgress(HealKind.SOUND_ID, i, songs.size, tr("Слушаю: ${t.displayTitle}"))) }
        }, { false })
        _state.update {
            it.copy(
                soundIds = heard.matches, progress = null,
                soundKeyProblem = heard.trouble?.trouble, soundKeyDetail = heard.trouble?.detail.orEmpty(),
                message = soundSummary(songs.size, heard),
            )
        }
    }

    private fun soundSummary(total: Int, h: Heard): String =
        tr("Прослушано ${total} песен: узнано ${h.matches.size}, уверенно ${h.matches.count { it.confident }}") +
            (if (h.unknown > 0) tr(", неизвестны сервису ${h.unknown}") else "") +
            (if (h.unreadable > 0) tr(", звук не прочитался у ${h.unreadable}") else "") +
            (if (h.offline) tr(". Сервис не отвечает — проверьте интернет") else "") +
            (if (h.trouble != null) tr(". Ключ AcoustID не принят") else "")

    /** The step of "heal everything": sure answers are written, the rest waits in the section. */
    private fun runSounds(tracks: List<app.malachite.core.library.TrackInfo>, e: HealExecutor, p: (HealProgress) -> Unit, c: () -> Boolean): HealReport {
        val songs = soundCandidates(tracks)
        if (songs.isEmpty()) return HealReport(HealKind.SOUND_ID)
        // no key: nothing is asked and no window shows; the section says why it did not happen
        if (!app.malachite.player.data.SoundIdKey.present) {
            _state.update { it.copy(soundNoKey = true) }
            return HealReport(HealKind.SOUND_ID, skipped = songs.size, log = listOf(tr("пропущено: в настройках нет ключа AcoustID")))
        }
        _state.update { it.copy(soundNoKey = false) }
        waitForInternet(HealKind.SOUND_ID, p, c)
        val heard = listen(songs, { i, t -> p(HealProgress(HealKind.SOUND_ID, i, songs.size, tr("Слушаю: ${t.displayTitle}"))) }, c)
        val doubtful = heard.matches.filter { !it.confident }
        _state.update { st ->
            st.copy(
                soundIds = st.soundIds.orEmpty().filterNot { old -> heard.matches.any { it.path == old.path } } + doubtful,
                soundKeyProblem = heard.trouble?.trouble ?: st.soundKeyProblem,
                soundKeyDetail = heard.trouble?.detail ?: st.soundKeyDetail,
            )
        }
        val written = e.applySoundMatches(heard.matches.filter { it.confident }, p, c)
        val note = soundSummary(songs.size, heard)
        return written.copy(log = listOf(note) + written.log)
    }

    /** Where files moved during this run of the app (old → new): the chosen songs are followed through healing. */
    private val movedTo = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun follow(path: String): String {
        var cur = path
        repeat(16) { cur = movedTo[cur] ?: return cur }
        return cur
    }

    /**
     * The chosen [steps] of healing, in their order, for [paths] (null: the whole library). Each
     * step is planned when its turn comes, on the library as the earlier steps left it, and does
     * only what it is sure of; the doubtful cases wait in their sections for a look. Steps that
     * compare songs (spellings, clones, compilations, shared covers) still look at the whole
     * library, but change only the chosen songs.
     */
    fun heal(steps: Set<HealStep>, paths: List<String>?) {
        val only = paths?.distinct()
        if (only != null && only.isEmpty()) return
        for (s in HealStep.entries) {
            if (s !in steps) continue
            val kind = s.kind
            if (kind == null) enqueueTask(HealTask.OddTags(only.orEmpty(), everything = only == null))
            else enqueueTask(HealTask.Fresh(kind, only))
        }
    }

    /** [paths] null: the whole library (a step of "heal everything"). */
    private fun runOddTags(paths: List<String>?, e: HealExecutor, p: (HealProgress) -> Unit, c: () -> Boolean): HealReport {
        val chosen = paths?.mapTo(HashSet()) { follow(it) }
        val songs = e.index.values.filter { t -> (chosen == null || t.path in chosen) && oddReason(t) != null }
        if (songs.isEmpty()) return HealReport(HealKind.MISSING)
        // without internet only what needs no lookup is done ("AyuGram" goes, "Artist - Song" is taken apart)
        val online = app.malachite.player.data.Net.online()
        val (found, _) = lookUpOddTags(songs, online) { i, t -> p(HealProgress(HealKind.MISSING, i, songs.size, tr("Проверяю: ${t.displayTitle}"))) }
        // sure answers are written now, the doubtful ones wait in "empty albums and artists"
        val doubtful = found.filter { !it.confident }
        if (doubtful.isNotEmpty()) _state.update { s -> s.copy(missing = s.missing.orEmpty().filterNot { m -> doubtful.any { it.path == m.path } } + doubtful) }
        return e.applyMissingTags(found.filter { it.confident }, p, c)
    }

    fun updateJunk(list: List<JunkItem>) = _state.update { it.copy(junk = list) }

    private fun isVideo(t: app.malachite.core.library.TrackInfo): Boolean {
        val f = library.file(t) ?: return false
        val mp4 = app.malachite.core.audio.Mp4Probe.videoFrames(f)
        return if (mp4 >= 0) mp4 > 2 else AndroidTags.hasVideo(f)
    }

    private fun androidTags(t: app.malachite.core.library.TrackInfo) = library.file(t)?.let { AndroidTags.read(it) }
    fun updateDuplicates(list: List<DuplicateGroup>) = _state.update { it.copy(duplicates = list) }

    // ------------------------------------------------------------------ applying (queued)

    /** A job that is not kept on disk (it cannot be described as data). */
    private fun enqueue(kind: HealKind, run: (HealExecutor, (HealProgress) -> Unit, () -> Boolean) -> HealReport) {
        _state.update { it.copy(queued = it.queued + kind) }
        queue.trySend(Job(kind, run = run))
    }

    /**
     * Takes back the healing task [r] (the newest one only – a newer task may build on it):
     * moved files go back to their places, the originals of changed files come back, files sent
     * to the trash return. The healed versions are not deleted: they go to the trash.
     */
    fun undo(r: UndoRecord) = enqueueTask(HealTask.Undo(r))

    private fun runUndo(r: UndoRecord, kind: HealKind, progress: (HealProgress) -> Unit, cancelled: () -> Boolean): HealReport {
        val root = library.root ?: return HealReport(kind, failed = 1, log = listOf(tr("Главная папка не открыта")))
        val sf = library.safe ?: return HealReport(kind, failed = 1)
        val log = ArrayList<String>()
        var done = 0
        var failed = 0
        val back = LinkedHashMap<String, String>()
        // The record lies in the main folder, where other apps may write: every path in it must
        // lead into the main folder, its trash or its backups, never elsewhere.
        fun inRoot(rel: String) = app.malachite.core.fs.PathGuard.child(root, rel)
        val backupDir = r.backupDir?.let { File(it) }?.takeIf { it.isDirectory && sf.inBackups(it) }
        val originals = backupDir?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
        val total = r.moves.size + originals.size + r.trashed.size
        var step = 0
        fun tick(label: String) = progress(HealProgress(kind, step++, total, tr("Отмена: $label")))

        // 1. files go back to where they were (a duplicate's "move" only pointed its data to the kept copy)
        for ((old, new) in r.moves) {
            if (cancelled()) break
            tick(new.substringAfterLast('/'))
            if (old in r.trashed) continue
            val src = inRoot(new)
            val dst = inRoot(old)
            if (src == null || dst == null) { failed++; log += tr("$new: путь ведёт за пределы главной папки, пропущено"); continue }
            when {
                !src.isFile -> { failed++; log += tr("$new: файла уже нет") }
                dst.exists() -> { failed++; log += tr("$old: на этом месте уже есть файл") }
                else -> when (val res = sf.move(src, dst)) {
                    is app.malachite.core.fs.SafeFiles.Result.Ok -> { back[new] = old; done++ }
                    is app.malachite.core.fs.SafeFiles.Result.Skipped -> { failed++; log += "$new: ${res.reason}" }
                    is app.malachite.core.fs.SafeFiles.Result.Failed -> { failed++; log += "$new: ${res.reason}" }
                }
            }
        }
        // 2. the originals of the changed files; the healed version goes to the trash
        for (b in originals) {
            if (cancelled()) break
            val rel = b.relativeTo(backupDir!!).invariantSeparatorsPath
            tick(rel.substringAfterLast('/'))
            val target = inRoot(rel)
            if (target == null) { failed++; log += tr("$rel: путь ведёт за пределы главной папки, пропущено"); continue }
            if (target.exists() && !sf.trash(target, root).isOk) { failed++; log += tr("$rel: не удалось убрать изменённую версию"); continue }
            when (val res = sf.move(b, target)) {
                is app.malachite.core.fs.SafeFiles.Result.Ok -> { done++; log += tr("$rel: возвращён оригинал") }
                is app.malachite.core.fs.SafeFiles.Result.Skipped -> { failed++; log += "$rel: ${res.reason}" }
                is app.malachite.core.fs.SafeFiles.Result.Failed -> { failed++; log += "$rel: ${res.reason}" }
            }
        }
        // 3. files sent to the trash come back
        for ((rel, inTrash) in r.trashed) {
            if (cancelled()) break
            tick(rel.substringAfterLast('/'))
            val src = File(inTrash).takeIf { sf.inTrash(it) }
            val dst = inRoot(rel)
            if (src == null || dst == null) { failed++; log += tr("$rel: путь ведёт за пределы главной папки, пропущено"); continue }
            when {
                !src.isFile -> { failed++; log += tr("$rel: в корзине его уже нет") }
                dst.exists() -> { failed++; log += tr("$rel: на этом месте уже есть файл") }
                else -> if (sf.move(src, dst).isOk) { done++; log += tr("$rel: возвращён из корзины") } else failed++
            }
        }
        // favourites, lyrics and playlists follow the files back
        if (back.isNotEmpty()) library.store?.applyMoves(back, library.tracks.value.associateBy { it.path })
        // a fully taken back task cannot be taken back again; one that went only partly stays listed
        if (failed == 0 && !cancelled()) {
            undoStore()?.delete(r.id)
            backupDir?.deleteRecursively()
        }
        progress(HealProgress(kind, total, total, tr("Готово")))
        return HealReport(kind, changed = done, failed = failed, log = listOf(tr("Отмена исцеления «${title(kind)}»")) + log, moves = back)
    }

    fun applyFormats(list: List<FormatFix>) = enqueueTask(HealTask.Formats(list))
    fun applyNames(list: List<NameFix>) = enqueueTask(HealTask.Names(list))
    fun applyJunk(list: List<JunkItem>) = enqueueTask(HealTask.Junk(list))
    fun applyMissing(list: List<TagSuggestion>) = enqueueTask(HealTask.Missing(list))
    fun applyFilenames(list: List<FileRename>) = enqueueTask(HealTask.Filenames(list))
    fun applyDuplicates(list: List<DuplicateGroup>) = enqueueTask(HealTask.Duplicates(list))
    fun applyCovers(list: List<CoverJob>) = enqueueTask(HealTask.Covers(list))
    fun applyLayout(list: List<LayoutMove>) = enqueueTask(HealTask.Layout(list))

    fun updateCompilations(list: List<app.malachite.core.heal.CompilationFix>) = _state.update { it.copy(compilations = list) }
    fun updateBroken(list: List<app.malachite.core.heal.BrokenFile>) = _state.update { it.copy(broken = list) }

    /** One album artist for every song of the chosen compilations; then those songs move into one album folder. */
    fun applyCompilations(list: List<app.malachite.core.heal.CompilationFix>) {
        val paths = list.filter { it.selected }.flatMap { it.paths }.distinct()
        enqueueTask(HealTask.Compilations(list))
        enqueueTask(HealTask.LayoutOf(paths))
    }

    fun applyBroken(list: List<app.malachite.core.heal.BrokenFile>) = enqueueTask(HealTask.Broken(list))

    fun updateCoverIssues(list: List<app.malachite.core.heal.CoverIssue>) = _state.update { it.copy(coverIssues = list) }
    fun updateNumbering(list: List<app.malachite.core.heal.AlbumNumbering>) = _state.update { it.copy(numbering = list) }
    fun updateTitleNumbers(list: List<app.malachite.core.heal.TitleNumberFix>) = _state.update { it.copy(titleNumbers = list) }
    fun updateLetterCase(list: List<app.malachite.core.heal.CaseFix>) = _state.update { it.copy(letterCase = list) }

    fun applyCoverQuality(list: List<app.malachite.core.heal.CoverIssue>) = enqueueTask(HealTask.CoverQuality(list))
    fun applyNumbering(list: List<app.malachite.core.heal.AlbumNumbering>) = enqueueTask(HealTask.Numbering(list))
    fun applyTitleNumbers(list: List<app.malachite.core.heal.TitleNumberFix>) = enqueueTask(HealTask.TitleNumbers(list))
    fun applyLetterCase(list: List<app.malachite.core.heal.CaseFix>) = enqueueTask(HealTask.LetterCase(list))

    /** Songs without words, and songs whose words were found in another alphabet ("I dve tysyachi let voyna"). */
    private fun lyricsPlan(tracks: List<app.malachite.core.library.TrackInfo>, data: app.malachite.core.library.LibraryData): List<String> {
        val store = library.store
        return HealPlanner.lyrics(tracks, data) { t ->
            val saved = store?.let { s -> runCatching { s.loadLyrics(data.user(t.path)) }.getOrNull() }
            saved != null && app.malachite.core.lyrics.LyricsScript.foreignLetters(t.displayTitle, t.displayArtist, saved)
        }
    }

    /** Width, height and size of the cover of a song (only the picture's header is decoded), and a fingerprint of its bytes. */
    private fun artworkInfo(t: app.malachite.core.library.TrackInfo): app.malachite.core.heal.CoverLook? {
        val f = library.file(t) ?: return null
        val bytes = runCatching { app.malachite.core.audio.TagIO.readArtwork(f) }.getOrNull() ?: return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        // the same picture is the same bytes: a whole discography downloaded with one picture
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        return app.malachite.core.heal.CoverLook(o.outWidth, o.outHeight, bytes.size, "${bytes.size}-$crc")
    }

    /** Covers to fix among [tracks]: too small, too heavy, or one picture on several albums (not the ones the user picked). */
    private fun coverQualityPlan(tracks: List<app.malachite.core.library.TrackInfo>, data: app.malachite.core.library.LibraryData) =
        HealPlanner.coverQuality(tracks, ::artworkInfo, chosen = { t ->
            val u = data.user(t.path)
            // before the mark existed a picked cover was known by where it came from (not a download's own picture)
            u.coverChosen || (u.coverSource != null && u.coverSource !in DOWNLOAD_COVER_SOURCES)
        })

    private val albumTracks by lazy { app.malachite.core.cover.AlbumTracks(app.malachite.player.MalachiteApp.graph.http) }

    /**
     * Numbers in titles ("09 Иван Говнов") and titles that are only a number ("13"): those get the
     * real title from the album's list of songs online (MusicBrainz, Deezer), each album asked once.
     */
    private fun titleNumbersPlan(tracks: List<app.malachite.core.library.TrackInfo>): List<app.malachite.core.heal.TitleNumberFix> {
        val plain = HealPlanner.titleNumbers(tracks)
        if (tracks.none { HealPlanner.numberOnlyTitle(it) != null }) return plain
        if (!app.malachite.player.data.Net.online()) {
            _state.update { it.copy(message = tr("Нет интернета: названия-номера («13») берутся из списков песен альбомов в интернете. Проверьте позже.")) }
            return plain
        }
        val lists = HashMap<String, List<app.malachite.core.cover.AlbumTracks.Tracklist>>()
        val online = HealPlanner.numberTitles(tracks) { artist, album -> lists.getOrPut("$artist|$album") { albumTracks.find(artist, album) } }
        val fixed = online.mapTo(HashSet()) { it.path }
        return plain.filter { it.path !in fixed } + online
    }

    /** Albums without track numbers matched to their lists of songs online (Deezer). */
    private fun numberingPlan(tracks: List<app.malachite.core.library.TrackInfo>): List<app.malachite.core.heal.AlbumNumbering> {
        if (!app.malachite.player.data.Net.online()) {
            _state.update { it.copy(message = tr("Нет интернета: списки песен альбомов берутся из интернета. Проверьте позже.")) }
            return emptyList()
        }
        val lists = app.malachite.core.cover.AlbumTracklist(app.malachite.player.MalachiteApp.graph.http)
        return HealPlanner.albumsWithoutNumbers(tracks).mapNotNull { album ->
            val first = album.first()
            val remote = runCatching { lists.find(first.groupArtist, first.album) }.getOrNull() ?: return@mapNotNull null
            val fixes = HealPlanner.matchNumbers(album, remote)
            if (fixes.isEmpty()) null
            else app.malachite.core.heal.AlbumNumbering("num|${first.groupArtist}|${first.album}", first.groupArtist, first.album, "Deezer", fixes)
        }
    }

    /** Measures the loudness of the songs; nothing in the files changes – the player levels by it. */
    fun measureLoudness(paths: List<String>) = enqueueTask(HealTask.Loudness(paths))

    private fun runLoudness(all: List<String>, p: (HealProgress) -> Unit, c: () -> Boolean): HealReport {
        // songs measured before the app was closed are not measured again
        val known = (library.store?.loadLibrary() ?: library.data.value)
        val paths = all.filter { known.user(it).loudness == null }
        val done = HashMap<String, Float>()
        var failed = 0
        fun save() {
            if (done.isEmpty()) return
            val batch = HashMap(done)
            done.clear()
            library.store?.updateLibrary { d -> batch.entries.fold(d) { acc, (path, v) -> acc.update(path) { u -> u.copy(loudness = v) } } }
        }
        for ((i, path) in paths.withIndex()) {
            if (c()) break
            val t = library.track(path) ?: continue
            p(HealProgress(HealKind.LOUDNESS, i, paths.size, t.displayTitle))
            val f = library.file(t) ?: continue
            val lufs = LoudnessScan.measure(f, t.durationMs, c)
            if (lufs != null) done[path] = lufs.toFloat() else failed++
            // kept often: after the app is closed the measuring goes on from here
            if (done.size >= 10) save()
        }
        save()
        p(HealProgress(HealKind.LOUDNESS, paths.size, paths.size, tr("Готово")))
        return HealReport(HealKind.LOUDNESS, changed = paths.size - failed, failed = failed)
    }

    /**
     * Files that cannot be played. Each one is opened the way Android opens music and a few pieces
     * of sound are read from its beginning and its middle. Songs that failed to play in this run
     * are listed too, for a look (not ticked).
     */
    private fun brokenPlan(tracks: List<app.malachite.core.library.TrackInfo>): List<app.malachite.core.heal.BrokenFile> {
        val failed = app.malachite.player.playback.PlaybackFailures.paths()
        return tracks.mapNotNull { t ->
            val reason = brokenReason(t)
            when {
                reason != null -> app.malachite.core.heal.BrokenFile(t.path, reason, selected = t.durationMs <= 0 || reason == tr("пустой файл"))
                t.path in failed -> app.malachite.core.heal.BrokenFile(t.path, tr("не воспроизвёлся в плеере"), selected = false)
                else -> null
            }
        }
    }

    private fun brokenReason(t: app.malachite.core.library.TrackInfo): String? {
        val f = library.file(t) ?: return null
        if (!f.isFile) return null
        if (f.length() == 0L) return tr("пустой файл")
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return tr("нет звуковой дорожки")
            ex.selectTrack(track)
            val buf = java.nio.ByteBuffer.allocate(512 * 1024)
            var read = 0
            for (i in 0 until 6) {
                if (ex.readSampleData(buf, 0) < 0) break
                read++
                ex.advance()
            }
            if (read == 0) return tr("звук не читается")
            if (t.durationMs > 20_000) {
                ex.seekTo(t.durationMs * 500, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (ex.readSampleData(buf, 0) < 0) return tr("файл обрывается на середине")
            }
            null
        } catch (e: Exception) {
            tr("не открывается")
        } finally {
            runCatching { ex.release() }
        }
    }
    fun applyLyrics(list: List<String>) = enqueueTask(HealTask.Lyrics(list))

    /**
     * A task that needs the internet does not fail on every song when there is none: it says
     * so and starts by itself when the connection is back (or stops when the user cancels).
     */
    private fun waitForInternet(kind: HealKind, progress: (HealProgress) -> Unit, cancelled: () -> Boolean) {
        var told = false
        while (!app.malachite.player.data.Net.online() && !cancelled()) {
            if (!told) {
                progress(HealProgress(kind, 0, 0, tr("Нет интернета — начну сам, как только появится связь")))
                told = true
            }
            Thread.sleep(1500)
        }
    }

    /** Manual cover choice for an album from the review list. */
    fun applyChosenCover(job: CoverJob, art: TagEdit.Artwork) = enqueue(HealKind.COVERS) { e, _, _ ->
        e.embedCover(job.tracks, art).also {
            _state.update { s -> s.copy(coverReview = s.coverReview.filter { it.id != job.id }) }
            // picked by the user: later healing leaves it as it is
            val paths = job.tracks.map { follow(it) }
            library.store?.updateLibrary { d -> paths.fold(d) { acc, p -> acc.update(p) { u -> u.copy(coverChosen = true) } } }
        }
    }

    /**
     * "Heal everything": not-music → formats → empty albums/artists → names → file names → duplicates → covers → folders → lyrics. Each step
     * re-plans on the *current* library and applies only confident fixes; doubtful ones
     * (possible typos, non-identical duplicates, unsure covers) are left for review.
     */
    fun healEverything(steps: Set<HealStep> = HealStep.USUAL_LIBRARY) = heal(steps, null)

    /**
     * One step of "heal everything", planned on the library as it is now; with [only] for those
     * songs alone (followed through renames and moves of the earlier steps).
     */
    private fun runFresh(kind: HealKind, only: List<String>?, e: HealExecutor, p: (HealProgress) -> Unit, c: () -> Boolean): HealReport {
        val all = e.index.values.toList()
        val chosen: Set<String>? = only?.mapTo(HashSet()) { follow(it) }
        val tracks = if (chosen == null) all else all.filter { it.path in chosen }
        if (chosen != null && tracks.isEmpty()) return HealReport(kind)
        fun mine(path: String) = chosen == null || path in chosen
        // covers and compilations look at whole albums: the albums the chosen songs are in
        val albumMates = if (chosen == null) all else {
            val albums = tracks.mapTo(HashSet()) { app.malachite.core.text.TextFold.strictKey(it.album) }
            all.filter { app.malachite.core.text.TextFold.strictKey(it.album) in albums }
        }
        fun data() = library.store?.loadLibrary() ?: library.data.value
        return when (kind) {
            HealKind.JUNK -> e.applyJunk(HealPlanner.junk(tracks, data(), settings.value.importRules) { t -> isVideo(t) }, p, c)
            HealKind.FORMATS -> e.applyFormats(HealPlanner.formats(tracks), p, c)
            HealKind.TITLE_NUMBERS -> e.applyTitleNumbers(titleNumbersPlan(tracks), p, c)
            HealKind.MISSING -> e.applyMissingTags(missingPlan(tracks, data()), p, c)
            HealKind.NAMES -> e.applyNames(
                HealPlanner.names(all).mapNotNull { f -> if (chosen == null) f else f.tracks.filter(::mine).takeIf { it.isNotEmpty() }?.let { f.copy(tracks = it) } },
                p, c,
            )
            HealKind.FILENAMES -> e.applyFilenames(HealPlanner.filenames(tracks, settings.value.layoutOptions()), p, c)
            HealKind.DUPLICATES -> {
                val root = library.root
                val groups = HealPlanner.duplicates(all) { t -> root?.let { AudioPayload.fingerprint(File(it, t.path)) } }
                // only the chosen copies go; a copy outside the choice is never touched
                e.applyDuplicates(groups.mapNotNull { g -> if (chosen == null) g else g.remove.filter(::mine).takeIf { it.isNotEmpty() }?.let { g.copy(remove = it) } }, p, c)
            }
            HealKind.COVERS -> e.applyCovers(
                HealPlanner.covers(albumMates, library.root, data()).mapNotNull { j -> if (chosen == null) j else j.tracks.filter(::mine).takeIf { it.isNotEmpty() }?.let { j.copy(tracks = it) } },
                p, c,
            )
            HealKind.COMPILATIONS -> e.applyCompilations(
                HealPlanner.compilations(albumMates).filter { it.confident }
                    .mapNotNull { f -> if (chosen == null) f else f.paths.filter(::mine).takeIf { it.isNotEmpty() }?.let { f.copy(paths = it) } },
                tr("Разные исполнители"), p, c,
            )
            HealKind.SOUND_ID -> runSounds(tracks, e, p, c)
            HealKind.LAYOUT -> e.applyLayout(HealPlanner.layout(tracks, settings.value.layoutOptions()), p, c)
            HealKind.LYRICS -> {
                waitForInternet(HealKind.LYRICS, p, c)
                e.applyLyrics(lyricsPlan(tracks, data()), settings.value.lyricsEmbedInFile, p, c)
            }
            // only files that surely cannot play (empty, no sound); the doubtful ones wait for a look
            HealKind.BROKEN -> e.applyBroken(brokenPlan(tracks), p, c)
            HealKind.COVER_QUALITY -> {
                // one picture on several albums is seen only among all of them, so the whole library is looked at
                val plan = coverQualityPlan(all, data())
                    .mapNotNull { i -> if (chosen == null) i else i.paths.filter(::mine).takeIf { it.isNotEmpty() }?.let { i.copy(paths = it) } }
                if (plan.any { !it.heavy }) waitForInternet(HealKind.COVER_QUALITY, p, c)
                e.applyCoverQuality(plan, p, c)
            }
            HealKind.TRACK_NUMBERS -> {
                waitForInternet(HealKind.TRACK_NUMBERS, p, c)
                e.applyTrackNumbers(
                    numberingPlan(albumMates).mapNotNull { n -> if (chosen == null) n else n.fixes.filter { mine(it.path) }.takeIf { it.isNotEmpty() }?.let { n.copy(fixes = it) } },
                    p, c,
                )
            }
            // chosen on purpose: every capitals-only or lower-case-only name of these songs gets the usual look
            HealKind.LETTER_CASE -> e.applyLetterCase(HealPlanner.letterCase(tracks).map { it.copy(selected = true) }, p, c)
            HealKind.LOUDNESS -> runLoudness(tracks.map { it.path }, p, c)
            else -> HealReport(kind)
        }
    }

    companion object {
        /** Catalogues whose own picture comes with a downloaded song (see the online section). */
        private val DOWNLOAD_COVER_SOURCES = setOf("Internet Archive", "Audius", "Deezer")

        fun title(k: HealKind) = when (k) {
            HealKind.FORMATS -> tr("форматы файлов")
            HealKind.JUNK -> tr("не музыка")
            HealKind.MISSING -> tr("пустые альбомы и исполнители")
            HealKind.NAMES -> tr("названия")
            HealKind.FILENAMES -> tr("имена файлов")
            HealKind.DUPLICATES -> tr("клоны")
            HealKind.COVERS -> tr("обложки")
            HealKind.LAYOUT -> tr("раскладка по папкам")
            HealKind.LYRICS -> tr("тексты")
            HealKind.COMPILATIONS -> tr("сборники")
            HealKind.BROKEN -> tr("повреждённые файлы")
            HealKind.COVER_QUALITY -> tr("качество обложек")
            HealKind.TRACK_NUMBERS -> tr("номера треков")
            HealKind.TITLE_NUMBERS -> tr("номера в названиях")
            HealKind.LETTER_CASE -> tr("регистр букв")
            HealKind.LOUDNESS -> tr("громкость")
            HealKind.SOUND_ID -> tr("узнавание по звуку")
        }
    }
}
