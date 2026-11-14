package app.malachite.core.heal

import app.malachite.core.i18n.tr
import app.malachite.core.online.OnlineMatch
import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

/**
 * Album and artist tags that are not names at all: the site a song was downloaded from
 * ("muzrecord.com"), a placeholder ("Unknown album", "mp3"), or the song itself written again
 * ("Ебанько Я Ебу Собак" as the album of "Я ебу собак" by Ебанько). An album named exactly
 * like its song is a single and stays.
 */
object OddTags {
    private const val TLD = "(?:com|net|org|ru|su|ua|by|kz|fm|me|cc|info|biz|pro|to|tv|io|club|xyz|online|site|top|name|mobi|uz|am|ge|in|co|de|pl|lt|lv)"
    private val SITE = Regex("(?i)(?:^|[\\s(\\[«\"'|])(?:https?://)?(?:www\\.)?[\\p{L}0-9][\\p{L}0-9-]{1,40}\\.$TLD(?:$|[\\s)\\]»\"'/.,!|])")

    /** Placeholders, as loose keys. */
    private val PLACEHOLDERS = listOf(
        "unknown", "unknown album", "unknown artist", "untitled", "no album", "no artist", "album", "artist",
        "music", "audio", "mp3", "track", "tracks", "download", "new", "vk", "telegram", "various",
        "неизвестный", "неизвестный альбом", "неизвестный исполнитель", "без названия", "без альбома",
        "альбом", "исполнитель", "музыка", "аудио", "трек", "скачать", "новинка", "новинки", "вконтакте",
    ).map { TextFold.looseKey(it) }.toSet()

    /** Why [album] of the song [title] by [artist] is not a real album name, or null when it looks like one. */
    fun albumProblem(album: String, title: String, artist: String): String? {
        val a = album.trim()
        if (a.isEmpty()) return null
        if (SITE.containsMatchIn(a) || Watermarks.contains(a)) return tr("в альбоме название сайта")
        if (TextFold.looseKey(a) in PLACEHOLDERS) return tr("заглушка вместо альбома")
        if (app.malachite.core.library.FolderHints.isProgramFolder(a)) return tr("в альбоме имя программы или папки загрузок")
        val song = Watermarks.strip(title)
        if (OnlineMatch.words(song).isEmpty() || OnlineMatch.words(a).size <= OnlineMatch.words(song).size) return null
        // the song's words are (nearly) all there, together with the artist; besides them at most
        // a counter left by a download site and a word spelled another way ("Xuy" for "хуй")
        if (OnlineMatch.covered(song, a) < 0.75) return null
        val albumWords = OnlineMatch.words(a)
        val artistWords = OnlineMatch.words(artist)
        fun isArtist(w: String) = artistWords.any { it == w || (minOf(it.length, w.length) >= 3 && (it.startsWith(w) || w.startsWith(it))) }
        if (albumWords.none { isArtist(it) }) return null
        val left = OnlineMatch.extraWords(a, song).filter { w -> !isArtist(w) && !w.all(Char::isDigit) }
        return if (left.size <= maxOf(1, albumWords.size * 15 / 100)) tr("в альбоме исполнитель и название песни") else null
    }

    /** Why [artist] (of the song [title]) is not a real artist name, or null. */
    fun artistProblem(artist: String, title: String = ""): String? {
        val a = artist.trim()
        if (a.isEmpty()) return null
        if (SITE.containsMatchIn(a) || Watermarks.contains(a)) return tr("в исполнителе название сайта")
        if (TextFold.looseKey(a) in PLACEHOLDERS) return tr("заглушка вместо исполнителя")
        if (app.malachite.core.library.FolderHints.isProgramFolder(a)) return tr("в исполнителе имя программы или папки загрузок")
        if (isCompilationName(a)) return tr("«$a» — это пометка сборника, а не исполнитель")
        if (artistWithoutTitle(a, title) != null) return tr("в исполнителе ещё и название песни")
        return null
    }

    /**
     * The artist and the title both hold "Artist - Song" ("Молчат Дома (Molchat Doma) - Судно (Sudno)"
     * twice), or the title holds "Artist - Song" while the artist tag is that artist: the artist and
     * the song apart, or null when the tags are not like that.
     */
    fun splitArtistAndSong(artist: String, title: String): Pair<String, String>? {
        val a = artist.trim()
        val t = title.trim()
        if (t.isEmpty()) return null
        val parts = t.split(DASH, limit = 2)
        if (parts.size < 2) return null
        val head = parts[0].trim()
        val tail = Watermarks.unquote(parts[1].trim())
        if (head.isEmpty() || tail.isEmpty()) return null
        return when {
            // the same "Artist - Song" in both
            a.isNotEmpty() && TextFold.looseKey(a) == TextFold.looseKey(t) -> head to tail
            // the artist written once more in front of the song
            a.isNotEmpty() && TextFold.looseKey(head) == TextFold.looseKey(a) -> a to tail
            else -> null
        }
    }

    /** Why [title] is not just the song's name ("Artist - Song"), or null. */
    fun titleProblem(artist: String, title: String): String? =
        if (splitArtistAndSong(artist, title) != null) tr("в названии ещё и исполнитель") else null

    /** "Разные исполнители", "Various Artists": what a compilation has as its album artist, never a song's artist. */
    fun isCompilationName(name: String): Boolean = TextFold.looseKey(name) in COMPILATION_NAMES

    private val COMPILATION_NAMES = listOf("разные исполнители", "various artists", "various", "va", "v.a.", "сборник", "сборники", "compilation")
        .map { TextFold.looseKey(it) }.toSet()

    private val DASH = Regex("\\s+[-–—]\\s+")

    /**
     * "Молчат Дома (Molchat Doma) - Судно (Sudno)" as the artist of "Судно (Sudno)": the artist
     * with the song after a dash. Returns the artist alone, or null when the artist is not like that.
     */
    fun artistWithoutTitle(artist: String, title: String): String? {
        val song = Watermarks.strip(title).trim()
        if (song.isEmpty()) return null
        val parts = artist.split(DASH)
        if (parts.size < 2) return null
        val head = parts.first().trim()
        val tail = parts.drop(1).joinToString(" - ").trim()
        if (head.isEmpty() || tail.isEmpty()) return null
        // the part after the dash is the song (nearly all its words, and not much more)
        if (OnlineMatch.covered(song, tail) < 0.75 || OnlineMatch.covered(tail, song) < 0.6) return null
        return head
    }
}
