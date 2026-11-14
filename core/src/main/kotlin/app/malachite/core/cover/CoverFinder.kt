package app.malachite.core.cover

import app.malachite.core.i18n.tr
import app.malachite.core.net.Http
import app.malachite.core.net.QueryVariants
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.str

/**
 * Finds album covers online from the artist / album / title copied out of the file's tags.
 *
 * Sources (no keys): iTunes Search, Deezer, MusicBrainz + Cover Art Archive.
 * The query is retried with cleaned names, artist and album swapped (tags written the wrong
 * way round) and Cyrillic <-> Latin transliteration ("sviditelstvo o smerti" ->
 * "свидительство о смерти"). Every result is scored against the original names.
 */
class CoverFinder(private val http: Http = Http()) {

    data class Candidate(
        val imageUrl: String,
        val previewUrl: String,
        val artist: String,
        val album: String,
        val source: String,
        val score: Double,
        val note: String,
        /** More addresses of the same picture to try when [imageUrl] is not there (smaller sizes). */
        val fallbackUrls: List<String> = emptyList(),
        /** The picture is wider than the cover inside it: cut a centred square (video thumbnails). */
        val square: Boolean = false,
    )

    /**
     * [link] is whatever address the file itself carries (the comment tag written by a
     * downloader): a YouTube link gives the picture of exactly this recording.
     */
    fun search(artist: String, album: String, title: String = "", limit: Int = 24, durationMs: Long = 0, link: String = ""): List<Candidate> {
        val dur = durationMs / 1000.0
        // the same song in another length is another recording (live, re-recording): another cover
        fun durBonus(sec: Double?): Double {
            if (dur <= 0 || sec == null || sec <= 0) return 0.0
            val d = kotlin.math.abs(dur - sec)
            return if (d <= 3) 0.03 else if (d <= 8) 0.0 else if (d <= 20) -0.08 else -0.15
        }
        val results = LinkedHashMap<String, Candidate>()
        fun add(c0: Candidate) {
            val c = c0.copy(score = c0.score.coerceIn(0.0, 1.0))
            val prev = results[c.imageUrl]
            if (prev == null || prev.score < c.score) results[c.imageUrl] = c
        }
        youtubeFromLink(link, artist, album.ifBlank { title })?.let(::add)
        val albumQueries = if (album.isNotBlank()) QueryVariants.build(artist, album) else emptyList()
        for ((i, q) in albumQueries.withIndex()) {
            // Russian releases are often only in the Russian iTunes store
            runCatching { itunesAlbums(q, artist, album, "RU").forEach(::add) }
            runCatching { deezerAlbums(q, artist, album).forEach(::add) }
            if (results.values.none { it.score >= 0.85 }) runCatching { itunesAlbums(q, artist, album, "US").forEach(::add) }
            if (results.values.count { it.score >= 0.85 } >= 2) break
            if (i >= 4) break
        }
        fun found(min: Double) = results.values.any { it.score >= min && it.source != YOUTUBE }
        if (!found(0.8) && album.isNotBlank()) {
            val q = albumQueries.first()
            runCatching { musicBrainz(q, artist, album).forEach(::add) }
            // extra catalogues: big for Russian music (Yandex) and for independent bands (Bandcamp)
            if (!found(0.85)) runCatching { yandex(q, artist, album, songs = false).forEach(::add) }
            if (!found(0.85)) runCatching { bandcamp(q, artist, album, songs = false).forEach(::add) }
        }
        // Single / no album: look the song up and use its album's cover.
        if (!found(0.75) && title.isNotBlank()) {
            // "Алена Апина - "Лёха" (Official Video)" is looked up as "Лёха"
            val clean = app.malachite.core.text.Watermarks.searchTitle(title, artist)
            for (q in QueryVariants.build(artist, title, allowSwap = false, isTitle = true).take(3)) {
                runCatching { itunesSongs(q, artist, clean, album, "RU", ::durBonus).forEach(::add) }
                runCatching { deezerTracks(q, artist, clean, album, ::durBonus).forEach(::add) }
                if (!found(0.8)) runCatching { itunesSongs(q, artist, clean, album, "US", ::durBonus).forEach(::add) }
                if (found(0.8)) break
            }
            if (!found(0.8)) {
                val q = QueryVariants.build(artist, title, allowSwap = false, isTitle = true).first()
                runCatching { yandex(q, artist, clean, songs = true).forEach(::add) }
                if (!found(0.8)) runCatching { bandcamp(q, artist, clean, songs = true).forEach(::add) }
                if (!found(0.8)) runCatching { genius(q, artist, clean).forEach(::add) }
                if (!found(0.8)) runCatching { musicBrainzSongs(q, artist, clean).forEach(::add) }
            }
        }
        val sorted = results.values.sortedByDescending { it.score }.take(limit)
        app.malachite.core.CoreLog.i(
            "cover",
            "«$artist» / «$album» / «$title»: ${sorted.size} вариантов" + // i18n:off – a line of the log
                (sorted.firstOrNull()?.let { ", лучший ${it.source} «${it.artist} — ${it.album}» ${(it.score * 100).toInt()}%" } ?: ""), // i18n:off
        )
        return sorted
    }

