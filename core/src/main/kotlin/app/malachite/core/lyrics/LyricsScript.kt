package app.malachite.core.lyrics

/**
 * Lyrics written in another alphabet than the song: «Звезда по имени Солнце» with
 * "I dve tysyachi let voyna…" is a transliteration (or a translation) somebody uploaded, not
 * the words as they are sung. Such text is never put on a song by itself.
 */
object LyricsScript {
    /** The services a text can come from by itself; a text the user typed, synced or fixed is theirs. */
    val FOUND_SOURCES = setOf("LRCLIB", "NetEase", "Genius", "lyrics.ovh")

    /** Share of Latin letters among the letters of [text], or null when it has fewer than [minLetters] letters. */
    fun latinShare(text: String, minLetters: Int = 1): Double? {
        var latin = 0
        var all = 0
        for (c in text) {
            if (!c.isLetter()) continue
            all++
            if (c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ɏ') latin++
        }
        return if (all < minLetters) null else latin.toDouble() / all
    }

    /**
     * The song is named in another alphabet (Cyrillic, Greek, Japanese…) and the text is all in
     * Latin letters. A song named in Latin letters is not judged: "Zvezda po imeni Solntse" is
     * often a Russian song with Russian words.
     */
    fun foreignLetters(title: String, artist: String, lyrics: Lyrics): Boolean {
        if (lyrics.lines.isEmpty()) return false
        // the title tells the language; a title without words ("45") – together with the artist
        val name = latinShare(title, minLetters = 3) ?: latinShare("$title $artist", minLetters = 3) ?: return false
        if (name > 0.3) return false
        val text = latinShare(lyrics.plainText, minLetters = 40) ?: return false
        return text >= 0.85
    }
}
