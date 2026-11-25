package app.malachite.core.library

import kotlinx.serialization.Serializable

/** A song file inside the main folder, as read from disk (cached in `.malachite/index.json`). */
@Serializable
data class TrackInfo(
    /** Path relative to the main folder, with '/' separators: "Artist/Album/Title.mp3". */
    val path: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val size: Long = 0,
    val lastModified: Long = 0,
    /** [app.malachite.core.audio.AudioFormat] name of the *real* format. */
    val format: String = "",
    val hasArtwork: Boolean = false,
    val bitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    /**
     * Fields that are empty in the file's tags and were taken from the folder layout
     * ("album", "artist"), e.g. "album". Healing can write them into the file.
     */
    val inferred: String = "",
) {
    val fileName get() = path.substringAfterLast('/')
    val extension get() = fileName.substringAfterLast('.', "").lowercase()

    /**
     * Title to show (falls back to the file name). Worked out once: the check for broken encodings
     * ran at every look, and a list of songs looked at each title several times per row.
     */
    val displayTitle: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        app.malachite.core.text.Mojibake.fixOrSelf(title).ifBlank { fileName.substringBeforeLast('.') }
    }
    val displayArtist: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        app.malachite.core.text.Mojibake.fixOrSelf(artist.ifBlank { albumArtist })
    }
    /** The artist the album belongs to (album artist if present). */
    val groupArtist get() = albumArtist.ifBlank { artist }

    val id: Long get() = path.hashCode().toLong() and 0xFFFFFFFFL
}

/** Things the user did with a song. Keyed by [TrackInfo.path] in [LibraryData.tracks]. */
@Serializable
data class TrackUserData(
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,
    val addedAt: Long = 0,
    /** File name inside `.malachite/lyrics/` (without extension), if lyrics were saved. */
    val lyricsId: String? = null,
    val lyricsSource: String? = null,
    val lyricsOffsetMs: Long = 0,
    /** The lyrics' own pace (1.01: a hundredth faster), kept apart like the offset; see Lyrics.retimed. */
    val lyricsTempo: Float = 1f,
    /** User marked "this song has no lyrics" – do not search again. */
    val noLyrics: Boolean = false,
    val coverSource: String? = null,
    /** The user picked this cover (search, own picture, one cover for several songs): healing never swaps it. */
    val coverChosen: Boolean = false,
    /** Remembered playback speed / pitch for this song (null = use global). */
    val speed: Float? = null,
    val pitchSemitones: Float? = null,
    /** Where the file came from when it was imported. */
    val importedFrom: String? = null,
    /** Favourite lyric lines, as [app.malachite.core.lyrics.lineMarkKeys] gives them (words + which repeat). */
    val markedLines: List<String> = emptyList(),
    /** Measured loudness in LUFS (healing "Громкость"); the player levels songs by it. */
    val loudness: Float? = null,
    /** "Не нравится": not played in shuffle and left out of the smart playlists; the file stays. */
    val disliked: Boolean = false,
    /** The best moment of the song (the chorus), in ms; null: not marked. */
    val bestMomentMs: Long? = null,
)

/** Content of `.malachite/library.json`. */
@Serializable
data class LibraryData(
    val version: Int = 1,
    val createdAt: Long = 0,
    val device: String = "",
    val tracks: Map<String, TrackUserData> = emptyMap(),
    /** Paths of files outside the main folder that were already imported (never import twice). */
    val importedSources: Map<String, String> = emptyMap(),
    /** Source files the user chose to ignore. */
    val ignoredSources: Set<String> = emptySet(),
    /** Artists the user does not like ([app.malachite.core.text.TextFold.strictKey] of the name) and their names as shown. */
    val dislikedArtists: Map<String, String> = emptyMap(),
) {
    fun user(path: String) = tracks[path] ?: TrackUserData()

    /** The song or its artist is marked "Не нравится". */
    fun disliked(t: TrackInfo): Boolean = user(t.path).disliked || artistDisliked(t)

    fun artistDisliked(t: TrackInfo): Boolean = dislikedArtists.isNotEmpty() &&
        (artistDisliked(t.displayArtist) || artistDisliked(t.groupArtist))

    fun artistDisliked(name: String): Boolean =
        name.isNotBlank() && app.malachite.core.text.TextFold.strictKey(name) in dislikedArtists

    fun dislikeArtist(name: String, on: Boolean): LibraryData {
        val key = app.malachite.core.text.TextFold.strictKey(name)
        if (key.isEmpty()) return this
        return copy(dislikedArtists = if (on) dislikedArtists + (key to name.trim()) else dislikedArtists - key)
    }

    fun update(path: String, f: (TrackUserData) -> TrackUserData) = copy(tracks = tracks + (path to f(user(path))))

    /** Rewrites every reference after a file was moved / renamed inside the library. */
    fun remap(moves: Map<String, String>): LibraryData {
        if (moves.isEmpty()) return this
        val newTracks = LinkedHashMap<String, TrackUserData>()
        for ((k, v) in tracks) {
            val nk = moves[k] ?: k
            val existing = newTracks[nk]
            newTracks[nk] = if (existing == null) v else mergeUser(existing, v)
        }
        return copy(
            tracks = newTracks,
            importedSources = importedSources.mapValues { (_, v) -> moves[v] ?: v },
        )
    }

    companion object {
        /** When two duplicates are merged, keep the union of what the user did. */
        fun mergeUser(a: TrackUserData, b: TrackUserData) = a.copy(
            favorite = a.favorite || b.favorite,
            playCount = a.playCount + b.playCount,
            lastPlayed = maxOf(a.lastPlayed, b.lastPlayed),
            addedAt = listOf(a.addedAt, b.addedAt).filter { it > 0 }.minOrNull() ?: 0,
            lyricsId = a.lyricsId ?: b.lyricsId,
            lyricsSource = a.lyricsSource ?: b.lyricsSource,
            // the shift and the pace belong to the lyrics kept
            lyricsOffsetMs = if (a.lyricsId != null) a.lyricsOffsetMs else b.lyricsOffsetMs,
            lyricsTempo = if (a.lyricsId != null) a.lyricsTempo else b.lyricsTempo,
            coverSource = a.coverSource ?: b.coverSource,
            coverChosen = a.coverChosen || b.coverChosen,
            speed = a.speed ?: b.speed,
            pitchSemitones = a.pitchSemitones ?: b.pitchSemitones,
            markedLines = (a.markedLines + b.markedLines).distinct(),
            loudness = a.loudness ?: b.loudness,
            disliked = a.disliked || b.disliked,
            bestMomentMs = a.bestMomentMs ?: b.bestMomentMs,
        )
    }
}

@Serializable
data class PlaylistEntry(
    /** 1-based position in the playlist / queue. */
    val position: Int,
    /** Absolute path on the device where the playlist was last saved. */
    val path: String,
    /** Path relative to the main folder – used to find the song after moving to another device. */
    val relativePath: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
)

/** A playlist file: `<main>/_Playlists/<name>.json` (+ a `.m3u8` twin for other players). */
@Serializable
data class Playlist(
    val name: String,
    val createdAt: Long,
    val createdAtIso: String = "",
    val updatedAt: Long = createdAt,
    val description: String = "",
    val entries: List<PlaylistEntry> = emptyList(),
)

/** Exact lyrics timings kept next to the .lrc file: `.malachite/lyrics/<id>.json`. */
@Serializable
data class StoredLyrics(
    val trackPath: String,
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val savedAt: Long = 0,
    val lyrics: app.malachite.core.lyrics.Lyrics,
)
