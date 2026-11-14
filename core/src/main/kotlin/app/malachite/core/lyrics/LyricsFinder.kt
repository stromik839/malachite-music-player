package app.malachite.core.lyrics

import app.malachite.core.net.Http
import app.malachite.core.net.QueryVariants
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.num
import app.malachite.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/**
 * Finds lyrics for a song online. No keys are needed. Synced lyrics are tried first, plain
 * text only when no synced version exists:
 *  1. LRCLIB (lrclib.net) – the biggest open database of synced lyrics (also has plain text);
 *  2. NetEase Cloud Music – synced lyrics for many popular songs;
 *  3. Genius – plain text, very good for Russian songs;
 *  4. lyrics.ovh – plain text, last resort.
 *
 * The title is cleaned first: "Алена Апина - "Лёха" (Official Video)" is searched as
 * "Лёха" by "Алена Апина". Every candidate is scored by title, artist and – most importantly
 * for syncing – duration, so that lyrics for another version (live, remix) are not picked.
 */
class LyricsFinder(private val http: Http = Http()) {

    data class Candidate(
        val lyrics: Lyrics,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Double,
        val score: Double,
        val source: String,
        /** Written in another alphabet than the song (a transliteration or a translation): never chosen by itself. */
        val foreignLetters: Boolean = false,
    ) {
        val synced get() = lyrics.synced
    }

    /**
     * Returns candidates, best first: synced ones that match well enough come before any plain
     * text. [durationMs] of the local file is used to reject other versions of the song.
     */
    fun search(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0,
        cleanOptions: LyricsCleaner.Options = LyricsCleaner.Options(),
    ): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        val queries = QueryVariants.build(artist, title, allowSwap = true, isTitle = true)
        if (queries.isEmpty()) return emptyList()
        val dur = durationMs / 1000.0

        fun add(lyrics: Lyrics, t: String, a: String, al: String, d: Double, source: String, penalty: Double = 0.0) {
            val foreign = LyricsScript.foreignLetters(title, artist, lyrics)
            // "I dve tysyachi let voyna" for «Звезда по имени Солнце»: shown, but never good enough by itself
            val score = (score(title, artist, dur, t, a, d, lyrics.synced) - penalty - (if (foreign) FOREIGN_LETTERS_PENALTY else 0.0)).coerceIn(0.0, 1.0)
            val key = "$source|${t.lowercase()}|${a.lowercase()}|${lyrics.synced}|${d.toInt()}|$foreign"
            val prev = found[key]
            if (prev == null || prev.score < score) found[key] = Candidate(lyrics, t, a, al, d, score, source, foreign)
        }

        fun synced(text: String, source: String): Lyrics = Lrc.parse(text, source).let { l ->
            l.copy(lines = l.lines.mapNotNull { line ->
                if (CREDIT.containsMatchIn(line.text)) null
                else LyricsCleaner.cleanLine(line.text, cleanOptions)?.let { line.copy(text = it) }
            })
        }

        fun plain(text: String, source: String): Lyrics = Lyrics.plain(LyricsCleaner.clean(text, cleanOptions), source)

        fun considerLrclib(el: JsonElement?, q: QueryVariants.Query) {
            if (el == null) return
            val s = el["syncedLyrics"].str()
            val p = el["plainLyrics"].str()
            val instrumental = el["instrumental"]?.toString() == "true"
            if (s.isNullOrBlank() && p.isNullOrBlank() && !instrumental) return
            val t = el["trackName"].str() ?: el["name"].str() ?: q.second
            val a = el["artistName"].str() ?: q.artist
            val al = el["albumName"].str().orEmpty()
            val d = el["duration"].num() ?: 0.0
            when {
                !s.isNullOrBlank() -> {
                    add(synced(s, "LRCLIB"), t, a, al, d, "LRCLIB")
                    // the plain version as well: used when the synced one turns out wrong
                    if (!p.isNullOrBlank()) add(plain(p, "LRCLIB"), t, a, al, d, "LRCLIB")
                }
                !p.isNullOrBlank() -> add(plain(p, "LRCLIB"), t, a, al, d, "LRCLIB")
                else -> add(Lyrics(emptyList(), synced = false, source = "LRCLIB (instrumental)"), t, a, al, d, "LRCLIB")
            }
        }

