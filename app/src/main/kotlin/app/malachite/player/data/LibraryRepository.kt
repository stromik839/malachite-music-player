package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import app.malachite.core.audio.TagEdit
import app.malachite.core.fs.SafeFiles
import app.malachite.core.heal.HealPlanner
import app.malachite.core.library.LibraryData
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.LibraryScanner
import app.malachite.core.library.LibraryStore
import app.malachite.core.library.Playlist
import app.malachite.core.library.PlaylistEntry
import app.malachite.core.library.TrackInfo
import app.malachite.core.lyrics.Lyrics
import app.malachite.core.text.TextFold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: String,
    val tracks: List<TrackInfo>,
) {
    val cover: TrackInfo? get() = tracks.firstOrNull { it.hasArtwork } ?: tracks.firstOrNull()
    val durationMs get() = tracks.sumOf { it.durationMs }
}

data class Artist(val key: String, val name: String, val albums: List<Album>, val tracks: List<TrackInfo>)

data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val message: String = "")

/**
 * The library = the songs inside the main folder. Nothing outside it is ever shown.
 * Albums and artists are grouped by spelling-insensitive keys, so even before healing
 * "Всем смертям назло" and "Всемсмертямназло" appear as one album.
 */
/** Ids of songs played from the internet start with this (they are not files of the main folder). */
const val ONLINE_PREFIX = "online:"