    /** Best match good enough for automatic healing (no user confirmation). */
    fun best(artist: String, album: String, title: String = "", durationMs: Long = 0, link: String = ""): Candidate? =
        search(artist, album, title, durationMs = durationMs, link = link).firstOrNull { it.score >= AUTO_THRESHOLD }

    fun download(c: Candidate): ByteArray? =
        (listOf(c.imageUrl) + c.fallbackUrls + c.previewUrl).distinct().firstNotNullOfOrNull { http.download(it) }

    // ------------------------------------------------------------------ the file's own link

    /**
     * The picture of the YouTube video the file was downloaded from. For music uploads it is
     * the cover itself in the middle of a wide frame, so a centred square is cut out of it.
     */
    internal fun youtubeFromLink(link: String, artist: String, name: String): Candidate? {
        val id = YOUTUBE_ID.find(link)?.groupValues?.get(1) ?: return null
        val base = "https://i.ytimg.com/vi/$id/"
        return Candidate(
            imageUrl = base + "maxresdefault.jpg", previewUrl = base + "mqdefault.jpg",
            artist = artist, album = name, source = YOUTUBE,
            // the right recording for sure, but a video frame is not always a cover: just above
            // the automatic threshold, below any exact album match
            score = 0.84, note = "from the link in the file",
            fallbackUrls = listOf(base + "hq720.jpg", base + "mqdefault.jpg"), square = true,
        )
    }

    // ------------------------------------------------------------------ extra catalogues

    private fun yandex(q: QueryVariants.Query, artist: String, name: String, songs: Boolean): List<Candidate> {
        val text = Http.enc("${q.artist} ${q.second}".trim())
        val type = if (songs) "track" else "album"
        val json = http.getJson("https://api.music.yandex.net/search?text=$text&type=$type&page=0&nocorrect=false", optional = true) ?: return emptyList()
        val list = json["result"][if (songs) "tracks" else "albums"]["results"].arr().orEmpty()
        return list.take(10).mapNotNull { r ->
            val alb = if (songs) r["albums"].arr()?.firstOrNull() else r
            val uri = (alb["coverUri"].str() ?: r["coverUri"].str())?.takeIf { it.contains("%%") } ?: return@mapNotNull null
            val ca = r["artists"].arr()?.firstOrNull()?.get("name").str().orEmpty()
            val ct = r["title"].str().orEmpty()
            val cb = alb["title"].str().orEmpty()
            Candidate(
                "https://" + uri.replace("%%", "1000x1000"), "https://" + uri.replace("%%", "200x200"),
                ca, cb, tr("Яндекс Музыка"), score(artist, name, ca, ct) - 0.01, if (songs) "$BY_SONG ${q.note}" else q.note,
            )
        }
    }

