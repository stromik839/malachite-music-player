package app.malachite.core.online

import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

/**
 * How well a song, album or artist found online answers what was typed.
 *
 * The query may be one name or several parts in any order: "artist, album, song",
 * "song, artist", "artist album song". Nothing has to match exactly: words are compared across
 * scripts (Молчат = Molchat), by their beginning ("molch") and with a small typo, and results
 * are ordered by how much of the query they cover. A part that is exactly the artist (or the
 * title, or the album) counts extra, and words found in the artist's name count more than the
 * same words mentioned in a title — so the band itself comes before somebody's re-upload of its
 * song. Re-uploads from video rippers ("yt1s.com - …") go down, unreadable entries ("? ????")
 * are left out.
 */
object OnlineMatch {

    /** One thing found: [title] is the song, the album or (for an artist) empty. */
    data class Candidate(val artist: String, val title: String, val album: String = "")

    /** Below this an entry is not shown, unless nothing better was found at all. */
    const val ENOUGH = 0.34

    private val SPLIT = Regex("\\s*[,;|]\\s*|\\s+[-–—/]\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val RIPPER = Regex(
        "(?i)\\b(yt1s|y2mate|ytmp3|savefrom|savetube|mp3juices|flvto|onlinevideoconverter|4kdownload|ssyoutube|" +
            "ytmp3cc|mp3download|youtube-mp3|converter)\\b",
    )

    /** The parts of a query: split at commas, semicolons, "|" and dashes between spaces. */
    fun parts(query: String): List<String> = query.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** The query as one line for a catalogue's own search (the parts with spaces between). */
    fun plainQuery(query: String): String = parts(query).joinToString(" ")

    /** Words in a form that ignores case, script and the usual spelling variants. */
    fun words(s: String): List<String> =
        TextFold.cleanDisplay(s).split(NON_WORD).map { TextFold.looseKey(it) }.filter { it.isNotEmpty() }

    /** An entry whose name could not be read ("? ???? ?????", "����"): of no use to anybody. */
    fun unreadable(s: String): Boolean {
        val chars = s.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return true
        val bad = chars.count { it == '?' || it == '�' }
        return bad * 10 >= chars.length * 4
    }

    /** A copy made with a video-to-mp3 site: its name carries the site. */
    fun ripped(s: String): Boolean = RIPPER.containsMatchIn(s) || Watermarks.contains(s)

    /** 0 (nothing in common) … about 1.6 (the parts are exactly the artist and the title); below 0: leave out. */
    fun score(query: String, c: Candidate): Double {
        val ps = parts(query)
        val asked = ps.flatMap { words(it) }
        if (asked.isEmpty()) return 0.0
        if (unreadable(c.title.ifBlank { c.artist })) return -1.0
        val title = Watermarks.strip(c.title)
        val inArtist = words(c.artist)
        val inTitle = words(title)
        val inAlbum = words(c.album)
        var covered = 0.0
        var byArtist = 0
        for (q in asked) {
            val a = best(q, inArtist)
            val t = best(q, inTitle)
            val l = best(q, inAlbum) * 0.9
            covered += maxOf(a, t, l)
            if (a >= 0.85 && a >= t) byArtist++
        }
        // how much of what was typed is there at all
        var score = covered / asked.size
        // the same words in the artist's name weigh more than in a title ("Band - Song" uploaded by somebody else)
        score += 0.3 * byArtist / asked.size
        // a whole part that is the artist, the title or the album: that is what was meant
        val artistKey = TextFold.looseKey(c.artist)
        val titleKey = TextFold.looseKey(title)
        val albumKey = TextFold.looseKey(c.album)
        for (p in ps) {
            val k = TextFold.looseKey(p)
            if (k.isEmpty()) continue
            score += when {
                k == artistKey -> 0.4
                k == titleKey -> 0.25
                k == albumKey -> 0.2
                else -> 0.0
            }
        }
        if (ripped(c.title) || ripped(c.artist)) score -= 0.3
        return score
    }

    /** Which part (0…1) of the words of [what] are found in [inside], spelling, script and small typos aside. */
    fun covered(what: String, inside: String): Double {
        val ws = words(what)
        if (ws.isEmpty()) return 0.0
        val field = words(inside)
        return ws.count { best(it, field) >= 0.75 }.toDouble() / ws.size
    }

    /** Where names are put together: "A & B", "A feat. B", "A [B]", "A (B)", "A, B", "A - B". */
    private val NAME_SPLIT = Regex("\\s*[,;/&+\\[\\]()]\\s*|\\s+[-–—:]\\s+|\\s+(?:feat\\.?|ft\\.?|featuring|vs\\.?|x|и|and)\\s+", RegexOption.IGNORE_CASE)

    /** The names in [field]: itself and its parts ("Молчат Дома [Molchat Doma]" → both spellings). */
    fun nameParts(field: String): List<String> =
        (listOf(field) + field.split(NAME_SPLIT)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * [field] names [name] itself, not a longer name that has its words in it: «Kino», «Кино (Виктор
     * Цой)» and «Ploho & Кино» are «Кино»; «Mike's Kino World» is somebody else.
     */
    fun sameName(name: String, field: String): Boolean {
        if (words(name).isEmpty()) return false
        return nameParts(field).any { part -> covered(name, part) >= 1.0 && extraWords(part, name).isEmpty() }
    }

    /** The words of [inside] that do not answer any word of [what]. */
    fun extraWords(inside: String, what: String): List<String> {
        val ws = words(what)
        return words(inside).filter { w -> ws.none { wordMatch(it, w) >= 0.75 || wordMatch(w, it) >= 0.75 } }
    }

    /** The best match of a query word among the words of a field. */
    private fun best(q: String, field: List<String>): Double {
        var top = 0.0
        for (w in field) {
            val m = wordMatch(q, w)
            if (m > top) top = m
            if (top >= 1.0) break
        }
        return top
    }

    private fun wordMatch(q: String, w: String): Double = when {
        q == w -> 1.0
        // a word typed in part: "molch" → "molchat"
        q.length >= 3 && w.startsWith(q) -> 0.85
        // one letter off in a longer word, two in a long one
        q.length >= 5 && w.length >= 5 && distance(q, w, if (q.length >= 9) 2 else 1) <= (if (q.length >= 9) 2 else 1) -> 0.75
        else -> 0.0
    }

    /** Edit distance, stopping early once it is over [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * [items] ordered by how well they answer [query]; near ties go to the better known one
     * ([popularity]). Entries that answer little are left out unless nothing answers well; with
     * [nearBest] above 0 also those far below the best one (an artist called "Doma Dankz" is no
     * answer to "molchat doma" once Molchat Doma itself was found).
     */
    fun <T> rank(query: String, items: List<T>, candidate: (T) -> Candidate, popularity: (T) -> Long = { 0L }, nearBest: Double = 0.0): List<T> {
        val scored = items.mapIndexed { i, t -> Triple(t, score(query, candidate(t)), i) }.filter { it.second >= 0.0 }
        val top = scored.maxOfOrNull { it.second } ?: 0.0
        val good = scored.filter { it.second >= ENOUGH && it.second >= top * nearBest }
        val keep = good.ifEmpty { scored }
        return keep.sortedWith(
            compareByDescending<Triple<T, Double, Int>> { Math.round(it.second * 20) }
                .thenByDescending { popularity(it.first) }
                .thenBy { it.third },
        ).map { it.first }
    }
}
