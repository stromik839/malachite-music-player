package app.malachite.core.library

import app.malachite.core.heal.HealPlanner
import app.malachite.core.text.Similarity
import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

/**
 * Many songs have no album in their tags – the album lives only in the folder name
 * (".../Boris_Kuzmin/Время выткало нить/Закружилась пряжа.m4a"). Other players show such an
 * album because Android's media index falls back to the folder name. This object reads the
 * same information from a file's path so that it is not lost.
 */
object FolderHints {

    data class Hint(
        val artist: String?,
        val album: String?,
        /** True when the folder structure confirms itself (the folder above the album is the artist). */
        val confident: Boolean,
        val note: String,
    )

    /**
     * Folders that programs make for what they save: messengers and their clients, video and
     * music downloaders. Such a name in the album or artist tag is the folder, not the music.
     */
    private val PROGRAMS = setOf(
        "download", "downloads", "загрузки", "telegram", "telegram audio", "telegram documents", "telegram music",
        "telegram video", "telegram desktop", "tdesktop", "whatsapp", "whatsapp audio", "whatsapp documents",
        "bluetooth", "vk", "vkmusic", "vk music", "музыка вк", "zaycev", "sefon", "snaptube", "vidmate", "tubemate",
        "ytdlnis", "newpipe", "libretube", "instander", "viber", "kate mobile", "katemobile", "vk coffee", "vkcoffee",
        "ayugram", "ayugram desktop", "nekogram", "nekox", "plus messenger", "telegram x", "telegramx", "tgx",
        "nicegram", "exteragram", "cherrygram", "octogram", "forkgram", "swiftgram", "materialgram", "kotatogram",
        "unigram", "64gram", "momogram", "turrit", "graph messenger", "mdgram", "teamgram", "partisan telegram",
    )

    /** Folders that say nothing about an album / artist. */
    private val GENERIC = PROGRAMS + setOf(
        "music", "музыка", "музыка вк", "my music", "моя музыка", "mymusic", "allmusic", "all music", "finalallmusic",
        "download", "downloads", "загрузки", "загрузка", "audio", "аудио", "audios", "telegram", "telegram audio",
        "telegram documents", "whatsapp", "whatsapp audio", "whatsapp documents", "media", "mp3", "songs", "песни",
        "new folder", "новая папка", "documents", "документы", "sounds", "bluetooth", "vk", "vkmusic", "vk music",
        "zaycev", "sefon", "playlist", "playlists", "плейлист", "сборник", "sd", "sdcard", "storage", "emulated",
        "0", "files", "file", "android", "data", "cache", "misc", "other", "разное", "без альбома", "unknown",
        "unknown album", "неизвестный альбом", "<unknown>", "album", "альбом", "artist", "исполнитель",
        "music_backup", "backup", "backups", "copy", "копия", "temp", "tmp", "share", "shared", "received", "incoming",
        "синглы", "сингл", "singles", "single", "ep", "lp", "демо", "demo", "live", "лайв", "концерты", "разное",
        // the folder of compilations: songs of many artists, none of them is "the" artist
        "разные исполнители", "various artists", "various", "va", "v a", "сборники", "compilation", "compilations",
        LibraryLayout.UNKNOWN_ALBUM.lowercase(), LibraryLayout.UNKNOWN_ARTIST.lowercase(), LibraryLayout.SINGLES.lowercase(),
    )

    private val VOLUME = Regex("^[0-9A-F]{4}-[0-9A-F]{4}$", RegexOption.IGNORE_CASE)
    private val YEAR_PREFIX = Regex("^\\(?(19|20)\\d\\d\\)?\\s*[-–—.]?\\s*")
    // "(2022)", "[2001]", "(2025, live-альбом)"
    private val YEAR_SUFFIX = Regex("\\s*[(\\[]\\s*(19|20)\\d\\d[^)\\]]*[)\\]]\\s*$")
    private val DASH_SPLIT = Regex("\\s+[-–—]\\s+")

    /** "AyuGram", "Telegram Audio", "Downloads": the folder of a program (see [PROGRAMS]). */
    fun isProgramFolder(name: String): Boolean =
        TextFold.cleanDisplay(name).lowercase().trim { !it.isLetterOrDigit() } in PROGRAMS

