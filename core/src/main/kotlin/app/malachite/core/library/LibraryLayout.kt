package app.malachite.core.library

import app.malachite.core.audio.AudioFormat
import app.malachite.core.text.Mojibake
import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

/**
 * Where a song belongs inside the main folder:
 *
 *     <main>/<Artist>/<Album (year)>/<Title>.<real extension>
 *     <main>/<Artist>/Синглы/<Title>.<ext>          songs without an album and singles
 *
 * e.g. /storage/emulated/0/Malachite/Вещие Ослы/Безнадёжность (2022)/Ботинки.mp3
 */
object LibraryLayout {

    const val DATA_DIR = ".malachite"
    const val PLAYLISTS_DIR = "_Playlists"
    const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
    const val UNKNOWN_ALBUM = "Без альбома"
    const val SINGLES = "Синглы"

    /** Folders inside the main folder that never contain songs. */
    val RESERVED = setOf(DATA_DIR, PLAYLISTS_DIR)

    data class Options(
        /** "01 - Title.mp3" instead of "Title.mp3". */
        val trackNumberPrefix: Boolean = false,
        /** "Безнадёжность (2022)" instead of "Безнадёжность" (only with a reliable year). */
        val yearInAlbumFolder: Boolean = true,
        /** Folder for songs without an album and for singles. */
        val singlesFolder: String = SINGLES,
    )

    /** A year that really is the release year: "2022", "2017-09-07T16:51". Not "20260813" (download date). */
    fun reliableYear(date: String?): String? {
        val d = date?.trim().orEmpty()
        val m = Regex("^((?:19|20)\\d\\d)(?:$|[-./\\s])").find(d) ?: return null
        return m.groupValues[1]
    }

    private val SPLIT = Regex("\\s*(?:,|;|\\s&\\s|\\sx\\s|\\sи\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\svs\\.?\\s)\\s*", RegexOption.IGNORE_CASE)

    /**
     * What the rest of the library knows: the year of each album (from all its songs), how many
     * songs each album has (to recognise singles) and which artists exist on their own (to file
     * "мой друг магнитофон, свидетельство о смерти, Никита Савра" under "Свидетельство О Смерти").
     */
    class Context(tracks: Collection<TrackInfo>) {
        private val artistCounts = HashMap<String, Int>()
        private val artistSpelling = HashMap<String, MutableMap<String, Int>>()
        private val albumYears = HashMap<String, MutableMap<String, Int>>()
        private val albumSizes = HashMap<String, Int>()
        private val albumSpelling = HashMap<String, MutableMap<String, Int>>()
        private val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        // declared before init {}: init already uses it
        private val artistNameCache = HashMap<String, String>()

        init {
            for (t in tracks) {
                val raw = rawArtist(t)
                if (raw.isNotBlank() && !SPLIT.containsMatchIn(raw)) {
                    val k = TextFold.strictKey(raw)
                    artistCounts.merge(k, 1, Int::plus)
                    artistSpelling.getOrPut(k) { HashMap() }.merge(TextFold.cleanDisplay(raw), 1, Int::plus)
                }
            }
            for (t in tracks) {
                if (t.album.isBlank()) continue
                val key = albumKey(t)
                albumSizes.merge(key, 1, Int::plus)
                albumSpelling.getOrPut(key) { HashMap() }.merge(Watermarks.strip(TextFold.cleanDisplay(t.album)), 1, Int::plus)
                reliableYear(t.year)?.takeIf { it.toInt() <= currentYear }?.let { y -> albumYears.getOrPut(key) { HashMap() }.merge(y, 1, Int::plus) }
            }
        }

        /** For several artists in one tag: the one this library already knows best. */
        fun resolveArtist(raw: String): String {
            if (!SPLIT.containsMatchIn(raw)) return raw
            val known = raw.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { part -> artistCounts[TextFold.strictKey(part)]?.let { part to it } }
                .maxByOrNull { it.second }?.first ?: return raw
            return artistSpelling[TextFold.strictKey(known)]?.maxByOrNull { it.value }?.key ?: known
        }

        fun albumKey(t: TrackInfo) = TextFold.strictKey(artistName(rawArtist(t))) + "|" + TextFold.strictKey(t.album)
        /**
         * The album's year when enough of its songs agree (at least a quarter): one song with a
         * stray "2026" must not stamp a 2001 album.
         */
        fun albumYear(t: TrackInfo): String? {
            val key = albumKey(t)
            val best = albumYears[key]?.maxByOrNull { it.value } ?: return null
            return best.key.takeIf { best.value * 4 >= (albumSizes[key] ?: 1) }
        }

        /** The most common spelling: "Враги Народа" ×9 / "Враги народа" ×2 -> one folder. */
        fun albumName(t: TrackInfo): String? = albumSpelling[albumKey(t)]?.maxByOrNull { it.value }?.key