        fun goodSynced(min: Double) = found.values.any { it.synced && it.lyrics.lines.isNotEmpty() && it.score >= min }
        fun goodPlain(min: Double) = found.values.any { !it.synced && it.lyrics.lines.isNotEmpty() && it.score >= min }

        // 1) LRCLIB: exact signature, then its search, for the best few spellings
        for ((i, q) in queries.take(4).withIndex()) {
            runCatching {
                val url = StringBuilder(LRCLIB + "get?artist_name=").append(Http.enc(q.artist))
                    .append("&track_name=").append(Http.enc(q.second))
                if (album.isNotBlank() && i == 0) url.append("&album_name=").append(Http.enc(album))
                if (dur > 0) url.append("&duration=").append(dur.toInt())
                considerLrclib(http.getJson(url.toString()), q)
            }
            if (goodSynced(0.9)) break
            runCatching {
                val url = LRCLIB + "search?track_name=" + Http.enc(q.second) +
                    (if (q.artist.isNotBlank()) "&artist_name=" + Http.enc(q.artist) else "")
                http.getJson(url).arr()?.take(15)?.forEach { considerLrclib(it, q) }
            }
            if (goodSynced(0.85)) break
        }
        // free text finds songs whose artist is spelled differently there
        if (!goodSynced(0.85)) {
            for (q in queries.take(2)) {
                runCatching {
                    http.getJson(LRCLIB + "search?q=" + Http.enc("${q.artist} ${q.second}".trim())).arr()?.take(15)?.forEach { considerLrclib(it, q) }
                }
                if (goodSynced(0.85)) break
            }
        }

        // 2) NetEase: synced lyrics for many popular songs
        if (!goodSynced(0.8)) {
            for (q in queries.take(2)) {
                runCatching { netease(q) { lrc, t, a, al, d -> add(synced(lrc, "NetEase"), t, a, al, d, "NetEase") } }
                if (goodSynced(0.8)) break
            }
        }

        // 3) Plain text, only when there is no synced version
        if (!goodSynced(MIN_SCORE) && !goodPlain(0.85)) {
            for (q in queries.take(2)) {
                runCatching { genius(q) { text, t, a -> add(plain(text, "Genius"), t, a, "", 0.0, "Genius") } }
                if (goodPlain(0.8)) break
            }
        }
        if (!goodSynced(MIN_SCORE) && !goodPlain(0.8)) {
            for (q in queries.take(2)) {
                if (q.artist.isBlank() || q.second.isBlank()) continue
                runCatching {
                    val el = http.getJson("https://api.lyrics.ovh/v1/" + Http.encPath(q.artist) + "/" + Http.encPath(q.second))
                    val text = el["lyrics"].str()?.let { stripOvhHeader(it) }
                    // the service gives no names back: it found something for exactly this query
                    if (!text.isNullOrBlank()) add(plain(text, "lyrics.ovh"), q.second, q.artist, "", 0.0, "lyrics.ovh", penalty = 0.05)
                }
                if (goodPlain(0.75)) break
            }
        }

