package app.malachite.core.heal

import app.malachite.core.i18n.tr
import app.malachite.core.audio.AudioFormat
import app.malachite.core.library.LibraryData
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.TrackInfo
import app.malachite.core.net.QueryVariants
import app.malachite.core.text.MatchConfidence
import app.malachite.core.text.NameGroup
import app.malachite.core.text.NameGrouping
import app.malachite.core.text.NameVariant
import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

enum class HealKind {
    JUNK, FORMATS, MISSING, NAMES, FILENAMES, DUPLICATES, COVERS, LAYOUT, LYRICS, COMPILATIONS, BROKEN,
    COVER_QUALITY, TRACK_NUMBERS, TITLE_NUMBERS, LETTER_CASE, LOUDNESS,
    /** Songs recognised by their sound (AcoustID). */
    SOUND_ID,
}

/**
 * What a song turned out to be by its sound: the fields to write ([title], [artist], [album];
 * null = leave as it is), how sure the service was, and where it came from.
 */
@kotlinx.serialization.Serializable
data class SoundMatch(
    val path: String,
    val oldTitle: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val score: Double,
    val confident: Boolean,
    val selected: Boolean = confident,
)

/** An album (or a single song) whose cover is too small to look good or needlessly heavy. */
@kotlinx.serialization.Serializable
data class CoverIssue(
    val id: String,
    val artist: String,
    val album: String,
    val title: String,
    val paths: List<String>,
    val width: Int,
    val height: Int,
    val bytes: Int,
    /** true: too heavy, made lighter here; false: too small, a better one is looked for online. */
    val heavy: Boolean,
    val selected: Boolean = true,
    /**
     * The same picture is on songs of [sharedBy] different albums (a whole discography downloaded
     * with one picture, a site's logo on every file): the album's own cover is looked for online.
     */
    val sharedBy: Int = 0,
) {
    val shared get() = sharedBy > 1
}

/** What a song's cover is: its size in pixels and in bytes, and a fingerprint of the picture. */
data class CoverLook(val width: Int, val height: Int, val bytes: Int, val hash: String = "")

/** "01 - Песня": the number belongs to the track number, not to the title. */
@kotlinx.serialization.Serializable
data class TitleNumberFix(val path: String, val oldTitle: String, val newTitle: String, val track: Int?, val selected: Boolean = true)

/** Which tag a letter-case fix changes. */
enum class CaseField { TITLE, ARTIST, ALBUM }

/** "НАЗВАНИЕ КАПСОМ" or "всё с маленькой" – offered, never applied without a look (names are often styled so on purpose). */
@kotlinx.serialization.Serializable
data class CaseFix(val id: String, val field: CaseField, val old: String, val new: String, val paths: List<String>, val selected: Boolean = false)

/** A track number taken from the album's list of songs. */
@kotlinx.serialization.Serializable
data class TrackNumberFix(val path: String, val title: String, val track: Int)

/** The songs of one album numbered by its list of songs found online. */
@kotlinx.serialization.Serializable
data class AlbumNumbering(val id: String, val artist: String, val album: String, val source: String, val fixes: List<TrackNumberFix>, val selected: Boolean = true)

/**
 * A compilation split into pieces: one album title, songs by many artists, and nothing that
 * holds them together – so the player shows a separate "album" for every artist. Giving all of
 * them one album artist ("Разные исполнители") puts the album back together.
 */
@kotlinx.serialization.Serializable
data class CompilationFix(
    val id: String,
    val album: String,
    val paths: List<String>,
    val artists: List<String>,
    /** The name says "compilation", or many artists share the album evenly. */
    val confident: Boolean,
    val selected: Boolean = confident,
)

/** A file that cannot be played: empty, cut off, or without readable sound. */
@kotlinx.serialization.Serializable
data class BrokenFile(val path: String, val reason: String, val selected: Boolean)

/** A file whose extension lies about its real format ("song.wav" that is really MP3). */
@kotlinx.serialization.Serializable
data class FormatFix(val path: String, val real: AudioFormat, val newPath: String)

enum class NameField { ARTIST, ALBUM, TITLE }

/**
 * Several spellings of one artist/album/title that should become [canonical].
 * [selected] is pre-set only for confident matches; "possible" ones wait for the user.
 */
@kotlinx.serialization.Serializable
data class NameFix(
    val id: String,
    val field: NameField,
    val variants: List<NameVariant>,
    val canonical: String,
    val confidence: MatchConfidence,
    /** For albums: the (canonical) artist they belong to. */
    val context: String,
    val tracks: List<String>,
    val selected: Boolean = confidence != MatchConfidence.POSSIBLE,
) {
    val rawValues: Set<String> get() = variants.map { it.raw }.toSet()
}

/** Copies of the same song. [keep] stays, [remove] go to the recoverable trash. */
@kotlinx.serialization.Serializable
data class DuplicateGroup(
    val id: String,
    val keep: String,
    val remove: List<String>,
    /** All files have bit-identical audio (only tags/names differ). */
    val identicalAudio: Boolean,
    val selected: Boolean = identicalAudio,
)