        /**
         * The folder spelling of an artist: the most common spelling of the same name, and a
         * misspelled variant ("Свидительство О Смерти" from an old folder name) joins the
         * much more common correct one ("Свидетельство О Смерти").
         */
        fun artistName(raw: String): String = artistNameCache.getOrPut(raw) {
            val r = resolveArtist(raw)
            val k = TextFold.strictKey(r)
            val own = artistCounts[k] ?: 0
            val target = artistCounts.entries
                .filter { (other, n) -> other != k && n >= maxOf(3, own * 3) }
                .sortedByDescending { it.value }
                .firstOrNull { (other, _) ->
                    val spelled = artistSpelling[other]?.maxByOrNull { it.value }?.key ?: return@firstOrNull false
                    app.malachite.core.text.Similarity.sameName(spelled, r) != null
                }?.key ?: k
            artistSpelling[target]?.maxByOrNull { it.value }?.key ?: r
        }
        fun albumSize(t: TrackInfo): Int = albumSizes[albumKey(t)] ?: 1
    }

    private fun rawArtist(t: TrackInfo) = Mojibake.fixOrSelf(t.albumArtist.ifBlank { Watermarks.primaryArtist(t.artist) })

    fun artistFolder(t: TrackInfo, context: Context? = null): String {
        val raw = rawArtist(t)
        val a = context?.artistName(raw) ?: raw
        return sanitize(Watermarks.strip(TextFold.cleanDisplay(a))).ifEmpty { UNKNOWN_ARTIST }
    }

    /** True for a song without an album or a single (album named like the song, nothing else in it). */
    fun isSingle(t: TrackInfo, context: Context? = null): Boolean {
        if (t.album.isBlank()) return true
        if (context == null) return false
        return TextFold.strictKey(t.album) == TextFold.strictKey(t.title) && context.albumSize(t) <= 1
    }

    fun albumFolder(t: TrackInfo, options: Options = Options(), context: Context? = null): String {
        if (isSingle(t, context)) return sanitize(options.singlesFolder).ifEmpty { SINGLES }
        val name = context?.albumName(t) ?: Watermarks.strip(TextFold.cleanDisplay(t.album))
        val year = if (!options.yearInAlbumFolder) null else if (context != null) context.albumYear(t) else reliableYear(t.year)
        val withYear = if (year != null && !name.contains(year)) "$name ($year)" else name
        return sanitize(withYear).ifEmpty { UNKNOWN_ALBUM }
    }

    fun fileBaseName(t: TrackInfo, options: Options = Options()): String {
        // site watermarks never end up in file names: "Когда я ответил да (Sefon.Pro)" -> "Когда я ответил да"
        val title = sanitize(Watermarks.strip(TextFold.cleanDisplay(t.title))).ifEmpty {
            sanitize(t.fileName.substringBeforeLast('.'))
        }.ifEmpty { "Track" }
        return if (options.trackNumberPrefix && t.track > 0) String.format("%02d - %s", t.track, title) else title
    }

    /** Expected relative path of [t] (the extension always matches the real format). */
    fun expectedPath(t: TrackInfo, options: Options = Options(), context: Context? = null): String {
        val format = runCatching { AudioFormat.valueOf(t.format) }.getOrDefault(AudioFormat.UNKNOWN)
        val ext = if (format == AudioFormat.UNKNOWN || AudioFormat.extensionMatches(t.extension, format)) t.extension else format.extension
        return artistFolder(t, context) + "/" + albumFolder(t, options, context) + "/" + fileBaseName(t, options) + (if (ext.isEmpty()) "" else ".$ext")
    }

    /** The album name a layout folder stands for: "Безнадёжность (2022)" -> "Безнадёжность". */
    fun albumFromFolder(folder: String, options: Options = Options()): String? {
        if (folder == UNKNOWN_ALBUM || folder.equals(options.singlesFolder, true) || folder.equals(SINGLES, true)) return null
        // "AyuGram", "Telegram", "Download": the folder of a program, not an album
        if (FolderHints.isGeneric(folder)) return null
        return folder.replace(Regex("\\s*\\((?:19|20)\\d\\d\\)$"), "").trim().ifEmpty { null }
    }

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val RESERVED_WIN = setOf(
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /**
     * Makes a name safe for every file system a phone / SD card / PC may use
     * (FAT32, exFAT, NTFS, ext4, F2FS) while keeping Cyrillic and other letters.
     */
    fun sanitize(name: String, maxBytes: Int = 120): String {
        var s = ILLEGAL.replace(name, " ")
        s = s.replace('/', ' ')
        s = TextFold.collapseSpaces(s).trim().trimEnd('.', ' ').trimStart('.', ' ')
        if (s.lowercase() in RESERVED_WIN) s = "_$s"
        if (s in RESERVED) s = "_$s"
        // limit by UTF-8 bytes, never cutting a character in half
        if (s.toByteArray(Charsets.UTF_8).size > maxBytes) {
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val str = String(Character.toChars(cp))
                val b = str.toByteArray(Charsets.UTF_8).size
                if (bytes + b > maxBytes) break
                sb.append(str); bytes += b
                i += Character.charCount(cp)
            }
            s = sb.toString().trimEnd('.', ' ')
        }
        return s
    }
}
