package app.malachite.core.cover

import app.malachite.core.net.Http
import app.malachite.core.net.QueryVariants
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.str
import app.malachite.core.text.TextFold
import app.malachite.core.text.Watermarks

/**
 * Looks up which album a song belongs to when neither its tags nor its folders say it
 * (Deezer, iTunes – Russian and US store – and MusicBrainz; no keys). Compilations ("Лучшее",
 * "Greatest Hits", "Various Artists") and singles named like the song are pushed down: the
 * real album is wanted. Results are suggestions; very sure ones (two services agree) come
 * pre-selected, but nothing is written without the user pressing "apply".
 */
class AlbumFinder(private val http: Http = Http()) {

    data class Guess(
        val album: String,
        val year: String?,
        val source: String,
        val score: Double,
        /** How many different services named this album. */
        val sources: Int = 1,
        /** true / false when the service says whether the release is a single, null if unknown. */
        val single: Boolean? = null,
    ) {
        val confident get() = score >= 0.9 && sources >= 2
    }

    /**
     * [durationMs] of the local file tells apart two recordings of one song on different
     * albums ("Когда я ответил да": 3:20 on one album, 4:30 on another).
     */
    fun find(artist: String, title: String, durationMs: Long = 0): List<Guess> {
        val clean = Watermarks.searchTitle(title, artist)
        val dur = durationMs / 1000.0
        val hits = ArrayList<Guess>()
        fun strong() = hits.filter { it.score >= 0.85 }.map { it.source }.toSet().size
        for (q in QueryVariants.build(artist, title, allowSwap = false, isTitle = true).take(2)) {
            runCatching { hits += deezer(q, artist, clean, dur) }
            runCatching { hits += itunes(q, artist, clean, "RU", dur) }
            if (strong() < 2) runCatching { hits += itunes(q, artist, clean, "US", dur) }
            if (strong() < 2) runCatching { hits += musicBrainz(q, artist, clean, dur) }
            if (strong() >= 2) break
        }
        val titleKey = TextFold.strictKey(clean)
        return hits.groupBy { TextFold.strictKey(Watermarks.albumBase(it.album)) }
            .filterKeys { it.isNotEmpty() }
            .map { (_, gs) ->
                val best = gs.maxByOrNull { it.score }!!
                val services = gs.filter { it.score >= 0.7 }.map { it.source.substringBefore(' ') }.toSet().size
                val support = (services - 1).coerceIn(0, 2) * 0.04
                // A single named like the song is not an album. The title track of a real album
                // ("Группа крови" on "Группа крови") is: iTunes / MusicBrainz tell them apart.
                val single = when {
                    TextFold.strictKey(best.album) != titleKey -> 0.0
                    gs.any { it.single == false } -> 0.0
                    gs.any { it.single == true } -> 0.15
                    else -> 0.05
                }
                best.copy(
                    score = (best.score + support - single).coerceIn(0.0, 1.0),
                    year = gs.mapNotNull { it.year }.minOrNull(),
                    sources = services,
                )
            }
            .filter { it.score >= 0.75 }
            // equal matches: more services, then the original (earliest) release, not a later reissue
            .sortedWith(
                compareByDescending<Guess> { Math.round(it.score * 20) }
                    .thenByDescending { it.sources }
                    .thenBy { it.year ?: "9999" },
            )
    }

    /** A recording of another length is most likely another version (live, remix, re-recording). */
    private fun durationBonus(durationSec: Double, candidateSec: Double?): Double {
        if (durationSec <= 0 || candidateSec == null || candidateSec <= 0) return 0.0
        val d = kotlin.math.abs(durationSec - candidateSec)
        return when {
            d <= 3 -> 0.03
            d <= 8 -> 0.0
            d <= 20 -> -0.1
            else -> -0.2
        }
    }

    /** An artist named by a service for a song on an album. */
    data class ArtistGuess(val artist: String, val source: String, val score: Double, val sources: Int = 1, val popularity: Long = 0) {
        val confident get() = score >= 0.9 && sources >= 2
    }

