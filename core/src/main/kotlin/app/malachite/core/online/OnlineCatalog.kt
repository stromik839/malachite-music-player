package app.malachite.core.online

import app.malachite.core.i18n.tr
import app.malachite.core.CoreLog
import app.malachite.core.net.Http
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.num
import app.malachite.core.net.str
import app.malachite.core.text.Watermarks

/**
 * Music that may be listened to and downloaded from the internet, from catalogues that offer
 * it openly through a public interface (no keys, no accounts):
 *
 *  - **Audius** – a platform where musicians publish their own tracks. Every track can be
 *    listened to; it is offered for download only when its author switched downloads on.
 *  - **Internet Archive** (archive.org) – a public library: netlabels, concert recordings,
 *    old records, free releases. An entry there is a whole release with its files.
 *
 * Nothing here is used unless the user opened the online section and typed a search.
 */
class OnlineCatalog(private val http: Http = Http()) {

    @kotlinx.serialization.Serializable
    data class Item(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val source: String,
        /** Address to play from; empty for a release (open it with [tracksOf]). */
        val streamUrl: String,
        /** Address to download from, or null when the author did not allow downloading. */
        val downloadUrl: String?,
        val coverUrl: String?,
        val license: String,
        /** File extension of the download ("mp3", "flac", "ogg"). */
        val extension: String,
        /** A release with several files (Internet Archive): its songs come from [tracksOf]. */
        val isRelease: Boolean = false,
        /** Page of the track / release, for the curious and for the record of where a file came from. */
        val pageUrl: String = "",
        /** Only a 30-second excerpt can be played: the full song is not offered openly anywhere. */
        val preview: Boolean = false,
        /** The artist in the catalogue ("audius:<id>"), when the catalogue can list all their songs. */
        val artistId: String = "",
        /** Followers, fans, plays or downloads: who is better known, for near ties. */
        val popularity: Long = 0,
        /** Songs in a release or by an artist, when the catalogue says (0: not known). */
        val count: Int = 0,
        /** A verified artist (the catalogue confirmed it is them). */
        val verified: Boolean = false,
        /** "album", "playlist" or "" (a release of the Internet Archive). */
        val releaseType: String = "",
        /** Size of the file to download in bytes, when the catalogue says (0: not known). */
        val sizeBytes: Long = 0,
    ) {
        /** An artist, opened like a release. */
        val isArtist: Boolean get() = id.startsWith(ARTIST_PREFIX)
    }

    /** What to look for. */
    enum class Kind { ALL, ARTISTS, ALBUMS, SONGS }

    /** What a search found, each list best first. Songs include the 30-second excerpts (marked [Item.preview]). */
    data class Results(val artists: List<Item>, val albums: List<Item>, val songs: List<Item>) {
        val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
        fun all(): List<Item> = songs + albums + artists
    }

    /** An artist as something to open, like a release: [tracksOf] lists all their songs. */
    fun artist(of: Item): Item? {
        if (!of.artistId.startsWith("audius:")) return null
        return Item(
            id = ARTIST_PREFIX + of.artistId,
            title = of.artist,
            artist = of.artist,
            album = "",
            durationSec = 0,
            source = of.source,
            streamUrl = "",
            downloadUrl = null,
            coverUrl = null,
            license = "",
            extension = "",
            isRelease = true,
            artistId = of.artistId,
        )
    }

    /** Everything found for [query], as one list (songs, then releases, then artists). */
    fun search(query: String): List<Item> = find(query, Kind.ALL).all()

