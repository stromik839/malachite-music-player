package app.malachite.core.library

import app.malachite.core.lyrics.Lrc
import app.malachite.core.lyrics.Lyrics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Everything the player remembers lives in the main folder, so that zipping the folder and
 * unpacking it on another phone brings back favourites, lyrics, sync timings, playlists and
 * settings:
 *
 *     <main>/.malachite/library.json      favourites, play counts, lyrics links, import history
 *     <main>/.malachite/settings.json     copy of the app settings
 *     <main>/.malachite/index.json        cache of tags (rebuilt automatically)
 *     <main>/.malachite/lyrics/<id>.lrc   lyrics, readable by other players
 *     <main>/.malachite/lyrics/<id>.json  exact timings (ms, line ends, words)
 *     <main>/_Playlists/<name>.json       playlists (+ .m3u8 twins)
 */
class LibraryStore(val root: File) {

    val dataDir = File(root, LibraryLayout.DATA_DIR)
    val lyricsDir = File(dataDir, "lyrics")
    val playlistsDir = File(root, LibraryLayout.PLAYLISTS_DIR)
    private val libraryFile = File(dataDir, "library.json")
    private val indexFile = File(dataDir, "index.json")
    val settingsFile = File(dataDir, "settings.json")
    val markerFile = File(dataDir, MARKER)

    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val compactJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun ensureCreated(deviceName: String) {
        dataDir.mkdirs()
        lyricsDir.mkdirs()
        playlistsDir.mkdirs()
        // keep other media apps from indexing our data folder
        File(dataDir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        if (!markerFile.exists()) markerFile.writeText("Malachite main music folder. Do not delete.\n")
        if (!libraryFile.exists()) saveLibrary(LibraryData(createdAt = System.currentTimeMillis(), device = deviceName))
    }

    // ---------------------------------------------------------------- library.json

    @Synchronized
    fun loadLibrary(): LibraryData = readJson(libraryFile, LibraryData.serializer()) ?: LibraryData()

    /** Sealed while the player's data is protected with a password (see [app.malachite.core.fs.Sealed]). */
    @Synchronized
    fun saveLibrary(data: LibraryData) = writeAtomicBytes(libraryFile, app.malachite.core.fs.Sealed.encodeForWrite(json.encodeToString(LibraryData.serializer(), data)))

    @Synchronized
    fun updateLibrary(f: (LibraryData) -> LibraryData): LibraryData {
        val d = f(loadLibrary())
        saveLibrary(d)
        return d
    }

    // ---------------------------------------------------------------- index cache

    fun loadIndex(): List<TrackInfo> = readJson(indexFile, ListSerializer(TrackInfo.serializer())) ?: emptyList()

    fun saveIndex(tracks: List<TrackInfo>) =
        writeAtomic(indexFile, compactJson.encodeToString(ListSerializer(TrackInfo.serializer()), tracks))

    // ---------------------------------------------------------------- lyrics

    private fun lyricsIdFor(path: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        val name = LibraryLayout.sanitize(path.substringAfterLast('/').substringBeforeLast('.'), 60)
        return name + "_" + md.take(4).joinToString("") { "%02x".format(it) }
    }

    fun saveLyrics(track: TrackInfo, lyrics: Lyrics, source: String): String {
        lyricsDir.mkdirs()
        val existing = loadLibrary().user(track.path).lyricsId
        val id = existing ?: lyricsIdFor(track.path)
        writeAtomic(File(lyricsDir, "$id.lrc"), Lrc.write(lyrics, track.title, track.artist, track.album))
        val stored = StoredLyrics(track.path, track.title, track.artist, source, System.currentTimeMillis(), lyrics)
        writeAtomic(File(lyricsDir, "$id.json"), json.encodeToString(StoredLyrics.serializer(), stored))
        updateLibrary { it.update(track.path) { u -> u.copy(lyricsId = id, lyricsSource = source, noLyrics = false, lyricsOffsetMs = lyrics.offsetMs) } }
        return id
    }

    fun loadLyrics(track: TrackInfo): Lyrics? = loadLyrics(loadLibrary().user(track.path))

    /** The saved words of a song by what the library knows about it (no need to read the library again). */
    fun loadLyrics(u: TrackUserData): Lyrics? {
        val id = u.lyricsId ?: return null
        val stored = readJson(File(lyricsDir, "$id.json"), StoredLyrics.serializer())
        val l = stored?.lyrics ?: File(lyricsDir, "$id.lrc").takeIf { it.isFile }?.let { Lrc.parse(it.readText(), u.lyricsSource ?: "") }
        return l?.withOffset(u.lyricsOffsetMs)
    }

    fun deleteLyrics(track: TrackInfo) {
        val id = loadLibrary().user(track.path).lyricsId ?: return
        File(lyricsDir, "$id.lrc").delete()
        File(lyricsDir, "$id.json").delete()
        updateLibrary { it.update(track.path) { u -> u.copy(lyricsId = null, lyricsSource = null, lyricsOffsetMs = 0) } }
    }

    fun setLyricsOffset(track: TrackInfo, offsetMs: Long) {
        updateLibrary { it.update(track.path) { u -> u.copy(lyricsOffsetMs = offsetMs) } }
    }

    // ---------------------------------------------------------------- playlists

    fun listPlaylists(): List<Playlist> =
        playlistsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { readJson(it, Playlist.serializer()) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    private fun playlistFile(name: String) = File(playlistsDir, LibraryLayout.sanitize(name).ifEmpty { "Playlist" } + ".json")

    fun savePlaylist(p: Playlist, tracks: Map<String, TrackInfo>) {
        playlistsDir.mkdirs()
        val entries = p.entries.mapIndexed { i, e ->
            val t = tracks[e.relativePath]
            e.copy(
                position = i + 1,
                path = File(root, e.relativePath).absolutePath,
                title = t?.displayTitle ?: e.title,
                artist = t?.displayArtist ?: e.artist,
                album = t?.album ?: e.album,
                durationMs = t?.durationMs ?: e.durationMs,
            )
        }
        val now = System.currentTimeMillis()
        val out = p.copy(
            entries = entries,
            updatedAt = now,
            createdAtIso = p.createdAtIso.ifEmpty { iso(p.createdAt) },
        )
        writeAtomicBytes(playlistFile(p.name), app.malachite.core.fs.Sealed.encodeForWrite(json.encodeToString(Playlist.serializer(), out)))
        val twin = File(playlistsDir, playlistFile(p.name).nameWithoutExtension + ".m3u8")
        // a protected playlist has no readable twin: it would give the list away
        if (app.malachite.core.fs.Sealed.sealNew) { twin.delete(); return }
        // .m3u8 twin with relative paths – understood by most players and PCs
        val m3u = StringBuilder("#EXTM3U\n#PLAYLIST:${p.name}\n")
        for (e in entries) {
            m3u.append("#EXTINF:").append(e.durationMs / 1000).append(',').append(e.artist).append(" - ").append(e.title).append('\n')
            m3u.append("../").append(e.relativePath).append('\n')
        }
        writeAtomic(twin, m3u.toString())
    }

    /** The playlist's own picture: `_Playlists/<name>.cover.jpg` (travels with the main folder). */
    fun playlistCoverFile(name: String) = File(playlistsDir, playlistFile(name).nameWithoutExtension + ".cover.jpg")

    /** Stores (or with null removes) the picture of a playlist. */
    fun setPlaylistCover(name: String, jpeg: ByteArray?) {
        val f = playlistCoverFile(name)
        if (jpeg == null) { f.delete(); return }
        playlistsDir.mkdirs()
        val tmp = File(playlistsDir, f.name + ".tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun deletePlaylist(name: String) {
        val f = playlistFile(name)
        f.delete()
        File(playlistsDir, f.nameWithoutExtension + ".m3u8").delete()
        playlistCoverFile(name).delete()
    }

    fun renamePlaylist(old: Playlist, newName: String, tracks: Map<String, TrackInfo>) {
        val cover = playlistCoverFile(old.name).takeIf { it.isFile }?.readBytes()
        deletePlaylist(old.name)
        savePlaylist(old.copy(name = newName), tracks)
        if (cover != null) setPlaylistCover(newName, cover)
    }

    /** Applies file moves to every playlist. */
    fun remapPlaylists(moves: Map<String, String>, tracks: Map<String, TrackInfo>) {
        if (moves.isEmpty()) return
        for (p in listPlaylists()) {
            if (p.entries.none { it.relativePath in moves }) continue
            savePlaylist(p.copy(entries = p.entries.map { e -> moves[e.relativePath]?.let { e.copy(relativePath = it) } ?: e }), tracks)
        }
    }

    /** Writes every playlist again (after protection was switched off: their .m3u8 twins come back). */
    fun resavePlaylists(tracks: Map<String, TrackInfo>) = listPlaylists().forEach { savePlaylist(it, tracks) }

    /** Remaps lyrics/favourites/playlists after files were moved by the healer. */
    fun applyMoves(moves: Map<String, String>, tracks: Map<String, TrackInfo>) {
        if (moves.isEmpty()) return
        updateLibrary { it.remap(moves) }
        remapPlaylists(moves, tracks)
    }

    // ---------------------------------------------------------------- settings mirror

    fun saveSettingsJson(text: String) = writeAtomic(settingsFile, text)
    fun loadSettingsJson(): String? = settingsFile.takeIf { it.isFile }?.readText()

    // ---------------------------------------------------------------- helpers

    private fun <T> readJson(f: File, s: KSerializer<T>): T? = try {
        if (f.isFile) json.decodeFromString(s, app.malachite.core.fs.Sealed.readText(f)) else null
    } catch (e: app.malachite.core.fs.Sealed.Locked) {
        // protected and not unlocked: never treat it as empty (an empty library would be saved over it)
        throw e
    } catch (e: Exception) {
        // keep the broken file for inspection, never overwrite it silently
        f.copyTo(File(f.parentFile, f.name + ".broken"), overwrite = true)
        null
    }

    companion object {
        const val MARKER = "MALACHITE_LIBRARY"

        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** Writes via a temp file + rename so a crash never leaves a half-written config. */
        fun writeAtomic(f: File, text: String) = writeAtomicBytes(f, text.toByteArray(Charsets.UTF_8))

        fun writeAtomicBytes(f: File, bytes: ByteArray) = synchronized(writeLock(f)) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("cannot write ${f.name}")
            }
        }

        /**
         * One writer per file at a time: two writes of the same file at once shared its temp file,
         * and one of them failed ("cannot write …") or left the other's half-written bytes.
         */
        private val writeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun writeLock(f: File): Any = writeLocks.getOrPut(f.absolutePath) { Any() }

        /** Is [dir] a Malachite main folder (e.g. unpacked from a zip on a new phone)? */
        fun isLibraryRoot(dir: File) = File(File(dir, LibraryLayout.DATA_DIR), MARKER).isFile ||
            File(File(dir, LibraryLayout.DATA_DIR), "library.json").isFile
    }
}