    fun isGeneric(name: String): Boolean {
        // "синглы 📀" -> "синглы"
        val n = TextFold.cleanDisplay(name).lowercase().trim { !it.isLetterOrDigit() }
        return n.isEmpty() || n in GENERIC || VOLUME.matches(n) || HealPlanner.isJunkName(n)
    }

    private val SINGLES_WORDS = setOf("синглы", "сингл", "singles", "single", "без альбома", "unknown album", LibraryLayout.SINGLES.lowercase())

    fun isSinglesFolder(name: String): Boolean =
        TextFold.cleanDisplay(name).lowercase().trim { !it.isLetterOrDigit() } in SINGLES_WORDS

    /** "Boris_Kuzmin" -> "Boris Kuzmin", "2005 - Всем смертям назло" -> "Всем смертям назло". */
    fun cleanFolderName(name: String): String {
        var s = TextFold.cleanDisplay(name)
        if (!s.contains(' ') && (s.contains('_') || s.contains('.'))) s = s.replace('_', ' ').replace('.', ' ')
        s = s.replace('_', ' ')
        s = YEAR_SUFFIX.replace(s, "")
        s = YEAR_PREFIX.replace(s, "")
        s = Watermarks.strip(TextFold.collapseSpaces(s))
        return s.trim()
    }