    private fun bandcamp(q: QueryVariants.Query, artist: String, name: String, songs: Boolean): List<Candidate> {
        fun esc(s: String) = s.replace("\\", " ").replace("\"", " ")
        val body = "{\"search_text\":\"${esc("${q.artist} ${q.second}".trim())}\",\"search_filter\":\"${if (songs) "t" else "a"}\",\"full_page\":false,\"fan_id\":null}"
        val json = http.postJson("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic", body, optional = true) ?: return emptyList()
        return json["auto"]["results"].arr().orEmpty().take(10).mapNotNull { r ->
            val img = r["img"].str()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val ca = r["band_name"].str().orEmpty()
            val ct = r["name"].str().orEmpty()
            val cb = if (songs) r["album_name"].str().orEmpty().ifBlank { ct } else ct
            // the big picture is addressed by the art number; the address from the answer is the small one
            val big = r["art_id"].str()?.toLongOrNull()?.let { "https://f4.bcbits.com/img/a%010d_10.jpg".format(it) }
            val sized = BANDCAMP_SIZE.replace(img, "_10.jpg")
            Candidate(
                big ?: sized, img, ca, cb, "Bandcamp",
                score(artist, name, ca, ct) - 0.01, if (songs) "$BY_SONG ${q.note}" else q.note,
                fallbackUrls = listOf(sized, BANDCAMP_SIZE.replace(img, "_16.jpg")),
            )
        }
    }

    private fun genius(q: QueryVariants.Query, artist: String, title: String): List<Candidate> {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://genius.com/api/search/song?q=$term&per_page=5", browser = true, optional = true) ?: return emptyList()
        return json["response"]["sections"].arr()?.firstOrNull()?.get("hits").arr().orEmpty().mapNotNull { h ->
            val r = h["result"]
            val art = r["song_art_image_url"].str()?.takeIf { it.startsWith("http") && !it.contains("default_cover") } ?: return@mapNotNull null
            val ca = r["primary_artist"]["name"].str().orEmpty()
            val ct = r["title"].str().orEmpty()
            Candidate(art, r["song_art_image_thumbnail_url"].str() ?: art, ca, ct, "Genius", score(artist, title, ca, ct) - 0.03, "$BY_SONG ${q.note}")
        }
    }

    private fun musicBrainzSongs(q: QueryVariants.Query, artist: String, title: String): List<Candidate> {
        fun esc(s: String) = s.replace("\\", "").replace("\"", "")
        val query = Http.enc("recording:\"${esc(q.second)}\" AND artist:\"${esc(q.artist)}\"")
        val json = http.getJson("https://musicbrainz.org/ws/2/recording/?query=$query&fmt=json&limit=5") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (rec in json["recordings"].arr().orEmpty()) {
            val ca = rec["artist-credit"].arr()?.firstOrNull()?.get("name").str().orEmpty()
            val s = score(artist, title, ca, rec["title"].str().orEmpty())
            if (s < 0.7) continue
            for (rel in rec["releases"].arr().orEmpty().take(3)) {
                val id = rel["id"].str() ?: continue
                val cb = rel["title"].str().orEmpty()
                out += Candidate(
                    "https://coverartarchive.org/release/$id/front-1200", "https://coverartarchive.org/release/$id/front-250",
                    ca, cb, "MusicBrainz", s - 0.04 - (if (AlbumFinder.isCompilation(cb)) 0.1 else 0.0), "$BY_SONG ${q.note}",
                    fallbackUrls = listOf("https://coverartarchive.org/release/$id/front-500"),
                )
            }
        }
        return out
    }

    private fun score(artist: String, album: String, cArtist: String, cAlbum: String): Double {
        val a = if (artist.isBlank()) 0.5 else QueryVariants.similarity(artist, cArtist)
        val b = if (album.isBlank()) 0.5 else maxOf(
            QueryVariants.similarity(album, cAlbum),
            QueryVariants.similarity(
                app.malachite.core.text.Watermarks.albumBase(QueryVariants.clean(album)),
                app.malachite.core.text.Watermarks.albumBase(cAlbum),
            ),
        )
        return a * 0.45 + b * 0.55
    }

    private fun itunesAlbums(q: QueryVariants.Query, artist: String, album: String, country: String): List<Candidate> {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=$country") ?: return emptyList()
        return json["results"].arr().orEmpty().mapNotNull { r ->
            val art = r["artworkUrl100"].str() ?: return@mapNotNull null
            val ca = r["artistName"].str().orEmpty()
            val cb = r["collectionName"].str().orEmpty()
            Candidate(
                imageUrl = art.replace("100x100bb", "1200x1200bb"),
                previewUrl = art.replace("100x100bb", "300x300bb"),
                artist = ca, album = cb, source = "iTunes",
                score = score(artist, album, ca, cb), note = q.note,
            )
        }
    }