    /**
     * Searches the catalogues for [kind]. The query may be several parts in any order
     * ("artist, album, song"): the catalogues are asked the whole query and each part on its own,
     * and everything found is ordered by [OnlineMatch] — the best match first, not only exact ones.
     */
    fun find(query: String, kind: Kind = Kind.ALL): Results {
        val parts = OnlineMatch.parts(query)
        if (parts.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        val whole = parts.joinToString(" ")
        // special signs in a name often make a search find nothing: the plain form is asked as well
        val plain = app.malachite.core.net.QueryVariants.plain(whole)
        // a catalogue keeps a band under one spelling: "молчат дома" finds Molchat Doma only when asked in Latin too
        val other = app.malachite.core.text.Translit.alternateScript(whole)
        val asks = (listOfNotNull(whole, other) + (if (parts.size > 1) parts else emptyList()) + listOf(plain))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(4)
        val songs = kind == Kind.ALL || kind == Kind.SONGS
        val albums = kind == Kind.ALL || kind == Kind.ALBUMS
        val artists = kind == Kind.ALL || kind == Kind.ARTISTS
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val jobs = ArrayList<java.util.concurrent.Future<List<Item>>>()
            fun ask(name: String, f: () -> List<Item>) {
                jobs += pool.submit<List<Item>> { runCatching(f).onFailure { CoreLog.w("online", "$name: ${it.message}") }.getOrDefault(emptyList()) }
            }
            asks.forEachIndexed { i, a ->
                if (songs) { ask("Audius") { audius(a) }; ask("Deezer") { deezer(a) } }
                if (artists && i < 2) { ask("Audius, исполнители") { audiusArtists(a) }; ask("Deezer, исполнители") { deezerArtists(a) } }
                if (albums && i < 2) { ask("Audius, альбомы") { audiusAlbums(a) }; ask("Deezer, альбомы") { deezerAlbums(a) } }
                if ((songs || albums) && i < 2) ask("Internet Archive") { archive(a) }
            }
            val found = jobs.flatMap { runCatching { it.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }.distinctBy { it.id }
            val releases = found.filter { it.isRelease && !it.isArtist }
            var tracks = found.filter { !it.isRelease }
            // A release found by the name of a song is of little use until somebody opens it:
            // the songs of the best releases that answer the query are listed right away.
            if (songs) {
                val archived = releases.filter { it.id.startsWith("ia:") }
                runCatching { tracks = tracks + archiveSongs(query, rankReleases(query, archived)) }
                    .onFailure { CoreLog.w("online", "Internet Archive, песни: ${it.message}") }
            }
            // releases already opened for their songs show their real cover
            val withCovers = releases.map { r -> if (r.coverUrl == null) r.copy(coverUrl = releaseTracks[r.id]?.firstNotNullOfOrNull { it.coverUrl }) else r }
            val out = Results(
                artists = if (artists) rankArtists(query, found.filter { it.isArtist }) else emptyList(),
                albums = if (albums) rankReleases(query, withCovers) else emptyList(),
                songs = if (songs) rankSongs(query, tracks.distinctBy { it.id }) else emptyList(),
            )
            CoreLog.i("online", "«$query» ($kind): исполнителей ${out.artists.size}, альбомов ${out.albums.size}, песен ${out.songs.count { !it.preview }} целиком и ${out.songs.count { it.preview }} отрывков")
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rankSongs(query: String, items: List<Item>): List<Item> =
        OnlineMatch.rank(query, items, { OnlineMatch.Candidate(it.artist, it.title, it.album) }, { it.popularity })

    private fun rankReleases(query: String, items: List<Item>): List<Item> =
        OnlineMatch.rank(query, items, { OnlineMatch.Candidate(it.artist, it.title, it.title) }, { it.popularity }, nearBest = NEAR_BEST)

    private fun rankArtists(query: String, items: List<Item>): List<Item> =
        OnlineMatch.rank(query, items, { OnlineMatch.Candidate(it.title, "") }, { it.popularity + if (it.verified) 1_000_000_000L else 0L }, nearBest = NEAR_BEST)

    /** The songs of the first releases ([releases], best first) that answer the query. */
    private fun archiveSongs(q: String, releases: List<Item>): List<Item> {
        if (releases.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = releases.take(EXPAND_RELEASES).map { r ->
                pool.submit<List<Item>> { runCatching { tracksOf(r) }.getOrDefault(emptyList()) }
            }
            val out = ArrayList<Item>()
            val seen = HashSet<String>()
            for (job in jobs) {
                val tracks = runCatching { job.get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                var taken = 0
                for (t in OnlineMatch.rank(q, tracks, { OnlineMatch.Candidate(it.artist, it.title, it.album) })) {
                    if (taken >= SONGS_PER_RELEASE || out.size >= SONGS_FROM_RELEASES) break
                    if (OnlineMatch.score(q, OnlineMatch.Candidate(t.artist, t.title, t.album)) < OnlineMatch.ENOUGH) continue
                    // the same song is often uploaded more than once
                    if (!seen.add(OnlineMatch.words(t.artist + " " + t.title).joinToString(" ") + "|" + t.durationSec / 3)) continue
                    out += t
                    taken++
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ Deezer (30-second excerpts)

    private fun deezer(q: String): List<Item> {
        val json = http.getJson("https://api.deezer.com/search?q=${Http.enc(q)}&limit=25") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { deezerItem(it, null) }
    }

    /** A Deezer track (only its 30-second excerpt); [release] gives the album when the track itself does not. */
    private fun deezerItem(t: kotlinx.serialization.json.JsonElement, release: Item?): Item? {
        val id = t["id"].str() ?: return null
        val excerpt = t["preview"].str()?.takeIf { it.startsWith("http") } ?: return null
        val album = t["album"]
        return Item(
            id = "deezer:$id",
            title = t["title"].str().orEmpty(),
            artist = t["artist"]["name"].str().orEmpty(),
            album = album["title"].str() ?: release?.title.orEmpty(),
            durationSec = 30,
            source = "Deezer",
            streamUrl = excerpt,
            downloadUrl = null,
            coverUrl = album["cover_xl"].str() ?: album["cover_big"].str() ?: album["cover_medium"].str() ?: release?.coverUrl,
            license = "",
            extension = "mp3",
            pageUrl = t["link"].str().orEmpty(),
            preview = true,
            popularity = t["rank"].num()?.toLong() ?: 0,
        )
    }

    private fun deezerArtists(q: String): List<Item> {
        val json = http.getJson("https://api.deezer.com/search/artist?q=${Http.enc(q)}&limit=10") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { a ->
            val id = a["id"].str() ?: return@mapNotNull null
            val name = a["name"].str().orEmpty()
            Item(
                id = ARTIST_PREFIX + "deezer:$id", title = name, artist = name, album = "", durationSec = 0,
                source = "Deezer", streamUrl = "", downloadUrl = null,
                coverUrl = a["picture_xl"].str() ?: a["picture_big"].str() ?: a["picture_medium"].str(),
                license = "", extension = "", isRelease = true, pageUrl = a["link"].str().orEmpty(),
                popularity = a["nb_fan"].num()?.toLong() ?: 0,
            )
        }
    }

    private fun deezerAlbums(q: String): List<Item> {
        val json = http.getJson("https://api.deezer.com/search/album?q=${Http.enc(q)}&limit=12") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { a ->
            val id = a["id"].str() ?: return@mapNotNull null
            Item(
                id = "deezer-album:$id", title = a["title"].str().orEmpty(), artist = a["artist"]["name"].str().orEmpty(),
                album = a["title"].str().orEmpty(), durationSec = 0, source = "Deezer", streamUrl = "", downloadUrl = null,
                coverUrl = a["cover_xl"].str() ?: a["cover_big"].str() ?: a["cover_medium"].str(),
                license = "", extension = "", isRelease = true, pageUrl = a["link"].str().orEmpty(),
                count = a["nb_tracks"].num()?.toInt() ?: 0, releaseType = "album",
            )
        }
    }

    // ------------------------------------------------------------------ Audius

    @Volatile private var audiusHost: String? = null

    private fun audiusHost(): String {
        audiusHost?.let { return it }
        val host = runCatching {
            http.getJson("https://api.audius.co")["data"].arr()?.firstNotNullOfOrNull { it.str() }
        }.getOrNull()?.trimEnd('/') ?: "https://discoveryprovider.audius.co"
        audiusHost = host
        return host
    }

    private fun audius(q: String): List<Item> {
        val host = audiusHost()
        val json = http.getJson("$host/v1/tracks/search?query=${Http.enc(q)}&limit=40&app_name=$APP") ?: return emptyList()
        return json["data"].arr().orEmpty().take(40).mapNotNull { audiusItem(it, host) }
    }

    private fun audiusArtists(q: String): List<Item> {
        val host = audiusHost()
        val json = http.getJson("$host/v1/users/search?query=${Http.enc(q)}&limit=10&app_name=$APP") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { u ->
            val id = u["id"].str() ?: return@mapNotNull null
            val name = u["name"].str().orEmpty()
            val tracks = u["track_count"].num()?.toInt() ?: 0
            // a listener's account without a single song of its own is not an artist
            if (tracks <= 0) return@mapNotNull null
            val pic = u["profile_picture"]
            Item(
                id = ARTIST_PREFIX + "audius:$id", title = name, artist = name, album = "", durationSec = 0,
                source = "Audius", streamUrl = "", downloadUrl = null,
                coverUrl = pic["480x480"].str() ?: pic["1000x1000"].str() ?: pic["150x150"].str(),
                license = "", extension = "", isRelease = true,
                pageUrl = u["handle"].str()?.let { "https://audius.co/$it" }.orEmpty(),
                artistId = "audius:$id",
                popularity = u["follower_count"].num()?.toLong() ?: 0,
                count = tracks,
                verified = u["is_verified"]?.toString() == "true",
            )
        }
    }

    private fun audiusAlbums(q: String): List<Item> {
        val host = audiusHost()
        val json = http.getJson("$host/v1/playlists/search?query=${Http.enc(q)}&limit=12&app_name=$APP") ?: return emptyList()
        return json["data"].arr().orEmpty().mapNotNull { p ->
            val id = p["id"].str() ?: return@mapNotNull null
            val art = p["artwork"]
            val count = p["track_count"].num()?.toInt() ?: p["playlist_contents"]["track_ids"].arr()?.size ?: 0
            if (count == 0 && p["track_count"] != null) return@mapNotNull null
            Item(
                id = "audius-pl:$id", title = p["playlist_name"].str().orEmpty(), artist = p["user"]["name"].str().orEmpty(),
                album = p["playlist_name"].str().orEmpty(), durationSec = 0, source = "Audius", streamUrl = "", downloadUrl = null,
                coverUrl = art["1000x1000"].str() ?: art["480x480"].str() ?: art["150x150"].str(),
                license = "", extension = "", isRelease = true,
                pageUrl = p["permalink"].str()?.let { "https://audius.co$it" }.orEmpty(),
                artistId = p["user"]["id"].str()?.let { "audius:$it" }.orEmpty(),
                popularity = (p["favorite_count"].num()?.toLong() ?: 0) + (p["repost_count"].num()?.toLong() ?: 0),
                count = count,
                releaseType = if (p["is_album"]?.toString() == "true") "album" else "playlist",
            )
        }
    }

    /** All songs an Audius artist has put out (up to [ARTIST_MAX]), newest first. */
    private fun audiusArtist(userId: String): List<Item> {
        val host = audiusHost()
        val out = ArrayList<Item>()
        var offset = 0
        while (out.size < ARTIST_MAX) {
            val json = http.getJson("$host/v1/users/${Http.enc(userId)}/tracks?limit=100&offset=$offset&app_name=$APP") ?: break
            val page = json["data"].arr().orEmpty()
            out += page.mapNotNull { audiusItem(it, host) }
            if (page.size < 100) break
            offset += 100
        }
        return out.take(ARTIST_MAX)
    }

    private fun audiusItem(t: kotlinx.serialization.json.JsonElement, host: String): Item? {
        val id = t["id"].str() ?: return null
        if (t["is_streamable"]?.toString() == "false" || t["is_delete"]?.toString() == "true") return null
        val stream = "$host/v1/tracks/$id/stream?app_name=$APP"
        val downloadable = t["is_downloadable"]?.toString() == "true" || t["downloadable"]?.toString() == "true"
        val art = t["artwork"]
        return Item(
            id = "audius:$id",
            title = t["title"].str().orEmpty(),
            artist = t["user"]["name"].str().orEmpty(),
            album = "",
            durationSec = t["duration"].num()?.toInt() ?: 0,
            source = "Audius",
            streamUrl = stream,
            downloadUrl = if (downloadable) stream else null,
            coverUrl = art["1000x1000"].str() ?: art["480x480"].str() ?: art["150x150"].str(),
            license = t["license"].str().orEmpty(),
            extension = "mp3",
            pageUrl = t["permalink"].str()?.let { "https://audius.co$it" }.orEmpty(),
            artistId = t["user"]["id"].str()?.let { "audius:$it" }.orEmpty(),
            popularity = t["play_count"].num()?.toLong() ?: 0,
            verified = t["user"]["is_verified"]?.toString() == "true",
        )
    }

    // ------------------------------------------------------------------ Internet Archive

    private fun archive(q: String): List<Item> {
        val words = q.map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim().ifEmpty { return emptyList() }
        // First by title and author. If that finds little, also through the descriptions (the list
        // of songs of a release is usually there) – without the collections of books, radio and talks.
        val exact = archiveQuery("(title:($words) OR creator:($words)) AND mediatype:(audio)")
        if (exact.size >= ARCHIVE_ENOUGH) return exact
        val wide = runCatching {
            archiveQuery(
                "($words) AND mediatype:(audio) AND NOT collection:(librivoxaudio OR audio_bookspoetry OR podcasts OR " +
                    "radioprograms OR audio_religion OR audio_news OR audio_tech OR oldtimeradio)",
            )
        }.getOrDefault(emptyList())
        return (exact + wide).distinctBy { it.id }
    }

    private fun archiveQuery(lucene: String): List<Item> {
        val url = "https://archive.org/advancedsearch.php?q=${Http.enc(lucene)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year&fl%5B%5D=licenseurl&fl%5B%5D=downloads" +
            "&rows=30&page=1&output=json&sort%5B%5D=downloads+desc"
        val json = http.getJson(url) ?: return emptyList()
        return json["response"]["docs"].arr().orEmpty().mapNotNull { d ->
            val id = d["identifier"].str() ?: return@mapNotNull null
            Item(
                id = "ia:$id",
                title = text(d["title"]).ifBlank { id },
                artist = text(d["creator"]),
                album = text(d["title"]),
                durationSec = 0,
                source = "Internet Archive",
                streamUrl = "",
                downloadUrl = null,
                // the archive's own tile is a drawing of the sound when nobody uploaded a cover:
                // the real cover is read when the release is opened
                coverUrl = null,
                license = licenseName(d["licenseurl"].str()),
                extension = "",
                isRelease = true,
                pageUrl = "https://archive.org/details/$id",
                popularity = d["downloads"].num()?.toLong() ?: 0,
            )
        }
    }

    /** What was already read from the Internet Archive: opening a release after a search is instant. */
    private val releaseTracks = java.util.concurrent.ConcurrentHashMap<String, List<Item>>()

    /** The songs of an Internet Archive release: one entry per song, in the best format it has. */
    fun tracksOf(release: Item): List<Item> {
        releaseTracks[release.id]?.let { return it }
        if (releaseTracks.size > 60) releaseTracks.clear()
        return readTracks(release).also { if (it.isNotEmpty()) releaseTracks[release.id] = it }
    }

    /**
     * The songs of [release] in its own catalogue, and for an artist or a Deezer album also the
     * whole songs other catalogues have of them: Deezer only plays 30-second excerpts, so an
     * artist found there would otherwise have nothing to listen to in full or to download.
     */
    private fun readTracks(release: Item): List<Item> {
        val own = readOwnTracks(release)
        val whole = runCatching {
            when {
                release.isArtist -> wholeByArtist(release.title)
                release.id.startsWith("deezer-album:") -> wholeAlbum(release)
                else -> emptyList()
            }
        }.onFailure { CoreLog.w("online", "целиком для «${release.title}»: ${it.message}") }.getOrDefault(emptyList())
        if (whole.isEmpty()) return own
        // whole songs first; the excerpt of a song that is there in full is not needed
        val have = whole.map { songKey(it) }.toSet()
        return whole + own.filter { !(it.preview && songKey(it) in have) }
    }

    private fun songKey(t: Item): String = OnlineMatch.words(Watermarks.strip(t.title)).joinToString(" ")

    /** Is [artist] (as a catalogue writes it) the artist called [name]? Also "Молчат Дома [Molchat Doma]", "Ploho & Молчат Дома". */
    private fun sameArtist(name: String, artist: String): Boolean =
        artist.isNotBlank() && OnlineMatch.score(name, OnlineMatch.Candidate(artist, "")) >= SAME_ARTIST &&
            // the whole name, not a word of a longer one: "Mike's Kino World" is not «Кино»
            OnlineMatch.sameName(name, artist)

    /** Whole songs of the artist [name]: from the releases of the Internet Archive and from Audius. */
    private fun wholeByArtist(name: String): List<Item> {
        if (OnlineMatch.words(name).isEmpty()) return emptyList()
        val names = listOfNotNull(name, app.malachite.core.text.Translit.alternateScript(name)).distinct()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val asks = names.flatMap { n ->
                listOf(
                    pool.submit<List<Item>> { runCatching { archive(n) }.getOrDefault(emptyList()) },
                    pool.submit<List<Item>> { runCatching { audius(n) }.getOrDefault(emptyList()) },
                )
            }
            val found = asks.flatMap { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }.distinctBy { it.id }
            // releases by this artist (or without an author but named after it)
            val releases = found.filter { r ->
                r.isRelease && (sameArtist(name, r.artist) || (r.artist.isBlank() && OnlineMatch.sameName(name, r.title)))
            }.sortedByDescending { it.popularity }.take(EXPAND_ARTIST)
            val opened = releases.map { r -> r to pool.submit<List<Item>> { runCatching { tracksOf(r) }.getOrDefault(emptyList()) } }
            val archived = opened.flatMap { (r, job) ->
                runCatching { job.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                    .filter { t -> sameArtist(name, t.artist) || t.artist.isBlank() || t.artist == r.artist }
                    .map { t -> if (t.artist.isBlank()) t.copy(artist = name) else t }
            }
            val uploaded = found.filter { !it.isRelease && sameArtist(name, it.artist) }
            val seen = HashSet<String>()
            return (archived + uploaded).filter { seen.add(songKey(it) + "|" + it.durationSec / 5) }.take(ARTIST_MAX)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Whole songs of a Deezer album: the same album in the Internet Archive, when it is there. */
    private fun wholeAlbum(album: Item): List<Item> {
        val q = "${album.artist} ${album.title}".trim()
        val asks = listOfNotNull(q, app.malachite.core.text.Translit.alternateScript(q)).distinct()
        val found = asks.flatMap { runCatching { archive(it) }.getOrDefault(emptyList()) }.distinctBy { it.id }
        val best = found.map { it to OnlineMatch.score(q, OnlineMatch.Candidate(it.artist, it.title)) }
            .filter { it.second >= 1.0 }.maxByOrNull { it.second }?.first ?: return emptyList()
        return tracksOf(best).filter { !it.preview }
    }

    private fun readOwnTracks(release: Item): List<Item> {
        if (release.id.startsWith(ARTIST_PREFIX + "audius:")) return audiusArtist(release.id.removePrefix(ARTIST_PREFIX + "audius:"))
        if (release.id.startsWith(ARTIST_PREFIX + "deezer:")) {
            val id = release.id.removePrefix(ARTIST_PREFIX + "deezer:")
            val json = http.getJson("https://api.deezer.com/artist/${Http.enc(id)}/top?limit=50") ?: return emptyList()
            return json["data"].arr().orEmpty().mapNotNull { deezerItem(it, null) }
        }
        if (release.id.startsWith("deezer-album:")) {
            val id = release.id.removePrefix("deezer-album:")
            val json = http.getJson("https://api.deezer.com/album/${Http.enc(id)}/tracks?limit=100") ?: return emptyList()
            return json["data"].arr().orEmpty().mapNotNull { deezerItem(it, release) }
        }
        if (release.id.startsWith("audius-pl:")) {
            val host = audiusHost()
            val id = release.id.removePrefix("audius-pl:")
            val json = http.getJson("$host/v1/playlists/${Http.enc(id)}/tracks?app_name=$APP") ?: return emptyList()
            return json["data"].arr().orEmpty().mapNotNull { audiusItem(it, host) }
        }
        val id = release.id.removePrefix("ia:")
        val json = http.getJson("https://archive.org/metadata/$id") ?: return emptyList()
        val meta = json["metadata"]
        val album = text(meta["title"]).ifBlank { release.title }
        val albumArtist = text(meta["creator"]).ifBlank { release.artist }
        val license = licenseName(meta["licenseurl"].str()).ifBlank { release.license }
        val files = json["files"].arr().orEmpty()
        val covers = archiveCovers(id, files)
        // the same song is usually there in several formats: keep the best one per song
        val best = LinkedHashMap<String, Pair<Int, Item>>()
        for (f in files) {
            val name = f["name"].str() ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            val rank = FORMAT_RANK[ext] ?: continue
            val base = name.substringBeforeLast('.')
            val address = "https://archive.org/download/$id/" + name.split('/').joinToString("/") { Http.encPath(it) }
            val item = Item(
                id = "ia:$id/$name",
                title = f["title"].str()?.takeIf { it.isNotBlank() } ?: base.substringAfterLast('/'),
                artist = (f["creator"].str() ?: f["artist"].str())?.takeIf { it.isNotBlank() } ?: albumArtist,
                album = f["album"].str()?.takeIf { it.isNotBlank() } ?: album,
                durationSec = seconds(f["length"].str()),
                source = "Internet Archive",
                streamUrl = address,
                downloadUrl = address,
                coverUrl = covers(name),
                license = license,
                extension = ext,
                pageUrl = release.pageUrl,
                sizeBytes = f["size"].str()?.toLongOrNull() ?: 0,
            )
            // derivative files are named after the original: "song.flac" and "song.mp3" are one song
            val key = base.removeSuffix("_vbr").removeSuffix("_64kb").removeSuffix("_128kb")
            val prev = best[key]
            if (prev == null || prev.first < rank) best[key] = rank to item
        }
        return best.values.map { it.second }
    }

    /**
     * The real cover of an Internet Archive release: a picture somebody uploaded with it. An entry
     * without one gets a picture the archive draws itself from the sound (white bars of a
     * spectrogram); that is not a cover and is neither shown nor written into downloaded files.
     */
    private fun archiveCovers(id: String, files: List<kotlinx.serialization.json.JsonElement>): (String) -> String? {
        val pictures = files.mapNotNull { f ->
            val name = f["name"].str().orEmpty()
            val format = f["format"].str().orEmpty()
            name.takeIf {
                f["source"].str() == "original" && !name.startsWith("__ia_thumb") &&
                    (format == "JPEG" || format == "PNG" || name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") })
            }
        }
        val songs = files.mapNotNull { f -> f["name"].str()?.takeIf { FORMAT_RANK.containsKey(it.substringAfterLast('.', "").lowercase()) } }
        fun url(name: String) = "https://archive.org/download/$id/" + name.split('/').joinToString("/") { Http.encPath(it) }
        val pick = ArchiveCovers.picker(pictures, songs) { COVER_NAME.containsMatchIn(it) }
        val cache = HashMap<String, String?>()
        return { song -> cache.getOrPut(song) { pick(song)?.let(::url) } }
    }

    private fun text(el: kotlinx.serialization.json.JsonElement?): String =
        el.str() ?: el.arr()?.mapNotNull { it.str() }?.joinToString(", ").orEmpty()

    private fun seconds(length: String?): Int {
        val s = length?.trim().orEmpty()
        if (s.isEmpty()) return 0
        s.toDoubleOrNull()?.let { return it.toInt() }
        val parts = s.split(':').mapNotNull { it.toIntOrNull() }
        return if (parts.isEmpty()) 0 else parts.fold(0) { acc, p -> acc * 60 + p }
    }

    companion object {
        private const val APP = "Malachite"

        /** The id of an artist opened like a release: "artist:audius:<id>". */
        const val ARTIST_PREFIX = "artist:"

        /** A file name that says it is the cover. */
        private val COVER_NAME = Regex("(?i)(cover|front|folder|album|artwork)")

        /** How sure "this is the same artist" must be ([OnlineMatch.score] of the name against the artist). */
        private const val SAME_ARTIST = 1.2
        /** How many releases of an artist in the Internet Archive are opened for its page. */
        private const val EXPAND_ARTIST = 8

        /** Artists and albums answering much worse than the best one are not offered. */
        private const val NEAR_BEST = 0.6

        /** At most this many songs of one artist are listed. */
        private const val ARTIST_MAX = 300

        /** With this many releases found by title and author the wider search is not needed. */
        private const val ARCHIVE_ENOUGH = 10
        /** How many of the first releases are opened to list their songs in the results. */
        private const val EXPAND_RELEASES = 8
        private const val SONGS_PER_RELEASE = 6
        private const val SONGS_FROM_RELEASES = 40

        /** What to offer when a song is there in several formats (MP3 plays everywhere and is small). */
        private val FORMAT_RANK = mapOf("mp3" to 3, "ogg" to 2, "flac" to 1, "m4a" to 2, "opus" to 2, "wav" to 0)

        fun licenseName(url: String?): String {
            val u = url.orEmpty().lowercase()
            return when {
                u.isEmpty() -> ""
                "publicdomain" in u || "/zero/" in u -> tr("общественное достояние")
                "creativecommons.org/licenses/" in u ->
                    "CC " + u.substringAfter("licenses/").substringBefore('/').uppercase()
                else -> ""
            }
        }
    }
}