class LibraryRepository(private val context: Context, private val settings: SettingsRepository) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile var root: File? = null; private set
    @Volatile var store: LibraryStore? = null; private set
    @Volatile var safe: SafeFiles? = null; private set

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    /** Bumped whenever a file's cover / tags change so images reload. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val isOpen get() = root != null
    private val _openRoot = MutableStateFlow<File?>(null)
    /** The opened main folder (null until opened) – observed by the UI. */
    val openRoot: StateFlow<File?> = _openRoot.asStateFlow()

    /** Opens (and creates if needed) the main folder. */
    fun open(folder: File): Boolean {
        if (!folder.exists() && !folder.mkdirs()) return false
        val st = LibraryStore(folder)
        st.ensureCreated(Build.MANUFACTURER + " " + Build.MODEL)
        val sf = SafeFiles(st.dataDir).apply {
            protect(folder, st.dataDir, st.playlistsDir)
            recover()
        }
        root = folder
        store = st
        safe = sf
        _openRoot.value = folder
        publish(st.loadIndex())
        _data.value = st.loadLibrary()
        _playlists.value = st.listPlaylists()
        rescanAsync()
        return true
    }

    fun reloadPlaylists() { store?.let { st -> _playlists.value = st.listPlaylists() } }

    fun file(t: TrackInfo): File? = root?.let { File(it, t.path) }
    fun file(path: String): File? = root?.let { File(it, path) }
    @Volatile private var byPath: Map<String, TrackInfo> = emptyMap()
    fun track(path: String): TrackInfo? = byPath[path] ?: onlineItems[path]?.let(::onlineTrack)

    // ---- songs played straight from the internet
    // They go through the same player (queue, notification, widget, lock screen) under an id that
    // starts with "online:". They are NOT in the library: no playlists, favourites or play
    // counts until the song is downloaded and becomes an ordinary file of the main folder.
    private val onlineItems = java.util.concurrent.ConcurrentHashMap<String, app.malachite.core.online.OnlineCatalog.Item>()

    fun isOnline(path: String?): Boolean = path != null && path.startsWith(ONLINE_PREFIX)

    fun onlineItem(path: String?): app.malachite.core.online.OnlineCatalog.Item? = path?.let { onlineItems[it] }

    /** Makes an online song playable; returns the stand-in the player and the screens work with. */
    fun registerOnline(item: app.malachite.core.online.OnlineCatalog.Item): TrackInfo {
        onlineItems[ONLINE_PREFIX + item.id] = item
        return onlineTrack(item)
    }

    private fun onlineTrack(item: app.malachite.core.online.OnlineCatalog.Item) = TrackInfo(
        path = ONLINE_PREFIX + item.id, title = item.title, artist = item.artist, album = item.album,
        durationMs = item.durationSec * 1000L, hasArtwork = item.coverUrl != null,
    )

    fun rescanAsync() {
        scope.launch { rescan() }
    }

    suspend fun rescan() = mutex.withLock {
        val r = root ?: return@withLock
        val st = store ?: return@withLock
        _scan.value = ScanState(true, 0, 0, tr("Чтение главной папки…"))
        val list = withContext(Dispatchers.IO) {
            LibraryScanner(r, settings.value.layoutOptions()).scan(_tracks.value.ifEmpty { st.loadIndex() }, threads = 3) { d, t ->
                _scan.value = ScanState(true, d, t, tr("Чтение тегов $d из $t"))
            }
        }
        st.saveIndex(list)
        publish(list)
        _data.value = st.loadLibrary()
        _playlists.value = st.listPlaylists()
        _scan.value = ScanState(false, list.size, list.size, "")
    }

    /** Re-reads one file after it changed. */
    fun refreshTrack(path: String) {
        val r = root ?: return
        val f = File(r, path)
        val list = _tracks.value.toMutableList()
        val i = list.indexOfFirst { it.path == path }
        if (f.isFile) {
            val t = LibraryScanner(r, settings.value.layoutOptions()).read(f, path)
            if (i >= 0) list[i] = t else list += t
        } else if (i >= 0) list.removeAt(i)
        publish(list)
        store?.saveIndex(list)
        _revision.value++
    }

    /** Re-reads several files after they changed (one write of the index for all of them). */
    fun refreshTracks(paths: Collection<String>) {
        val r = root ?: return
        if (paths.isEmpty()) return
        val list = _tracks.value.toMutableList()
        val at = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { i, t -> at[t.path] = i }
        val gone = HashSet<Int>()
        val scanner = LibraryScanner(r, settings.value.layoutOptions())
        for (p in paths.toSet()) {
            val f = File(r, p)
            val i = at[p]
            if (f.isFile) {
                val t = runCatching { scanner.read(f, p) }.getOrNull() ?: continue
                if (i != null) list[i] = t else list += t
            } else if (i != null) gone += i
        }
        val out = if (gone.isEmpty()) list else list.filterIndexed { i, _ -> i !in gone }
        publish(out)
        store?.saveIndex(out)
        _revision.value++
    }

    private fun publish(list: List<TrackInfo>) {
        // keys computed once per track (not on every comparison) – matters with thousands of songs
        val sorted = list.map { TextFold.strictKey(it.displayTitle) to it }
            .sortedWith(compareBy({ it.first }, { it.second.path })).map { it.second }
        byPath = sorted.associateBy { it.path }
        _tracks.value = sorted
        _albums.value = buildAlbums(sorted)
        _artists.value = buildArtists(_albums.value)
    }

    private fun buildAlbums(list: List<TrackInfo>): List<Album> =
        list.groupBy { TextFold.strictKey(it.groupArtist) + "|" + TextFold.strictKey(it.album) }
            .map { (key, ts) ->
                val title = ts.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val artist = ts.groupingBy { it.groupArtist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                Album(
                    key = key,
                    title = title.ifBlank { LibraryLayout.UNKNOWN_ALBUM },
                    artist = artist.ifBlank { LibraryLayout.UNKNOWN_ARTIST },
                    // the year most songs agree on; download dates ("20260218") are not years
                    year = ts.mapNotNull { app.malachite.core.library.LibraryLayout.reliableYear(it.year) }.groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key.orEmpty(),
                    tracks = ts.sortedWith(compareBy({ it.disc }, { it.track }, { it.displayTitle.lowercase() })),
                )
            }
            .sortedBy { TextFold.strictKey(it.title) }

    private fun buildArtists(albums: List<Album>): List<Artist> =
        albums.groupBy { it.key.substringBefore('|') }
            .map { (key, als) ->
                val name = als.flatMap { it.tracks }.groupingBy { it.groupArtist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                Artist(key, name.ifBlank { LibraryLayout.UNKNOWN_ARTIST }, als.sortedBy { it.year }, als.flatMap { it.tracks })
            }
            .sortedBy { TextFold.strictKey(it.name) }

    // ------------------------------------------------------------------ user data

    private val pendingDataWrites = java.util.concurrent.atomic.AtomicInteger()
    private val dataWrites = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachite-data").apply { isDaemon = true }
    }

    /**
     * A change of the player's own data (a favourite, a lyrics offset, a marked line): the
     * screen shows it at once, the file is written in the background. The write reads the file,
     * changes it and stores it (with a wait for the disk), which on the main thread held the app
     * for up to two seconds. Writes keep their order; once none is left the file's version is
     * shown (it also holds what other parts of the app, healing for one, stored meanwhile).
     */
    private fun updateData(f: (LibraryData) -> LibraryData) {
        val st = store ?: return
        _data.update(f)
        pendingDataWrites.incrementAndGet()
        dataWrites.execute {
            val saved = runCatching { st.updateLibrary(f) }
                .onFailure { AppLog.w("library", "user data not written", it) }
                .getOrNull()
            if (pendingDataWrites.decrementAndGet() == 0 && saved != null && store === st) _data.value = saved
        }
    }


    fun toggleFavorite(path: String) { if (!isOnline(path)) updateData { it.update(path) { u -> u.copy(favorite = !u.favorite) } } }

    /** Several songs into (or out of) the favourites with one write. */
    fun setFavorite(paths: Collection<String>, value: Boolean) {
        val mine = paths.filterNot { isOnline(it) }
        if (mine.isEmpty()) return
        updateData { d -> mine.fold(d) { acc, p -> acc.update(p) { u -> u.copy(favorite = value) } } }
    }

    /** "Не нравится": the songs are passed over in shuffle and left out of the smart playlists. */
    fun setDisliked(paths: Collection<String>, value: Boolean) {
        val mine = paths.filterNot { isOnline(it) }
        if (mine.isEmpty()) return
        updateData { d -> mine.fold(d) { acc, p -> acc.update(p) { u -> u.copy(disliked = value) } } }
    }

    fun setArtistDisliked(name: String, value: Boolean) {
        if (name.isBlank()) return
        updateData { it.dislikeArtist(name, value) }
    }

    /** The song, or its artist, is marked "Не нравится". */
    fun isDisliked(path: String): Boolean {
        val d = _data.value
        return track(path)?.let { d.disliked(it) } ?: d.user(path).disliked
    }

    /** The best moment of a song (null: forget it). */
    fun setBestMoment(path: String, ms: Long?) {
        if (isOnline(path)) return
        updateData { it.update(path) { u -> u.copy(bestMomentMs = ms?.coerceAtLeast(0)) } }
    }

    fun markPlayed(path: String) = scope.launch {
        if (!isOnline(path)) updateData { it.update(path) { u -> u.copy(playCount = u.playCount + 1, lastPlayed = System.currentTimeMillis()) } }
    }

    fun rememberSpeed(path: String, speed: Float?, pitch: Float?) {
        if (!isOnline(path)) updateData { it.update(path) { u -> u.copy(speed = speed, pitchSemitones = pitch) } }
    }

    /**
     * Marks one lyric line as a favourite, or takes the mark away. [lineKey] comes from
     * [app.malachite.core.lyrics.lineMarkKeys], so only this line is marked, not its repeats.
     * Returns true if it is marked now.
     */
    fun toggleLineMark(path: String, lineKey: String): Boolean {
        if (isOnline(path) || lineKey.isEmpty()) return false
        val marked = !app.malachite.core.lyrics.isLineMarked(lineKey, data.value.user(path).markedLines.toSet())
        // an older mark of the same words (it marked every repeat) goes away with it
        val legacy = lineKey.substringBeforeLast('#').takeIf { lineKey.endsWith("#0") }
        updateData {
            it.update(path) { u ->
                u.copy(markedLines = if (marked) u.markedLines + lineKey else u.markedLines - lineKey - setOfNotNull(legacy))
            }
        }
        return marked
    }

    fun setNoLyrics(path: String, value: Boolean) { if (!isOnline(path)) updateData { it.update(path) { u -> u.copy(noLyrics = value) } } }

    // ------------------------------------------------------------------ lyrics

    fun loadLyrics(t: TrackInfo): Lyrics? = store?.loadLyrics(t)

    fun saveLyrics(t: TrackInfo, lyrics: Lyrics, source: String) {
        if (isOnline(t.path)) return
        val st = store ?: return
        st.saveLyrics(t, lyrics, source)
        _data.value = st.loadLibrary()
        if (settings.value.lyricsEmbedInFile) {
            val text = if (lyrics.synced) app.malachite.core.lyrics.Lrc.write(lyrics) else lyrics.plainText
            file(t)?.let { safe?.writeTags(it, TagEdit(lyrics = text), root) }
            refreshTrack(t.path)
        }
    }

    fun setLyricsOffset(t: TrackInfo, ms: Long) {
        if (isOnline(t.path)) return // nothing is stored for a song that is not in the library
        updateData { it.update(t.path) { u -> u.copy(lyricsOffsetMs = ms) } }
    }

    /** [saveLyrics] away from the main thread (it writes files, maybe the tags too); [then] runs on the main thread after. */
    fun saveLyricsInBackground(t: TrackInfo, lyrics: Lyrics, source: String, then: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { saveLyrics(t, lyrics, source) }
                .onFailure { AppLog.w("lyrics", "lyrics not saved", it) }.isSuccess
            withContext(Dispatchers.Main) { then(ok) }
        }
    }

    fun deleteLyrics(t: TrackInfo) {
        store?.deleteLyrics(t)
        _data.value = store?.loadLibrary() ?: _data.value
    }

    // ------------------------------------------------------------------ tags & covers

    /**
     * Writes tags safely and, when names changed, moves the file to its Artist/Album folder.
     * Returns the new relative path.
     */
    suspend fun editTags(t: TrackInfo, edit: TagEdit, reorganize: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val r = root ?: return@withContext Result.failure(IllegalStateException(tr("Нет главной папки")))
        val sf = safe ?: return@withContext Result.failure(IllegalStateException(tr("Нет главной папки")))
        val f = File(r, t.path)
        when (val res = sf.writeTags(f, edit, r)) {
            is SafeFiles.Result.Ok -> {}
            is SafeFiles.Result.Skipped -> return@withContext Result.failure(IllegalStateException(res.reason))
            is SafeFiles.Result.Failed -> return@withContext Result.failure(IllegalStateException(res.reason))
        }
        refreshTrack(t.path)
        var path = t.path
        if (reorganize && (edit.artist != null || edit.album != null || edit.albumArtist != null || edit.title != null)) {
            val fresh = track(t.path) ?: return@withContext Result.success(path)
            val moves = HealPlanner.layout(listOf(fresh), settings.value.layoutOptions(), LibraryLayout.Context(_tracks.value))
            for (m in moves) {
                val res = sf.move(File(r, m.from), File(r, m.to))
                if (res is SafeFiles.Result.Ok) {
                    val to = res.file.relativeTo(r).invariantSeparatorsPath
                    store?.applyMoves(mapOf(m.from to to), _tracks.value.associateBy { it.path })
                    refreshTrack(m.from)
                    refreshTrack(to)
                    path = to
                    notifySystem(listOf(File(r, m.from), res.file))
                }
            }
            _data.value = store?.loadLibrary() ?: _data.value
            _playlists.value = store?.listPlaylists() ?: _playlists.value
        }
        notifySystem(listOf(File(r, path)))
        Result.success(path)
    }

    suspend fun embedCover(paths: List<String>, art: TagEdit.Artwork, source: String): Int = withContext(Dispatchers.IO) {
        val r = root ?: return@withContext 0
        val sf = safe ?: return@withContext 0
        var ok = 0
        for (p in paths) {
            if (sf.writeTags(File(r, p), TagEdit(artwork = art), r).isOk) {
                ok++
                updateData { it.update(p) { u -> u.copy(coverSource = source, coverChosen = true) } }
                refreshTrack(p)
            }
        }
        notifySystem(paths.map { File(r, it) })
        ok
    }

    /** Tells Android's media index (and so other apps) that files changed. */
    fun notifySystem(files: List<File>) {
        if (files.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null) }
    }

    // ------------------------------------------------------------------ playlists

    private fun trackMap() = _tracks.value.associateBy { it.path }

    fun createPlaylist(name: String, paths: List<String> = emptyList()) {
        val st = store ?: return
        val now = System.currentTimeMillis()
        val p = Playlist(name.trim().ifEmpty { tr("Плейлист") }, now, LibraryStore.iso(now),
            entries = paths.mapIndexed { i, s -> PlaylistEntry(i + 1, "", s) })
        st.savePlaylist(p, trackMap())
        _playlists.value = st.listPlaylists()
    }

    fun addToPlaylist(name: String, paths: List<String>) {
        val st = store ?: return
        val p = st.listPlaylists().firstOrNull { it.name == name } ?: return createPlaylist(name, paths)
        val existing = p.entries.map { it.relativePath }.toSet()
        val add = paths.filter { it !in existing }.map { PlaylistEntry(0, "", it) }
        st.savePlaylist(p.copy(entries = p.entries + add), trackMap())
        _playlists.value = st.listPlaylists()
    }

    fun savePlaylistOrder(p: Playlist, paths: List<String>) {
        val st = store ?: return
        st.savePlaylist(p.copy(entries = paths.map { PlaylistEntry(0, "", it) }), trackMap())
        _playlists.value = st.listPlaylists()
    }

    fun deletePlaylist(name: String) {
        store?.deletePlaylist(name)
        _playlists.value = store?.listPlaylists().orEmpty()
    }

    /**
     * Puts a song downloaded in the online section into the main folder as an ordinary song:
     * placed and named like the rest, with its title, artist, album and cover written into the
     * file itself – so everything else (healing, own cover, lyrics) works for it as for any song.
     * Returns the path inside the main folder.
     */
    fun addDownloaded(
        item: app.malachite.core.online.OnlineCatalog.Item,
        http: app.malachite.core.net.Http,
        onProgress: (Float) -> Unit,
    ): Result<String> = runCatching {
        val url = item.downloadUrl ?: error(tr("Автор не разрешил скачивание"))
        val r = root ?: error(tr("Нет главной папки"))
        val sf = safe ?: error(tr("Нет главной папки"))
        val st = store ?: error(tr("Нет главной папки"))
        // the extension comes from the catalogue: only letters and digits, so it can never be a path
        val ext = item.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "mp3"
        val tmp = File(st.dataDir, "incoming/" + System.nanoTime() + "." + ext)
        try {
            val whole = http.downloadTo(url, tmp) { done, total -> onProgress(if (total > 0) done.toFloat() / total else -1f); true }
            if (!whole) error(tr("Загрузка оборвалась"))
            val format = app.malachite.core.audio.AudioFormat.detect(tmp)
            if (format == app.malachite.core.audio.AudioFormat.UNKNOWN) error(tr("Это не музыкальный файл"))
            val planned = TrackInfo(path = "song." + format.extension, title = item.title, artist = item.artist, album = item.album, format = format.name)
            val rel = LibraryLayout.expectedPath(planned, settings.value.layoutOptions(), LibraryLayout.Context(_tracks.value + planned))
            var target = File(r, rel)
            var n = 2
            while (target.exists()) { target = File(r, rel.substringBeforeLast('.') + " ($n)." + format.extension); n++ }
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) { tmp.copyTo(target); tmp.delete() }
            // what the file itself does not say yet is written into it
            val cur = app.malachite.core.audio.TagIO.read(target)
            val tags = TagEdit(
                title = item.title.takeIf { it.isNotBlank() && cur?.title.isNullOrBlank() },
                artist = item.artist.takeIf { it.isNotBlank() && cur?.artist.isNullOrBlank() },
                album = item.album.takeIf { it.isNotBlank() && cur?.album.isNullOrBlank() },
            )
            if (!tags.isEmpty) sf.writeTags(target, tags, r)
            if (item.coverUrl != null && cur?.hasArtwork != true) {
                http.download(item.coverUrl!!)?.let { AndroidImagePrep.prepare(it, square = true) }?.let { sf.writeTags(target, TagEdit(artwork = it), r) }
            }
            val path = target.relativeTo(r).invariantSeparatorsPath
            updateData { it.update(path) { u -> u.copy(addedAt = System.currentTimeMillis(), coverSource = item.source, coverChosen = false) } }
            refreshTrack(path)
            notifySystem(listOf(target))
            AppLog.i("online", "Скачано из ${item.source}: $path")
            path
        } finally {
            tmp.delete()
        }
    }.onFailure { AppLog.w("online", "Не скачалось «${item.artist} — ${item.title}»: ${it.message}") }

    /** Changes whenever a playlist picture is set or removed (screens re-read the file). */
    private val _playlistCovers = MutableStateFlow(0)
    val playlistCovers: StateFlow<Int> = _playlistCovers.asStateFlow()

    fun playlistCover(name: String): File? = store?.playlistCoverFile(name)?.takeIf { it.isFile }

    fun setPlaylistCover(name: String, jpeg: ByteArray?) {
        store?.setPlaylistCover(name, jpeg)
        _playlistCovers.value++
    }

    fun renamePlaylist(p: Playlist, newName: String) {
        store?.renamePlaylist(p, newName, trackMap())
        _playlists.value = store?.listPlaylists().orEmpty()
    }

    fun reloadData() {
        val st = store ?: return
        _data.value = st.loadLibrary()
        _playlists.value = st.listPlaylists()
    }

    /**
     * The song at [oldPath] lives in [newFile] now (inside the main folder; e.g. after it was
     * converted): its plays, favourite mark and places in playlists move along. Returns its new path.
     */
    suspend fun swapFile(oldPath: String, newFile: File): String? = withContext(Dispatchers.IO) {
        val r = root ?: return@withContext null
        val rel = newFile.relativeTo(r).invariantSeparatorsPath
        if (rel != oldPath) store?.applyMoves(mapOf(oldPath to rel), _tracks.value.associateBy { it.path })
        refreshTrack(oldPath)
        refreshTrack(rel)
        reloadData()
        notifySystem(listOf(File(r, oldPath), newFile))
        rel
    }
}