    private fun itunesSongs(q: QueryVariants.Query, artist: String, title: String, album: String, country: String, durBonus: (Double?) -> Double): List<Candidate> {
        val term = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://itunes.apple.com/search?term=$term&entity=song&limit=10&country=$country") ?: return emptyList()
        return json["results"].arr().orEmpty().mapNotNull { r ->
            val art = r["artworkUrl100"].str() ?: return@mapNotNull null
            val ca = r["artistName"].str().orEmpty()
            val ct = r["trackName"].str().orEmpty()
            val cb = r["collectionName"].str().orEmpty().removeSuffix(" - Single").removeSuffix(" - EP")
            val s = score(artist, title, ca, ct) * (if (album.isNotBlank()) 0.8 + 0.2 * QueryVariants.similarity(album, cb) else 1.0) -
                (if (AlbumFinder.isCompilation(cb, r["collectionArtistName"].str().orEmpty())) 0.1 else 0.0) +
                durBonus(r["trackTimeMillis"].str()?.toDoubleOrNull()?.div(1000))
            Candidate(art.replace("100x100bb", "1200x1200bb"), art.replace("100x100bb", "300x300bb"), ca, cb, "iTunes", s, "$BY_SONG ${q.note}")
        }
    }

    private fun deezerAlbums(q: QueryVariants.Query, artist: String, album: String): List<Candidate> {
        val query = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://api.deezer.com/search/album?q=$query&limit=10") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { r ->
            val xl = r["cover_xl"].str() ?: r["cover_big"].str() ?: return@mapNotNull null
            val ca = r["artist"]["name"].str().orEmpty()
            val cb = r["title"].str().orEmpty()
            Candidate(xl, r["cover_medium"].str() ?: xl, ca, cb, "Deezer", score(artist, album, ca, cb), q.note)
        }
    }

    private fun deezerTracks(q: QueryVariants.Query, artist: String, title: String, album: String, durBonus: (Double?) -> Double): List<Candidate> {
        val query = Http.enc("${q.artist} ${q.second}".trim())
        val json = http.getJson("https://api.deezer.com/search?q=$query&limit=10") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { r ->
            val alb = r["album"]
            val xl = alb["cover_xl"].str() ?: alb["cover_big"].str() ?: return@mapNotNull null
            val ca = r["artist"]["name"].str().orEmpty()
            val ct = r["title"].str().orEmpty()
            val cb = alb["title"].str().orEmpty()
            val s = score(artist, title, ca, ct) * (if (album.isNotBlank()) 0.8 + 0.2 * QueryVariants.similarity(album, cb) else 1.0) -
                (if (AlbumFinder.isCompilation(cb)) 0.1 else 0.0) + durBonus(r["duration"].str()?.toDoubleOrNull())
            Candidate(xl, alb["cover_medium"].str() ?: xl, ca, cb, "Deezer", s, "$BY_SONG ${q.note}")
        }
    }

    private fun musicBrainz(q: QueryVariants.Query, artist: String, album: String): List<Candidate> {
        val query = Http.enc("release:\"${q.second}\" AND artist:\"${q.artist}\"")
        val json = http.getJson("https://musicbrainz.org/ws/2/release/?query=$query&fmt=json&limit=5") ?: return emptyList()
        return json["releases"].arr().orEmpty().mapNotNull { r ->
            val id = r["id"].str() ?: return@mapNotNull null
            val cb = r["title"].str().orEmpty()
            val ca = r["artist-credit"].arr()?.firstOrNull()?.get("name").str().orEmpty()
            Candidate(
                "https://coverartarchive.org/release/$id/front-1200",
                "https://coverartarchive.org/release/$id/front-250",
                ca, cb, "MusicBrainz", score(artist, album, ca, cb) - 0.02, q.note,
            )
        }
    }

    companion object {
        const val AUTO_THRESHOLD = 0.82

        /** Note of candidates found through the song (not the album): their album is the song's album. */
        const val BY_SONG = "by song:"

        const val YOUTUBE = "YouTube"
        private val YOUTUBE_ID = Regex("(?:youtube\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/)|youtu\\.be/|music\\.youtube\\.com/watch\\?(?:[^\\s]*&)?v=)([A-Za-z0-9_-]{11})")
        private val BANDCAMP_SIZE = Regex("_\\d+\\.jpg$")
    }
}