    private fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return Similarity.sameName(cleanFolderName(a), Watermarks.primaryArtist(TextFold.cleanDisplay(b))) != null
    }

    /** Folders of [path] without the storage prefix (/storage/emulated/0, SD card id, /sdcard). */
    fun folders(path: String): List<String> {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.dropLast(1).toMutableList()
        when {
            segs.size >= 3 && segs[0] == "storage" && segs[1] == "emulated" -> repeat(3) { segs.removeAt(0) }
            segs.size >= 2 && segs[0] == "storage" -> repeat(2) { segs.removeAt(0) }
            segs.isNotEmpty() && (segs[0] == "sdcard" || segs[0] == "mnt") -> segs.removeAt(0)
        }
        return segs
    }

    /**
     * What the folders around [path] say about the song. [artist] is the artist from the tags
     * (may be empty).
     */
    fun fromPath(path: String, artist: String?): Hint? {
        val dirs = folders(path)
        if (dirs.isEmpty()) return null
        val parent = dirs.last()
        val grand = dirs.getOrNull(dirs.size - 2)
        // ".../Вещие Ослы/синглы 📀/Будет лучше.mp3": the artist is the folder above the singles folder
        if (isSinglesFolder(parent) && grand != null && !isGeneric(grand)) {
            return when {
                same(grand, artist) -> Hint(null, null, true, "синглы исполнителя «$grand»")
                artist.isNullOrBlank() -> Hint(cleanFolderName(grand), null, true, "папки «$grand/$parent»")
                else -> null
            }
        }
        if (isGeneric(parent)) return null
        val parentClean = cleanFolderName(parent)
        if (parentClean.isBlank()) return null

        // ".../Свидетельство о смерти/Петля.mp3": the folder is the artist, there is no album.
        if (same(parent, artist)) return Hint(null, null, true, "папка исполнителя: $parent")

        // ".../Свидетельство о смерти - Всем смертям назло/...": artist and album in one folder name
        val parts = parentClean.split(DASH_SPLIT, limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) {
            if (same(parts[0], artist)) return Hint(null, cleanFolderName(parts[1]), true, "папка «$parent»")
            if (artist.isNullOrBlank() && grand != null && isGeneric(grand)) {
                return Hint(parts[0].trim(), cleanFolderName(parts[1]), false, "папка «$parent»")
            }
        }

        if (grand != null && !isGeneric(grand)) {
            // ".../Boris_Kuzmin/Время выткало нить/...": artist folder, then album folder
            if (same(grand, artist)) return Hint(null, parentClean, true, "папки «$grand/$parent»")
            if (artist.isNullOrBlank()) return Hint(cleanFolderName(grand), parentClean, false, "папки «$grand/$parent»")
        }
        // A lone folder: maybe an album, maybe just a collection – needs the user's confirmation.
        return Hint(null, parentClean, false, "папка «$parent»")
    }

    /**
     * Title from a file name when the tag has none: "01. Свидетельство О Смерти — Подводная лодка"
     * -> "Подводная лодка" (the artist part is removed when it matches the artist).
     */
    fun titleFromFileName(fileName: String, artist: String?): String {
        var base = Watermarks.stripTrackNumber(fileName.substringBeforeLast('.'))
        base = Watermarks.strip(TextFold.cleanDisplay(base.replace('_', ' ')))
        val parts = base.split(DASH_SPLIT, limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank() && (artist.isNullOrBlank() || same(parts[0], artist))) return parts[1].trim()
        return base.trim()
    }

    /** "Свидетельство О Смерти — Подводная лодка" -> "Подводная лодка" when the prefix is the song's artist. */
    fun stripArtistPrefix(title: String, artist: String?): String {
        if (artist.isNullOrBlank()) return title
        val parts = title.split(DASH_SPLIT, limit = 2)
        // "Алена Апина - "Лёха"" -> "Лёха" (the quotes only wrapped the title after the artist)
        if (parts.size == 2 && parts[1].isNotBlank() && same(parts[0], artist)) return app.malachite.core.text.Watermarks.unquote(parts[1].trim())
        return title
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val COVER_NAMES = Regex("^(cover|folder|front|albumart[a-z_]*|album|обложка)$", RegexOption.IGNORE_CASE)

    /**
     * A picture lying next to the songs that is the cover: "cover.jpg", "folder.png", the only
     * picture of an album folder, or a picture named like the song ("БудетЛучше.jpg" for
     * "Будет лучше"). Tiny thumbnails are ignored.
     */
    fun coverImageIn(dir: java.io.File?, songTitle: String? = null, albumName: String? = null): java.io.File? {
        val images = dir?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXT && f.length() in 8_000..15_000_000 }
            ?.toList().orEmpty()
        if (images.isEmpty()) return null
        if (!songTitle.isNullOrBlank()) {
            val key = TextFold.strictKey(songTitle)
            images.firstOrNull { TextFold.strictKey(it.nameWithoutExtension) == key }?.let { return it }
        }
        images.filter { COVER_NAMES.matches(it.nameWithoutExtension) }.maxByOrNull { it.length() }?.let { return it }
        // "ВремяВыткалоНить.jpeg" in the folder of the album "Время выткало нить"
        if (!albumName.isNullOrBlank()) {
            val key = TextFold.strictKey(albumName)
            images.firstOrNull { TextFold.strictKey(it.nameWithoutExtension) == key }?.let { return it }
        }
        // pictures named after songs belong to those songs, not to the whole folder
        val generic = images.filter { !Regex("\\p{L}{4,}").containsMatchIn(it.nameWithoutExtension) || it.nameWithoutExtension.contains(Regex("\\d{3,}x\\d{3,}")) }
        return generic.maxByOrNull { it.length() }
    }

    private val YT = Regex("youtu\\.?be|youtube\\.com", RegexOption.IGNORE_CASE)

    /**
     * Files downloaded from YouTube carry the *uploader* as artist ("icarus") and the real
     * "Artist - Title" in the title. Returns (artist, title) or null.
     */
    fun fromYoutubeTitle(title: String?, comment: String?): Pair<String, String>? {
        if (title.isNullOrBlank() || comment.isNullOrBlank() || !YT.containsMatchIn(comment)) return null
        val parts = title.split(DASH_SPLIT, limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val t = TextFold.collapseSpaces(Regex("\\s*\\[[^\\]]*(19|20)\\d\\d[^\\]]*\\]").replace(parts[1], " "))
        return parts[0].trim() to t.trim()
    }

    /** Artist from a file name "Artist - Title" (only when the tag has none). */
    fun artistFromFileName(fileName: String): String? {
        val base = Watermarks.strip(TextFold.cleanDisplay(Watermarks.stripTrackNumber(fileName.substringBeforeLast('.')).replace('_', ' ')))
        val parts = base.split(DASH_SPLIT, limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() else null
    }
}