    /**
     * Who sang [title] on the album [album]: asked when the artist tag is empty or junk while
     * the album is right. The song and the album must both answer, so a cover version on another
     * album does not count.
     */
    fun findArtist(title: String, album: String, durationMs: Long = 0): List<ArtistGuess> {
        val song = Watermarks.searchTitle(title, "")
        if (song.isBlank() || album.isBlank()) return emptyList()
        val dur = durationMs / 1000.0
        val hits = ArrayList<ArtistGuess>()
        fun rate(cTitle: String, cAlbum: String, cSec: Double?): Double =
            maxOf(QueryVariants.similarity(song, cTitle), QueryVariants.similarity(QueryVariants.stripVersionTags(song), QueryVariants.stripVersionTags(cTitle))) * 0.6 +
                QueryVariants.similarity(Watermarks.albumBase(album), Watermarks.albumBase(cAlbum)) * 0.4 + durationBonus(dur, cSec)
        runCatching {
            val q = "track:\"${song.replace("\"", "")}\" album:\"${album.replace("\"", "")}\""
            val json = http.getJson("https://api.deezer.com/search?q=${Http.enc(q)}&limit=15")
            for (r in json["data"].arr().orEmpty()) {
                val who = r["artist"]["name"].str()?.takeIf { it.isNotBlank() } ?: continue
                hits += ArtistGuess(
                    who, "Deezer", rate(r["title"].str().orEmpty(), r["album"]["title"].str().orEmpty(), r["duration"].str()?.toDoubleOrNull()),
                    popularity = r["rank"].str()?.toLongOrNull() ?: 0,
                )
            }
        }
        for (country in listOf("RU", "US")) {
            if (hits.count { it.score >= 0.85 } >= 2) break
            runCatching {
                val json = http.getJson("https://itunes.apple.com/search?term=${Http.enc("$song $album")}&entity=song&limit=20&country=$country")
                for (r in json["results"].arr().orEmpty()) {
                    val who = r["artistName"].str()?.takeIf { it.isNotBlank() } ?: continue
                    val cAlbum = r["collectionName"].str().orEmpty().removeSuffix(" - Single").removeSuffix(" - EP")
                    hits += ArtistGuess(who, "iTunes $country", rate(r["trackName"].str().orEmpty(), cAlbum, r["trackTimeMillis"].str()?.toDoubleOrNull()?.div(1000)))
                }
            }
        }
        return hits.groupBy { TextFold.strictKey(it.artist) }
            .filterKeys { it.isNotEmpty() }
            .map { (_, gs) ->
                val best = gs.maxByOrNull { it.score }!!
                val services = gs.filter { it.score >= 0.8 }.map { it.source.substringBefore(' ') }.toSet().size
                best.copy(
                    score = (best.score + (services - 1).coerceIn(0, 1) * 0.04).coerceAtMost(1.0),
                    sources = services.coerceAtLeast(1),
                    popularity = gs.maxOf { it.popularity },
                )
            }
            .filter { it.score >= 0.8 }
            // equal matches (a cover on an album of the same name): the better known artist first
            .sortedWith(compareByDescending<ArtistGuess> { Math.round(it.score * 20) }.thenByDescending { it.sources }.thenByDescending { it.popularity })
    }

    private fun score(artist: String, title: String, cArtist: String, cTitle: String): Double =
        QueryVariants.similarity(artist, cArtist).coerceAtLeast(
            QueryVariants.similarity(Watermarks.primaryArtist(artist), Watermarks.primaryArtist(cArtist)),
        ) * 0.45 +
            maxOf(QueryVariants.similarity(title, cTitle), QueryVariants.similarity(QueryVariants.stripVersionTags(title), QueryVariants.stripVersionTags(cTitle))) * 0.55

    private fun deezer(q: QueryVariants.Query, artist: String, title: String, dur: Double): List<Guess> {
        fun run(query: String): List<Guess> {
            val json = http.getJson("https://api.deezer.com/search?q=${Http.enc(query)}&limit=15") ?: return emptyList()
            return json["data"].arr().orEmpty().mapNotNull { r ->
                val album = r["album"]["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val s = score(artist, title, r["artist"]["name"].str().orEmpty(), r["title"].str().orEmpty()) +
                    durationBonus(dur, r["duration"].str()?.toDoubleOrNull())
                Guess(album, null, "Deezer", s - compilationPenalty(album, ""))
            }
        }
        val strict = run("artist:\"${q.artist.replace("\"", "")}\" track:\"${q.second.replace("\"", "")}\"")
        return strict.ifEmpty { run("${q.artist} ${q.second}".trim()) }
    }

    private fun itunes(q: QueryVariants.Query, artist: String, title: String, country: String, dur: Double): List<Guess> {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://itunes.apple.com/search?term=$term&entity=song&limit=20&country=$country") ?: return emptyList()
        return json["results"].arr().orEmpty().mapNotNull { r ->
            val raw = r["collectionName"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val album = raw.removeSuffix(" - Single").removeSuffix(" - EP")
            val year = r["releaseDate"].str()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            val s = score(artist, title, r["artistName"].str().orEmpty(), r["trackName"].str().orEmpty()) +
                durationBonus(dur, r["trackTimeMillis"].str()?.toDoubleOrNull()?.div(1000))
            val tracks = r["trackCount"].str()?.toIntOrNull()
            val single = raw.endsWith(" - Single") || (tracks != null && tracks <= 3)
            Guess(album, year, "iTunes $country", s - compilationPenalty(album, r["collectionArtistName"].str().orEmpty()), single = single)
        }
    }

    private fun musicBrainz(q: QueryVariants.Query, artist: String, title: String, dur: Double): List<Guess> {
        fun esc(s: String) = s.replace("\\", "").replace("\"", "")
        val query = Http.enc("recording:\"${esc(q.second)}\" AND artist:\"${esc(q.artist)}\"")
        val json = http.getJson("https://musicbrainz.org/ws/2/recording/?query=$query&fmt=json&limit=10") ?: return emptyList()
        val out = ArrayList<Guess>()
        for (rec in json["recordings"].arr().orEmpty()) {
            val ca = rec["artist-credit"].arr()?.firstOrNull()?.get("name").str().orEmpty()
            val s = score(artist, title, ca, rec["title"].str().orEmpty()) + durationBonus(dur, rec["length"].str()?.toDoubleOrNull()?.div(1000))
            if (s < 0.7) continue
            for (rel in rec["releases"].arr().orEmpty()) {
                val name = rel["title"].str()?.takeIf { it.isNotBlank() } ?: continue
                val group = rel["release-group"]
                val secondary = group["secondary-types"].arr().orEmpty().mapNotNull { it.str() }
                val penalty = when {
                    secondary.any { it.equals("Compilation", true) || it.equals("Live", true) || it.equals("Remix", true) } -> 0.2
                    rel["status"].str()?.equals("Official", true) == false -> 0.1
                    else -> 0.0
                } + compilationPenalty(name, rel["artist-credit"].arr()?.firstOrNull()?.get("name").str().orEmpty())
                val year = rel["date"].str()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                val type = group["primary-type"].str()
                out += Guess(name, year, "MusicBrainz", s - 0.02 - penalty, single = type?.let { it.equals("Single", true) })
            }
        }
        return out
    }

    companion object {
        private val COMPILATION = Regex(
            "(^|[^\\p{L}])(best of|greatest hits|the best|hits|collection|anthology|essential|gold|" +
                "сборник|лучшее|лучшие|хиты|золот\\p{L}*|антология|избранное|коллекция|дискотека|хит-парад|" +
                "караоке|мегамикс|супер ?хит\\p{L}*|народн\\p{L}* хит\\p{L}*|радио|" +
                // live recordings are not the studio album either
                "live|concert|unplugged|концерт\\p{L}*|живой звук|акустика)([^\\p{L}]|$)",
            RegexOption.IGNORE_CASE,
        )

        fun isCompilation(album: String, albumArtist: String = ""): Boolean =
            COMPILATION.containsMatchIn(album) || albumArtist.equals("Various Artists", true) ||
                albumArtist.equals("Сборник", true) || albumArtist.equals("Разные исполнители", true)

        private fun compilationPenalty(album: String, albumArtist: String) = if (isCompilation(album, albumArtist)) 0.2 else 0.0
    }
}