/** An album (or a single song without album) that has no cover. */
@kotlinx.serialization.Serializable
data class CoverJob(
    val id: String,
    val artist: String,
    val album: String,
    val title: String,
    val tracks: List<String>,
    /** A track of the same album that already has a cover – reuse it, no internet needed. */
    val donor: String? = null,
    /** A cover picture lying next to the songs (absolute path), e.g. "cover.jpg" of the album folder. */
    val imageFile: String? = null,
)

@kotlinx.serialization.Serializable
data class LayoutMove(val from: String, val to: String)

/** A file in the main folder that is not music (a video, a messenger cache file, a voice note…). */
@kotlinx.serialization.Serializable
data class JunkItem(val path: String, val reason: String, val sourcePath: String?, val selected: Boolean)

/**
 * An album / artist that is empty in the file's tags but known from somewhere else:
 * Android's own tag reader, the folder inside the main folder, or the folder the song was
 * imported from ("Boris_Kuzmin/Время выткало нить/...").
 */
@kotlinx.serialization.Serializable
data class TagSuggestion(
    val path: String,
    val album: String?,
    val artist: String?,
    val source: String,
    val confident: Boolean,
    val selected: Boolean = confident,
    /** The album tag is not empty but differs from the user's own folder: replace it. */
    val overwrite: Boolean = false,
    /** Release year for a file whose year tag is empty or only holds a download date ("20250227"). */
    val year: String? = null,
    /** The artist tag is not empty but junk (a site, a placeholder): replace it. */
    val overwriteArtist: Boolean = false,
    /** A new title ("Судно (Sudno)" out of "Молчат Дома - Судно (Sudno)"); null: the title stays. */
    val title: String? = null,
)

/**
 * A file with a meaningless name ("2_3049492992.mp3") whose tags hold the real title:
 * it is renamed to the title from inside the file, staying in the same folder.
 */
@kotlinx.serialization.Serializable
data class FileRename(val path: String, val newPath: String, val title: String) {
    val oldName get() = path.substringAfterLast('/')
    val newName get() = newPath.substringAfterLast('/')
}

object HealPlanner {

    // ------------------------------------------------------------------ formats

    /**
     * Compilations whose songs are scattered over one "album" per artist: the same album title,
     * at least three artists, none of them holding most of the songs, no common album artist.
     */
    fun compilations(tracks: List<TrackInfo>): List<CompilationFix> {
        val skip = setOf(TextFold.strictKey(app.malachite.core.library.LibraryLayout.UNKNOWN_ALBUM), TextFold.strictKey(app.malachite.core.library.LibraryLayout.SINGLES))
        return tracks.filter { it.album.isNotBlank() }
            .groupBy { TextFold.strictKey(Watermarks.albumBase(it.album)) }
            .filter { (key, _) -> key.isNotEmpty() && key !in skip }
            // "AyuGram", "Telegram Audio", a site: songs that only came the same way, no album
            .filter { (_, ts) -> !app.malachite.core.library.FolderHints.isProgramFolder(ts.first().album) && OddTags.albumProblem(ts.first().album, "", "") == null }
            .mapNotNull { (key, ts) ->
                // already one album: every song has the same album artist
                val albumArtists = ts.map { TextFold.strictKey(it.albumArtist) }.toSet()
                if (albumArtists.size == 1 && albumArtists.first().isNotEmpty()) return@mapNotNull null
                val byArtist = ts.groupBy { TextFold.strictKey(Watermarks.primaryArtist(it.artist)) }.filterKeys { it.isNotEmpty() }
                val namedAsCompilation = app.malachite.core.cover.AlbumFinder.isCompilation(ts.first().album)
                val minArtists = if (namedAsCompilation) 2 else 3
                if (ts.size < 3 || byArtist.size < minArtists) return@mapNotNull null
                // one artist with most songs and a few guests is that artist's own album, not a compilation
                val biggest = byArtist.values.maxOf { it.size }
                if (!namedAsCompilation && biggest * 2 > ts.size) return@mapNotNull null
                CompilationFix(
                    id = "comp:$key",
                    album = ts.groupingBy { it.album }.eachCount().maxByOrNull { it.value }!!.key,
                    paths = ts.map { it.path },
                    artists = byArtist.values.map { group -> group.first().artist }.sorted(),
                    confident = namedAsCompilation || byArtist.size >= 4,
                )
            }
            .sortedBy { TextFold.strictKey(it.album) }
    }

    // ------------------------------------------------------------------ numbers in titles

    /**
     * "01. Песня", "01) Песня", "01 - Песня", "01_Песня". A number glued to a word ("7-я пятница",
     * "99 Luftballons", "1979") is part of the title and stays.
     */
    private val TITLE_NUMBER = Regex("^\\s*(\\d{1,3})(?:\\s*[.)]\\s+|\\s+[-–—]\\s+|\\s*_\\s*)(\\S.*)$")