        val sorted = found.values.sortedWith(
            compareByDescending<Candidate> { it.synced && it.lyrics.lines.isNotEmpty() && it.score >= MIN_SCORE }
                .thenByDescending { it.lyrics.lines.isNotEmpty() && it.score >= MIN_SCORE }
                .thenByDescending { it.score },
        )
        app.malachite.core.CoreLog.i(
            "lyrics",
            "«$artist» — «$title» (ищу как «${queries.first().artist}» — «${queries.first().second}»): ${sorted.size} вариантов" +
                (sorted.firstOrNull()?.let { ", лучший ${it.source} ${if (it.synced) "синхр." else "обычный"} ${(it.score * 100).toInt()}%" } ?: "") +
                (sorted.count { it.foreignLetters }.takeIf { it > 0 }?.let { ", другим алфавитом (не берутся сами): $it" } ?: ""),
        )
        return sorted
    }

    /** Best candidate that is good enough to be applied without asking: synced first, then plain. */
    fun best(title: String, artist: String, album: String = "", durationMs: Long = 0, cleanOptions: LyricsCleaner.Options = LyricsCleaner.Options()): Candidate? =
        pick(search(title, artist, album, durationMs, cleanOptions))

    // ------------------------------------------------------------------ NetEase

    private fun netease(q: QueryVariants.Query, out: (String, String, String, String, Double) -> Unit) {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        // the old "search/get/web" endpoint now answers with an encrypted string
        val songs = listOf(
            "https://music.163.com/api/cloudsearch/pc?s=$term&type=1&offset=0&limit=8",
            "https://music.163.com/api/search/pc?s=$term&type=1&offset=0&limit=8",
        ).firstNotNullOfOrNull { url ->
            runCatching { http.getJson(url, browser = true)["result"]["songs"].arr() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        var tried = 0
        for (s in songs) {
            val id = s["id"].str() ?: continue
            val t = s["name"].str().orEmpty()
            val a = (s["artists"].arr() ?: s["ar"].arr())?.firstOrNull()?.get("name").str().orEmpty()
            val al = (s["album"] ?: s["al"])["name"].str().orEmpty()
            val d = ((s["duration"] ?: s["dt"]).num() ?: 0.0) / 1000.0
            // do not download lyrics of songs that obviously are something else
            if (QueryVariants.similarity(q.second, t) < 0.6) continue
            if (++tried > 3) break
            val lyr = http.getJson("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1", browser = true)
            val lrc = lyr["lrc"]["lyric"].str()
            if (!lrc.isNullOrBlank() && Lrc.looksSynced(lrc)) out(lrc, t, a, al, d)
        }
    }

    // ------------------------------------------------------------------ Genius

    private fun genius(q: QueryVariants.Query, out: (String, String, String) -> Unit) {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        val res = http.getJson("https://genius.com/api/search/song?q=$term&per_page=5", browser = true) ?: return
        val hits = res["response"]["sections"].arr()?.firstOrNull()?.get("hits").arr().orEmpty()
        var tried = 0
        for (h in hits) {
            val r = h["result"]
            val t = r["title"].str().orEmpty()
            val a = r["primary_artist"]["name"].str() ?: r["artist_names"].str().orEmpty()
            val url = r["url"].str() ?: continue
            if (QueryVariants.similarity(q.second, t) < 0.6 && QueryVariants.similarity(q.second, QueryVariants.stripVersionTags(t)) < 0.6) continue
            if (++tried > 2) break
            val html = http.get(url, browser = true, accept = "text/html") ?: continue
            val text = HtmlLyrics.genius(html)
            if (!text.isNullOrBlank()) out(text, t, a)
        }
    }

    private fun stripOvhHeader(s: String): String =
        s.lineSequence().dropWhile { it.isBlank() || it.startsWith("Paroles de la chanson") }.joinToString("\n")

    companion object {
        const val LRCLIB = "https://lrclib.net/api/"
        const val MIN_SCORE = 0.7

        /** Takes a perfect match below [MIN_SCORE]: the text is offered in the list, not applied. */
        const val FOREIGN_LETTERS_PENALTY = 0.4

        /** Credits that NetEase puts into the lyrics themselves ("作词 : …"). */
        private val CREDIT = Regex("^\\s*(作词|作曲|编曲|制作人|制作|混音|母带|监制|出品|和声|吉他|贝斯|鼓|词|曲)\\s*[:：]")

        /** Synced lyrics that match well win over plain text; plain text is the fallback. */
        fun pick(candidates: List<Candidate>): Candidate? =
            candidates.filter { it.synced && it.lyrics.lines.isNotEmpty() && it.score >= MIN_SCORE }.maxByOrNull { it.score }
                ?: candidates.filter { !it.synced && it.lyrics.lines.isNotEmpty() && it.score >= MIN_SCORE }.maxByOrNull { it.score }
                ?: candidates.firstOrNull { it.lyrics.lines.isEmpty() && it.score >= 0.85 } // instrumental

        fun score(title: String, artist: String, durSec: Double, cTitle: String, cArtist: String, cDur: Double, synced: Boolean): Double {
            val clean = app.malachite.core.text.Watermarks.searchTitle(title, artist)
            val ts = maxOf(
                QueryVariants.similarity(title, cTitle),
                QueryVariants.similarity(clean, cTitle),
                QueryVariants.similarity(QueryVariants.stripVersionTags(clean), QueryVariants.stripVersionTags(cTitle)),
            )
            val asim = if (artist.isBlank()) 0.6 else maxOf(
                QueryVariants.similarity(artist, cArtist),
                QueryVariants.similarity(app.malachite.core.text.Watermarks.primaryArtist(artist), app.malachite.core.text.Watermarks.primaryArtist(cArtist)),
            )
            var s = ts * 0.55 + asim * 0.35
            if (durSec > 0 && cDur > 0) {
                val diff = abs(durSec - cDur)
                s += when {
                    diff <= 2 -> 0.15
                    diff <= 5 -> 0.08
                    diff <= 10 -> 0.0
                    diff <= 20 -> -0.15
                    else -> -0.4 // almost certainly another version: sync would be wrong
                }
            } else s += 0.05
            if (synced) s += 0.05
            return s.coerceIn(0.0, 1.0)
        }
    }
}

/** Pulls the lyrics text out of a web page. */
internal object HtmlLyrics {
    private val DIV = Regex("<div\\b|</div>", RegexOption.IGNORE_CASE)
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val NUM_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

    /** Genius: every `<div data-lyrics-container="true">`, without the "N Contributors…" header. */
    fun genius(html: String): String? {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val i = html.indexOf("data-lyrics-container=\"true\"", from)
            if (i < 0) break
            val open = html.lastIndexOf("<div", i)
            val start = html.indexOf('>', i) + 1
            val end = closingDiv(html, open)
            if (open < 0 || start <= 0 || end < start) break
            out.append(removeExcluded(html.substring(start, end))).append("<br/>")
            from = end
        }
        if (out.isEmpty()) return null
        var s = BR.replace(out, "\n")
        s = TAG.replace(s, "")
        s = unescape(s)
        return s.lines().joinToString("\n") { it.trim() }.trim().ifEmpty { null }
    }

    /** Index of the `</div>` that closes the div opening at [open]. */
    private fun closingDiv(html: String, open: Int): Int {
        if (open < 0) return -1
        var depth = 0
        var m = DIV.find(html, open)
        while (m != null) {
            if (m.value.startsWith("</")) {
                depth--
                if (depth == 0) return m.range.first
            } else depth++
            m = DIV.find(html, m.range.last + 1)
        }
        return -1
    }

    private fun removeExcluded(s: String): String {
        var r = s
        while (true) {
            val i = r.indexOf("data-exclude-from-selection=\"true\"")
            if (i < 0) return r
            val open = r.lastIndexOf("<div", i)
            val end = closingDiv(r, open)
            if (open < 0 || end < 0) return r
            r = r.substring(0, open) + r.substring(end + "</div>".length)
        }
    }

    fun unescape(s: String): String {
        var r = NUM_ENTITY.replace(s) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toIntOrNull(16) else m.groupValues[2].toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        r = r.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        return r
    }
}
