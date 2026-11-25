package app.malachite.core.net

import app.malachite.core.text.TextFold
import app.malachite.core.text.Translit
import app.malachite.core.text.Watermarks

/**
 * Builds the different ways to ask an online service about a song, from best to worst:
 * cleaned names, without "feat."/edition markers, artist/album swapped (tags written the
 * wrong way round), and the other script (Cyrillic <-> Latin).
 */
object QueryVariants {

    data class Query(val artist: String, val second: String, val note: String)

    fun clean(s: String?): String = Watermarks.strip(TextFold.cleanDisplay(s.orEmpty()))

    /**
     * [second] is the album (for covers) or the title (for lyrics, [isTitle] = true: then
     * "Artist - "Song" (Official Video)" is reduced to "Song").
     */
    fun build(artist: String?, second: String?, allowSwap: Boolean = true, isTitle: Boolean = false): List<Query> {
        val a0 = clean(artist)
        val s0 = if (isTitle) Watermarks.searchTitle(second.orEmpty(), a0) else clean(second)
        val a1 = Watermarks.primaryArtist(a0)
        val s1 = if (isTitle) stripVersionTags(s0) else Watermarks.albumBase(stripVersionTags(s0))
        val out = LinkedHashMap<String, Query>()
        fun add(a: String, s: String, note: String) {
            if (s.isBlank() && a.isBlank()) return
            val key = TextFold.strictKey(a) + "|" + TextFold.strictKey(s)
            if (key !in out) out[key] = Query(a.trim(), s.trim(), note)
        }
        add(a0, s0, "as tagged")
        add(a1, s1, "cleaned")
        // Names with special signs ("★", "$", "!!", "#", emoji): services usually keep them
        // without the signs, and a sign in the query can make a search find nothing at all.
        add(plain(a1), plain(s1), "without special signs")
        if (isTitle) {
            // "Other Artist - Song" in the title of a file with a wrong / empty artist
            val parts = s0.split(Regex("\\s+[-–—]\\s+"), limit = 2)
            if (parts.size == 2 && parts.all { it.isNotBlank() }) add(Watermarks.primaryArtist(parts[0].trim()), Watermarks.unquote(parts[1]), "artist from title")
        }
        if (isTitle) {
            // "8 Наваждение": the number may be the track's place on its album
            Watermarks.withoutBareNumber(s1)?.let { add(a1, it, "without the number in front") }
        }
        if (allowSwap) add(s1, a1, "artist/album swapped")
        Translit.alternateScript(a1)?.let { add(it, s1, "artist in other script") }
        Translit.alternateScript(s1)?.let { add(a1, it, "name in other script") }
        val aa = Translit.alternateScript(a1)
        val ss = Translit.alternateScript(s1)
        if (aa != null && ss != null) add(aa, ss, "both in other script")
        if (allowSwap && aa != null && ss != null) add(ss, aa, "swapped, other script")
        return out.values.toList()
    }

    /** Letters, digits and spaces only ("AC/DC" -> "AC DC", "Ke$ha ★ Tik-Tok!" -> "Ke ha Tik Tok"). */
    fun plain(s: String): String =
        TextFold.collapseSpaces(s.map { if (it.isLetterOrDigit() || it == ' ' || it == '\'') it else ' ' }.joinToString("")).trim()

    private val VERSION = Regex(
        "\\s*[\\[(][^\\])]*(remix|mix|edit|version|live|acoustic|instrumental|remaster|sped up|slowed|reverb|cover|ремикс|версия|акустика|кавер|live)[^\\])]*[\\])]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The same marks written without brackets at the end of a title, as file names from video
     * sites have them: "Song speed up, bass boosted", "Song - slowed + reverb". The words of the
     * song are the same, so lyrics, covers and albums are looked up without the tail.
     */
    private val SOUND_TAIL = Regex(
        "\\s+[-–—|/,+]*\\s*((super|ultra)\\s+)?((sped|speed|speeded)[ _-]?up|slowed|reverb|bass[ _-]?boost(ed)?|nightcore|daycore|8d\\s+audio)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    fun stripVersionTags(s: String): String {
        val noBrackets = TextFold.collapseSpaces(VERSION.replace(s, " ")).ifEmpty { s }
        val noTail = SOUND_TAIL.replace(noBrackets, "").trim()
        return if (noTail.length >= 2) noTail else noBrackets
    }

    /** 0..1 similarity of two names, tolerant of script, spacing and decoration. */
    fun similarity(a: String?, b: String?): Double {
        val ka = TextFold.looseKey(a)
        val kb = TextFold.looseKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        if (ka.contains(kb) || kb.contains(ka)) return 0.85 * minOf(ka.length, kb.length) / maxOf(ka.length, kb.length) + 0.15
        return app.malachite.core.text.Similarity.ratio(ka, kb)
    }
}
