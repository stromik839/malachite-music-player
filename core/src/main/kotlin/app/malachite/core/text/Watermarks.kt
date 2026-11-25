package app.malachite.core.text

/**
 * Junk that download sites and video rips put into tags:
 *   "Song (muzofond.fm)", "Artist - www.mp3party.net", "Title [zaycev.net]",
 *   "Title (Official Video)", "Title (Lyrics)", "Title | Текст песни".
 */
object Watermarks {

    private const val TLD = "(?:net|com|ru|fm|org|me|cc|info|pro|biz|to|ws|su|ua|by|kz|io|club|xyz|online|site|top|tv|mobi|name|in|co|uk|de|pl|lt|lv|ee|am|ge|uz|me|cx|la)"
    private val DOMAIN = "(?:https?://)?(?:www\\.)?[\\p{L}0-9][\\p{L}0-9-]{1,40}(?:\\.[\\p{L}0-9-]{1,40})?\\.$TLD"

    private val BRACKETED_DOMAIN = Regex("[\\[(（\\{]\\s*$DOMAIN\\s*[\\])）\\}]", RegexOption.IGNORE_CASE)
    private val TRAILING_DOMAIN = Regex("\\s*(?:[-–—|/:]\\s*)?$DOMAIN\\s*$", RegexOption.IGNORE_CASE)
    private val LEADING_DOMAIN = Regex("^\\s*$DOMAIN\\s*(?:[-–—|/:]\\s*)", RegexOption.IGNORE_CASE)

    private val VIDEO_JUNK = Regex(
        "\\s*[\\[(]\\s*(?:official\\s*(?:music\\s*)?(?:video|audio|clip|lyric video|visualizer)|" +
            "lyric(?:s)?\\s*(?:video)?|audio|video|clip|hq|hd|4k|mv|m/v|" +
            "официальн\\p{L}*\\s*(?:видео|клип|аудио)?|клип|премьера\\s*(?:клипа|песни|трека)?\\s*\\d*|" +
            "текст\\s*песни|lyrics?\\s*/\\s*текст|караоке|karaoke|free\\s*download)\\s*[\\])]",
        RegexOption.IGNORE_CASE,
    )

    fun contains(s: String): Boolean =
        BRACKETED_DOMAIN.containsMatchIn(s) || TRAILING_DOMAIN.containsMatchIn(s) ||
            LEADING_DOMAIN.containsMatchIn(s) || VIDEO_JUNK.containsMatchIn(s)

    /** Removes watermarks; never returns an empty string if the input had content. */
    fun strip(s: String): String {
        var r = s
        r = BRACKETED_DOMAIN.replace(r, " ")
        r = VIDEO_JUNK.replace(r, " ")
        r = TRAILING_DOMAIN.replace(r, "")
        r = LEADING_DOMAIN.replace(r, "")
        r = TextFold.collapseSpaces(r).trim(' ', '-', '–', '—', '|', '_', ',', ';', ':')
        r = TextFold.collapseSpaces(r)
        return r.ifEmpty { s.trim() }
    }

    private val QUOTED = Regex("^[\"«“„‘](.+)[\"»”“’]$")

    /** «Лёха» / "Лёха" -> Лёха (only when the quotes wrap the whole name). */
    fun unquote(s: String): String {
        val t = s.trim()
        val m = QUOTED.matchEntire(t) ?: return t
        val inner = m.groupValues[1].trim()
        // "a" and "b" – two quoted parts, not one wrapped name
        return if (inner.isEmpty() || inner.any { it == '"' || it == '«' || it == '»' }) t else inner
    }

    /**
     * The song title the way a lyrics or music service knows it: no site watermarks or
     * "(Official Video)", no "Artist - " in front (YouTube rips), no wrapping quotes.
     * "Алена Апина - "Лёха" (Official Video)" -> "Лёха".
     */
    fun searchTitle(title: String, artist: String?): String {
        var t = strip(TextFold.cleanDisplay(title))
        // "08 Наваждение", "08. Song", "8) Song", "8 - Song": the number of the track on its album,
        // not a part of the name – with it a search found nothing or a wrong text
        t = LEADING_TRACK.replace(t, "").ifBlank { t }
        if (!artist.isNullOrBlank()) {
            val parts = t.split(DASH, limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank() && Similarity.sameName(primaryArtist(parts[0]), primaryArtist(artist)) != null) {
                t = parts[1].trim()
            }
        }
        return unquote(strip(t))
    }

    /**
     * A track number in front of a title: zero-padded ("08 ", "08. ", "08_") or followed by a
     * dot, a bracket or a dash ("8. ", "8) ", "8 - "). A bare number with a space stays: it is a
     * part of names like "21 Guns" or "7 Seconds".
     */
    private val LEADING_TRACK = Regex("^\\s*(?:0\\d{1,2}(?:\\s*[.\\-_)–—]\\s*|\\s+)|\\d{1,3}\\s*[.)_]\\s+|\\d{1,3}\\s+[-–—]\\s+)(?=\\S)")

    /** "8 Song": the title without a bare number in front – tried as one more way to ask, after the others. */
    fun withoutBareNumber(title: String): String? =
        BARE_NUMBER.matchEntire(title.trim())?.groupValues?.get(1)?.takeIf { it.any(Char::isLetter) }

    private val BARE_NUMBER = Regex("\\d{1,3}\\s+(\\S.*)")

    private val DASH = Regex("\\s+[-–—]\\s+")

    private val FEAT = Regex("\\s*[\\[(]?\\s*\\b(?:feat\\.?|ft\\.?|featuring|при\\s+уч\\.?|при\\s+участии)\\s+.*$", RegexOption.IGNORE_CASE)

    /** "Artist feat. Other" -> "Artist". Only explicit "feat" markers are cut, never "&" or ",". */
    fun primaryArtist(s: String): String {
        val r = FEAT.replace(s, "").trim()
        return r.ifEmpty { s.trim() }
    }

    private val EDITION = Regex(
        "\\s*[\\[(]\\s*(?:(?:\\d{4}\\s*)?(?:deluxe|expanded|anniversary|special|bonus|collector'?s|limited|remaster(?:ed)?|explicit|clean|edited)\\b[^\\])]*|" +
            "(?:\\d{4}\\s*)?remaster(?:ed)?(?:\\s*\\d{4})?|single|ep|maxi[- ]single)\\s*[\\])]|\\s+-\\s+(?:single|ep)$",
        RegexOption.IGNORE_CASE,
    )

    /** Album name without edition markers – used only to *suggest* merges. */
    fun albumBase(s: String): String = TextFold.collapseSpaces(EDITION.replace(s, " "))

    private val TRACK_PREFIX = Regex("^\\s*(?:\\d{1,3}\\s*[.\\-_)]\\s*|\\d{1,3}\\s+-\\s+)")

    /** "01. Song" / "01 - Song" -> "Song" (only used for titles taken from file names). */
    fun stripTrackNumber(s: String): String = TRACK_PREFIX.replace(s, "").ifEmpty { s }
}