    fun titleNumbers(tracks: List<TrackInfo>): List<TitleNumberFix> = tracks.mapNotNull { t ->
        val m = TITLE_NUMBER.matchEntire(t.title) ?: return@mapNotNull null
        val n = m.groupValues[1].toInt()
        val rest = m.groupValues[2].trim()
        if (rest.length < 2 || rest.all { it.isDigit() }) return@mapNotNull null
        // the number agrees with the tag, or there is no number in the tag yet
        val sure = t.track == 0 || t.track == n
        TitleNumberFix(t.path, t.title, rest, track = n.takeIf { t.track == 0 && it in 1..199 }, selected = sure)
    }

    // ------------------------------------------------------------------ letter case

    private val SMALL_WORDS = setOf("a", "an", "the", "of", "and", "or", "in", "on", "at", "to", "for", "by", "with", "feat", "feat.", "ft", "ft.", "vs", "vs.")

    /** A name in normal letters: Latin words in Title Case, Cyrillic as a sentence; short Latin abbreviations stay. */
    fun normalCase(s: String): String {
        val words = s.split(' ')
        var first = true
        return words.joinToString(" ") { w ->
            if (w.isEmpty()) return@joinToString w
            val letters = w.filter { it.isLetter() }
            val latin = letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }
            val out = when {
                letters.isEmpty() || w.any { it.isDigit() } -> w
                // DJ, MC, UK, AC/DC: abbreviations written in capitals on purpose
                latin && letters.length <= 3 && letters.all { it.isUpperCase() } && s.any { it.isLowerCase() } -> w
                latin -> if (!first && w.lowercase() in SMALL_WORDS) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() }
                first -> w.lowercase().replaceFirstChar { it.uppercase() }
                else -> w.lowercase()
            }
            if (letters.isNotEmpty()) first = false
            out
        }
    }

    private fun needsCase(s: String): Boolean {
        val letters = s.filter { it.isLetter() && it.lowercaseChar() != it.uppercaseChar() }
        if (letters.length < 4) return false
        val allUpper = letters.all { it.isUpperCase() }
        val allLower = letters.all { it.isLowerCase() }
        return (allUpper && letters.length >= 5) || (allLower && s.trim().contains(' '))
    }

    fun letterCase(tracks: List<TrackInfo>): List<CaseFix> {
        val out = ArrayList<CaseFix>()
        fun collect(field: CaseField, value: (TrackInfo) -> String) {
            tracks.filter { needsCase(value(it)) }.groupBy { value(it).trim() }.forEach { (old, ts) ->
                val new = normalCase(old)
                if (new != old) out += CaseFix("${field.name}|$old", field, old, new, ts.map { it.path })
            }
        }
        collect(CaseField.ARTIST) { it.artist }
        collect(CaseField.ALBUM) { it.album }
        collect(CaseField.TITLE) { it.title }
        return out
    }

    // ------------------------------------------------------------------ cover quality

    /**
     * Covers worth fixing, one per album: smaller than [minSide] pixels (looks blurry), or heavier
     * than [maxBytes] / larger than [maxSide] (every song of the album carries the whole picture).
     * [info] tells width, height and size of the cover of a song, or null when it has none.
     */
    fun coverQuality(
        tracks: List<TrackInfo>,
        info: (TrackInfo) -> CoverLook?,
        minSide: Int = 300,
        maxSide: Int = 2000,
        maxBytes: Int = 1_000_000,
        chosen: (TrackInfo) -> Boolean = { false },
    ): List<CoverIssue> {
        val albums = tracks.filter { it.hasArtwork }
            .groupBy { if (it.album.isBlank()) "song|" + it.path else TextFold.strictKey(it.groupArtist) + "|" + TextFold.strictKey(it.album) }
            .mapNotNull { (key, ts) -> info(ts.first())?.let { Triple(key, ts, it) } }
        // one picture on songs of different albums: which albums carry it (a cover the user chose is theirs)
        val sharing = albums.filter { (_, ts, look) -> look.hash.isNotEmpty() && ts.none(chosen) }
            .groupBy { it.third.hash }
            .mapValues { (_, list) -> list.map { (_, ts, _) -> ts.first().album.let { a -> if (a.isBlank()) "song|" + ts.first().path else TextFold.strictKey(a) } }.distinct().size }
        return albums.mapNotNull { (key, ts, look) ->
            val first = ts.first()
            val (w, h, size) = Triple(look.width, look.height, look.bytes)
            val small = maxOf(w, h) in 1 until minSide
            val heavy = size > maxBytes || maxOf(w, h) > maxSide
            val sharedBy = if (ts.any(chosen)) 0 else sharing[look.hash] ?: 0
            if (!small && !heavy && sharedBy < 2) return@mapNotNull null
            CoverIssue(
                id = "cq|$key", artist = first.groupArtist, album = first.album, title = first.displayTitle,
                paths = ts.map { it.path }, width = w, height = h, bytes = size, heavy = heavy && !small && sharedBy < 2,
                sharedBy = if (sharedBy >= 2) sharedBy else 0,
            )
        }
    }

    // ------------------------------------------------------------------ track numbers

    /** Albums where most songs have no track number (so they play in a made-up order). */
    fun albumsWithoutNumbers(tracks: List<TrackInfo>): List<List<TrackInfo>> {
        val skip = setOf(TextFold.strictKey(app.malachite.core.library.LibraryLayout.UNKNOWN_ALBUM), TextFold.strictKey(app.malachite.core.library.LibraryLayout.SINGLES))
        return tracks.filter { it.album.isNotBlank() && TextFold.strictKey(it.album) !in skip }
            .groupBy { TextFold.strictKey(it.groupArtist) + "|" + TextFold.strictKey(it.album) }
            .values.filter { ts -> ts.size >= 2 && ts.count { it.track == 0 } * 2 >= ts.size }
    }

    /** Matches the songs of an album to its list of songs ([remote]: title -> number) by title. */
    fun matchNumbers(local: List<TrackInfo>, remote: List<Pair<String, Int>>): List<TrackNumberFix> {
        val used = HashSet<Int>()
        return local.mapNotNull { t ->
            val title = Watermarks.stripTrackNumber(Watermarks.searchTitle(t.displayTitle, t.groupArtist))
            val best = remote.withIndex().filter { it.index !in used }
                .maxByOrNull { app.malachite.core.net.QueryVariants.similarity(title, it.value.first) }
                ?: return@mapNotNull null
            if (app.malachite.core.net.QueryVariants.similarity(title, best.value.first) < 0.8) return@mapNotNull null
            used += best.index
            TrackNumberFix(t.path, t.displayTitle, best.value.second).takeIf { it.track != t.track }
        }
    }

    fun formats(tracks: List<TrackInfo>): List<FormatFix> = tracks.mapNotNull { t ->
        val real = runCatching { AudioFormat.valueOf(t.format) }.getOrNull() ?: return@mapNotNull null
        if (real == AudioFormat.UNKNOWN || AudioFormat.extensionMatches(t.extension, real)) return@mapNotNull null
        FormatFix(t.path, real, t.path.substringBeforeLast('.') + "." + real.extension)
    }

    // ------------------------------------------------------------------ names

    fun names(tracks: List<TrackInfo>, allowFuzzy: Boolean = true): List<NameFix> {
        val out = ArrayList<NameFix>()

        // Artists: primary artist of "artist" + "album artist" fields.
        val artistCounts = HashMap<String, Int>()
        for (t in tracks) {
            primaryRaw(t.artist)?.let { artistCounts.merge(it, 1, Int::plus) }
            t.albumArtist.trim().takeIf { it.isNotEmpty() }?.let { artistCounts.merge(it, 1, Int::plus) }
        }
        val artistGroups = NameGrouping.group(artistCounts.map { NameVariant(it.key, it.value) }, allowFuzzy)
        val artistMap = HashMap<String, String>() // raw -> canonical
        for ((i, g) in artistGroups.withIndex()) {
            val raws = g.variants.map { it.raw }.toSet()
            val affected = tracks.filter { primaryRaw(it.artist) in raws || it.albumArtist.trim() in raws }.map { it.path }
            if (affected.isEmpty()) continue
            out += NameFix("artist-$i", NameField.ARTIST, g.variants, g.canonical, g.confidence, "", affected)
            if (g.confidence != MatchConfidence.POSSIBLE) raws.forEach { artistMap[it] = g.canonical }
        }
        fun canonicalArtist(t: TrackInfo): String {
            val a = t.albumArtist.trim().ifEmpty { primaryRaw(t.artist).orEmpty() }
            return artistMap[a] ?: TextFold.cleanDisplay(a)
        }

        // Albums: grouped inside each artist.
        val byArtist = tracks.filter { it.album.isNotBlank() }.groupBy { TextFold.strictKey(canonicalArtist(it)) }
        var albumIdx = 0
        for ((_, ts) in byArtist) {
            val counts = ts.groupingBy { it.album.trim() }.eachCount()
            val groups = NameGrouping.group(counts.map { NameVariant(it.key, it.value) }, allowFuzzy)
            for (g in groups) {
                val raws = g.variants.map { it.raw }.toSet()
                val affected = ts.filter { it.album.trim() in raws }.map { it.path }
                out += NameFix("album-${albumIdx++}", NameField.ALBUM, g.variants, g.canonical, g.confidence, canonicalArtist(ts.first()), affected)
            }
        }

        // Titles: decoration and site watermarks, per track.
        for ((i, t) in tracks.withIndex()) {
            if (t.title.isBlank()) continue
            val cleaned = Watermarks.strip(TextFold.cleanDisplay(t.title))
            // "Свидетельство О Смерти — Подводная лодка" -> "Подводная лодка"
            val clean = app.malachite.core.library.FolderHints.stripArtistPrefix(cleaned, t.groupArtist.ifBlank { t.artist })
            if (clean != t.title && clean.isNotBlank()) {
                val conf = if (TextFold.strictKey(clean) == TextFold.strictKey(t.title)) MatchConfidence.EXACT else MatchConfidence.STRONG
                out += NameFix("title-$i", NameField.TITLE, listOf(NameVariant(t.title, 1)), clean, conf, t.displayArtist, listOf(t.path))
            }
        }
        // Missing or meaningless titles ("Track 01", "2_3049492992"): take them from the file name
        // ("01 - Artist - Song.mp3") when the file name itself is meaningful.
        for ((i, t) in tracks.withIndex()) {
            if (t.title.isNotBlank() && !isJunkName(t.title)) continue
            val base = Watermarks.stripTrackNumber(t.fileName.substringBeforeLast('.'))
            if (isJunkName(base)) continue
            val parts = base.split(" - ", " – ", " — ").map { it.trim() }.filter { it.isNotEmpty() }
            val title = Watermarks.strip(TextFold.cleanDisplay(parts.lastOrNull() ?: base))
            if (title.isNotBlank() && !isJunkName(title) && title != t.title) {
                // A file with no title at all, named "Song.mp3" or "Artist - Song.mp3" (the artist
                // matching the one we know): the name is the title. Anything else waits for review.
                val nameIsTitle = t.title.isBlank() && (
                    parts.size <= 1 ||
                        (parts.size == 2 && t.groupArtist.isNotBlank() && TextFold.strictKey(parts[0]) == TextFold.strictKey(t.groupArtist))
                    )
                val conf = if (nameIsTitle) MatchConfidence.STRONG else MatchConfidence.POSSIBLE
                out += NameFix("notitle-$i", NameField.TITLE, listOf(NameVariant(t.title, 1)), title, conf, t.displayArtist, listOf(t.path))
            }
        }
        return out
    }

    /** The primary artist exactly as written in the tag (without "feat. ..."). */
    fun primaryRaw(artist: String): String? {
        val a = artist.trim()
        if (a.isEmpty()) return null
        return Watermarks.primaryArtist(a).trim().ifEmpty { null }
    }

    /** Rewrites [artist] replacing its primary part, keeping a "feat. X" tail. */
    fun applyArtist(artist: String, map: Map<String, String>): String? {
        val primary = primaryRaw(artist) ?: return null
        val canonical = map[primary] ?: return null
        val tail = artist.trim().removePrefix(primary)
        val result = (canonical + tail).trim()
        return result.takeIf { it != artist }
    }

    // ------------------------------------------------------------------ duplicates

    /**
     * Finds copies of the same song. [fingerprint] returns the audio-payload hash of a track
     * (used only for candidates, so the whole library is not read).
     */
    fun duplicates(tracks: List<TrackInfo>, fingerprint: (TrackInfo) -> String?): List<DuplicateGroup> {
        val out = ArrayList<DuplicateGroup>()
        val used = HashSet<String>()
        // 1) Same audio regardless of tags: candidates share duration (±1 s).
        val byDuration = tracks.filter { it.durationMs > 0 }.groupBy { it.durationMs / 1000 }
        val fp = HashMap<String, String?>()
        fun fpOf(t: TrackInfo) = fp.getOrPut(t.path) { runCatching { fingerprint(t) }.getOrNull() }
        val sortedSecs = byDuration.keys.sorted()
        for (sec in sortedSecs) {
            val cands = (byDuration[sec].orEmpty() + byDuration[sec + 1].orEmpty()).distinctBy { it.path }
            if (cands.size < 2) continue
            val bySize = cands.groupBy { fpOf(it) }
            for ((hash, same) in bySize) {
                if (hash == null || same.size < 2) continue
                val fresh = same.filter { it.path !in used }
                if (fresh.size < 2) continue
                val keep = best(fresh)
                out += DuplicateGroup("same-${out.size}", keep.path, fresh.filter { it !== keep }.map { it.path }, identicalAudio = true)
                fresh.forEach { used += it.path }
            }
        }
        // 2) Same artist + same title + about the same length (another encoding of the same song).
        val byKey = tracks.filter { it.path !in used && it.title.isNotBlank() }.groupBy {
            TextFold.strictKey(Watermarks.primaryArtist(it.groupArtist)) + "|" + TextFold.strictKey(Watermarks.strip(it.title))
        }
        for ((key, ts) in byKey) {
            if (ts.size < 2 || key.startsWith("|")) continue
            val sorted = ts.sortedBy { it.durationMs }
            var cluster = mutableListOf(sorted.first())
            fun flush() {
                if (cluster.size >= 2) {
                    val keep = best(cluster)
                    out += DuplicateGroup("like-${out.size}", keep.path, cluster.filter { it !== keep }.map { it.path }, identicalAudio = false)
                }
            }
            fun albumKey(x: TrackInfo) = TextFold.strictKey(x.album)
            for (t in sorted.drop(1)) {
                val prev = cluster.last()
                // Same title on two different albums = different recordings, never "duplicates".
                val sameRelease = albumKey(t).isEmpty() || albumKey(prev).isEmpty() || albumKey(t) == albumKey(prev)
                if (t.durationMs - prev.durationMs <= 2500 && sameRelease) cluster += t else { flush(); cluster = mutableListOf(t) }
            }
            flush()
        }
        return out
    }

    private val LOSSLESS = setOf(AudioFormat.FLAC.name, AudioFormat.WAV.name, AudioFormat.AIFF.name, AudioFormat.APE.name, AudioFormat.WAVPACK.name, AudioFormat.DSF.name)

    /** The copy worth keeping: lossless > higher bitrate > has cover > complete tags > tidy path. */
    fun best(ts: List<TrackInfo>): TrackInfo = ts.maxByOrNull { t ->
        var s = 0.0
        if (t.format in LOSSLESS) s += 10_000
        s += t.bitrateKbps.coerceAtMost(2000)
        if (t.hasArtwork) s += 60
        if (t.title.isNotBlank()) s += 20
        if (t.artist.isNotBlank()) s += 20
        if (t.album.isNotBlank()) s += 20
        if (!TextFold.isDecorated(t.title) && !Watermarks.contains(t.title)) s += 10
        if (LibraryLayout.expectedPath(t) == t.path) s += 5
        if (Regex(" \\(\\d+\\)\\.[a-z0-9]+$").containsMatchIn(t.path)) s -= 5
        s
    }!!

    // ------------------------------------------------------------------ covers

    /**
     * [root] and [data] let the planner find cover pictures in the album folder and in the
     * folders the songs were imported from.
     */
    fun covers(tracks: List<TrackInfo>, root: java.io.File? = null, data: LibraryData? = null): List<CoverJob> {
        fun imageFor(ts: List<TrackInfo>, title: String?, album: String? = null): String? {
            val dirs = LinkedHashSet<java.io.File>()
            ts.forEach { t -> root?.let { dirs += java.io.File(it, t.path).parentFile } }
            ts.forEach { t -> data?.user(t.path)?.importedFrom?.let { dirs += java.io.File(it).parentFile } }
            for (d in dirs) app.malachite.core.library.FolderHints.coverImageIn(d, title, album)?.let { return it.absolutePath }
            return null
        }
        val out = ArrayList<CoverJob>()
        val albums = tracks.groupBy { TextFold.strictKey(it.groupArtist) + "|" + TextFold.strictKey(it.album) }
        for ((key, ts) in albums) {
            val missing = ts.filter { !it.hasArtwork }
            if (missing.isEmpty()) continue
            val first = missing.first()
            if (first.album.isBlank()) {
                // no album: every song is its own job (search by song title)
                missing.forEach { t ->
                    out += CoverJob("song-${t.path}", t.displayArtist, "", t.displayTitle, listOf(t.path), imageFile = imageFor(listOf(t), t.title))
                }
            } else {
                val donor = ts.firstOrNull { it.hasArtwork }?.path
                out += CoverJob("album-$key", first.groupArtist, first.album, first.displayTitle, missing.map { it.path }, donor, imageFor(ts, null, first.album))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ not music

    /**
     * Files that got into the main folder but are not music. [isVideo] checks a file for a real
     * video track. Videos and messenger cache files are pre-selected; the rest is for review.
     */
    fun junk(
        tracks: List<TrackInfo>,
        data: LibraryData,
        rules: app.malachite.core.library.ImportFilter.Rules,
        isVideo: (TrackInfo) -> Boolean,
    ): List<JunkItem> {
        val out = ArrayList<JunkItem>()
        for (t in tracks) {
            val src = data.user(t.path).importedFrom
            val srcName = src?.substringAfterLast('/') ?: t.fileName
            val format = runCatching { AudioFormat.valueOf(t.format) }.getOrDefault(AudioFormat.UNKNOWN)
            // by the real format, by the name the file has now, or by the name it was imported under
            val canBeVideo = format == AudioFormat.M4A || format == AudioFormat.THREE_GP || format == AudioFormat.MATROSKA ||
                t.extension in AudioFormat.VIDEO_CAPABLE ||
                (src?.substringAfterLast('.', "")?.lowercase() ?: "") in AudioFormat.VIDEO_CAPABLE
            val item = when {
                canBeVideo && runCatching { isVideo(t) }.getOrDefault(false) -> JunkItem(t.path, tr("видео"), src, true)
                app.malachite.core.library.ImportFilter.isMessengerName(srcName) ||
                    app.malachite.core.library.ImportFilter.isMessengerName(t.fileName) ->
                    JunkItem(t.path, tr("файл-кэш мессенджера ($srcName)"), src, t.title.isBlank() || isJunkName(t.title))
                format.voiceLike -> JunkItem(t.path, tr("голосовая запись (${format.extension})"), src, true)
                src != null && app.malachite.core.library.ImportFilter.checkPath(src, rules, null) ==
                    app.malachite.core.library.ImportFilter.Verdict.VOICE_MESSAGE -> JunkItem(t.path, tr("голосовое сообщение"), src, true)
                t.durationMs in 1 until rules.minDurationSec * 1000L -> JunkItem(t.path, tr("короче ${rules.minDurationSec} с"), src, false)
                src != null && !rules.importFromMessengers && app.malachite.core.library.ImportFilter.isFromMessenger(src) &&
                    !app.malachite.core.library.ImportFilter.isDeliberateSave(src) &&
                    !app.malachite.core.library.ImportFilter.isSongLike(srcName, t.title, t.artist.ifBlank { t.albumArtist }, t.durationMs) ->
                    JunkItem(t.path, tr("из мессенджера: ${src.substringBeforeLast('/').substringAfterLast('/')}"), src, false)
                else -> null
            }
            if (item != null) out += item
        }
        return out
    }

    // ------------------------------------------------------------------ missing album / artist

    /**
     * Songs whose album or artist is empty *in the file*. [androidTags] reads the file with
     * Android's own reader (it sometimes understands tags our parser cannot).
     */
    fun missingTags(
        tracks: List<TrackInfo>,
        data: LibraryData,
        androidTags: (TrackInfo) -> app.malachite.core.audio.TagData?,
    ): List<TagSuggestion> {
        val out = ArrayList<TagSuggestion>()
        val ctx = app.malachite.core.library.LibraryLayout.Context(tracks)
        for (t in tracks) {
            val inferred = t.inferred.split(',').toSet()
            val albumInFile = t.album.isNotBlank() && "album" !in inferred
            val artistInFile = (t.artist.isNotBlank() || t.albumArtist.isNotBlank()) && "artist" !in inferred
            // Year: the tag is empty or holds a download date. The YouTube description
            // ("Released on: …") or the year of the other songs of the album knows better.
            var year: String? = null
            var yearSource: String? = null
            if ("year" in inferred) {
                year = t.year; yearSource = tr("дата выхода из описания YouTube")
            } else if (app.malachite.core.library.LibraryLayout.reliableYear(t.year) == null && t.album.isNotBlank()) {
                ctx.albumYear(t)?.let { year = it; yearSource = tr("год альбома по другим его песням") }
            }
            if (albumInFile && artistInFile) {
                // The user's own folder ("Исполнитель/Альбом") says another album than the tag:
                // offered for review, never applied automatically.
                val h = data.user(t.path).importedFrom?.let { app.malachite.core.library.FolderHints.fromPath(it, t.groupArtist) }
                if (h != null && h.confident && h.album != null && TextFold.strictKey(h.album) != TextFold.strictKey(t.album)) {
                    out += TagSuggestion(
                        t.path, h.album, null,
                        tr("ваша прежняя папка «${h.album}», а в теге «${t.album}» (${h.note})"),
                        confident = false, overwrite = true,
                    )
                }
                if (year != null) out += TagSuggestion(t.path, null, null, yearSource!!, confident = true, year = year)
                continue
            }
            val android = runCatching { androidTags(t) }.getOrNull()
            val importedFrom = data.user(t.path).importedFrom
            val knownArtist = if (artistInFile) t.groupArtist else android?.artist?.takeIf { it.isNotBlank() } ?: t.artist
            val hint = importedFrom?.let { app.malachite.core.library.FolderHints.fromPath(it, knownArtist) }

            var album: String? = null
            var artist: String? = null
            val sources = ArrayList<String>()
            var confident = true
            if (!albumInFile) {
                when {
                    !android?.album.isNullOrBlank() -> { album = android!!.album; sources += tr("теги, прочитанные Android") }
                    "album" in inferred -> { album = t.album; sources += tr("папка альбома в главной папке") }
                    hint?.album != null -> { album = hint.album; sources += hint.note; if (!hint.confident) confident = false }
                }
            }
            if (!artistInFile) {
                when {
                    !android?.artist.isNullOrBlank() -> { artist = android!!.artist; sources += tr("теги, прочитанные Android") }
                    "artist" in inferred -> { artist = t.artist; sources += tr("папка исполнителя в главной папке") }
                    hint?.artist != null -> { artist = hint.artist; sources += hint.note; if (!hint.confident) confident = false }
                    else -> app.malachite.core.library.FolderHints.artistFromFileName(t.fileName)?.let {
                        artist = it; sources += tr("имя файла"); confident = false
                    }
                }
            }
            if (year != null) sources += yearSource!!
            if (album == null && artist == null && year == null) continue
            out += TagSuggestion(t.path, album, artist, sources.distinct().joinToString(", "), confident, year = year)
        }
        return out
    }

    // ------------------------------------------------------------------ file names

    /** Patterns that must match the *whole* name. */
    private val JUNK_FULL = listOf(
        // hashes / ids: 3f9a1c0e77b2, 5a8f-11e2-…
        Regex("(?=.*\\d)[0-9a-f_\\-]{8,}", RegexOption.IGNORE_CASE),
        // random ids: y7Hk2Pq09LmX3 (long, no spaces, many digits)
        Regex("(?=(?:.*\\d){4})[a-z0-9_]{12,}", RegexOption.IGNORE_CASE),
        // always meaningless, with or without a number
        Regex("(track|трек|audio|аудио|unknown( title| track)?|неизвестн\\p{L}*( трек)?|без названия|untitled|new recording)[\\s_\\-#№]*\\d*", RegexOption.IGNORE_CASE),
        // meaningless only together with a number ("Song 2", "Music" are real song titles, so they are not here)
        Regex("(voice|file|файл|document|документ|recording|запись|aud|ptt|msg|mp3)[\\s_\\-#№]*\\d+", RegexOption.IGNORE_CASE),
    )

    /** Patterns that only need to match the beginning: AUD-20240101-WA0003, audio_2024-01-01_12-00-00. */
    private val JUNK_PREFIX = listOf(
        Regex("^(audio|aud|ptt|voice|rec|vid|video|img|dl|download)[_\\-]\\d", RegexOption.IGNORE_CASE),
    )

    /** True for names that say nothing about the song: "2_3049492992", "Track 01", "AUD-20240101-WA0003". */
    fun isJunkName(raw: String?): Boolean {
        val s = TextFold.cleanDisplay(raw.orEmpty()).trim()
        if (s.isEmpty()) return true
        if (Regex("(19|20)\\d\\d").matches(s)) return false // a year is a real title ("1979")
        if (s.none { it.isLetter() }) return true // 2_3049492992, 001, 12-34
        return JUNK_FULL.any { it.matches(s) } || JUNK_PREFIX.any { it.containsMatchIn(s) }
    }

    /**
     * Files whose name differs from the song title written inside them. Only titles that are
     * themselves meaningful are used; the folder (artist/album) is left to the other healers.
     */
    fun filenames(tracks: List<TrackInfo>, options: LibraryLayout.Options = LibraryLayout.Options()): List<FileRename> {
        val taken = tracks.map { it.path.lowercase() }.toMutableSet()
        val out = ArrayList<FileRename>()
        for (t in tracks) {
            if (t.title.isBlank() || isJunkName(t.title)) continue
            val wanted = LibraryLayout.fileBaseName(t, options)
            val current = t.fileName.substringBeforeLast('.')
            if (current == wanted) continue
            if (Regex("^" + Regex.escape(wanted) + " \\(\\d+\\)$").matches(current)) continue
            val dir = t.path.substringBeforeLast('/', "")
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val ext = t.extension.let { if (it.isEmpty()) "" else ".$it" }
            var target = prefix + wanted + ext
            var n = 2
            while (target.lowercase() in taken && target.lowercase() != t.path.lowercase()) {
                target = "$prefix$wanted ($n)$ext"; n++
            }
            if (target == t.path) continue
            taken.remove(t.path.lowercase())
            taken += target.lowercase()
            out += FileRename(t.path, target, t.title)
        }
        return out
    }

    // ------------------------------------------------------------------ layout

    fun layout(
        tracks: List<TrackInfo>,
        options: LibraryLayout.Options = LibraryLayout.Options(),
        context: LibraryLayout.Context = LibraryLayout.Context(tracks),
    ): List<LayoutMove> {
        val taken = tracks.map { it.path.lowercase() }.toMutableSet()
        val out = ArrayList<LayoutMove>()
        for (t in tracks) {
            val expected = LibraryLayout.expectedPath(t, options, context)
            if (expected == t.path) continue
            // "Song (2).mp3" already sitting where it should, with a numbered name – leave it
            val numbered = Regex("^(.*) \\(\\d+\\)(\\.[^.]+)$").matchEntire(t.path)
            if (numbered != null && numbered.groupValues[1] + numbered.groupValues[2] == expected) continue
            var target = expected
            var n = 2
            while (target.lowercase() in taken) {
                target = expected.substringBeforeLast('.') + " ($n)." + expected.substringAfterLast('.')
                n++
            }
            taken.remove(t.path.lowercase())
            taken += target.lowercase()
            out += LayoutMove(t.path, target)
        }
        return out
    }

    // ------------------------------------------------------------------ lyrics

    /**
     * Songs to find the words for: those without them, and those whose words were found online in
     * another alphabet ([foreignLetters] reads the saved text; a text the user typed or synced is
     * never looked at).
     */
    fun lyrics(tracks: List<TrackInfo>, data: LibraryData, foreignLetters: (TrackInfo) -> Boolean = { false }): List<String> =
        tracks.filter { t ->
            val u = data.user(t.path)
            t.durationMs > 30_000 && when {
                u.lyricsId == null -> !u.noLyrics
                else -> u.lyricsSource in app.malachite.core.lyrics.LyricsScript.FOUND_SOURCES && foreignLetters(t)
            }
        }.map { it.path }

    /** Similarity helper exposed for UI ("why do we think these are the same"). */
    fun explain(a: String, b: String): String {
        val c = app.malachite.core.text.Similarity.sameName(a, b) ?: return tr("разные")
        return when (c) {
            MatchConfidence.EXACT -> tr("одинаковые (отличаются пробелами, регистром, украшениями или алфавитом)")
            MatchConfidence.STRONG -> tr("одинаковые (разная транслитерация)")
            MatchConfidence.POSSIBLE -> tr("похожие (опечатка в 1–3 буквах), сходство ${(QueryVariants.similarity(a, b) * 100).toInt()}%")
        }
    }
}
