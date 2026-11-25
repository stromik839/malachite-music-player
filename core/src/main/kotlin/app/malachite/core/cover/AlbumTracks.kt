package app.malachite.core.cover

import app.malachite.core.net.Http
import app.malachite.core.net.QueryVariants
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.num
import app.malachite.core.net.str

/**
 * The songs of an album as the catalogues list them, with their lengths (MusicBrainz first, it
 * knows much Russian music; Deezer when it knows nothing; no keys). Songs whose tags hold only their
 * number ("13") get their real title from the song at that number – when the lengths agree (see
 * [app.malachite.core.heal.HealPlanner.numberTitles]). [AlbumTracklist] is the quick Deezer-only
 * list used to number whole albums.
 */
class AlbumTracks(private val http: Http = Http()) {
    data class Track(val number: Int, val title: String, val durationMs: Long)

    data class Tracklist(val source: String, val album: String, val artist: String, val tracks: List<Track>)

    /** The lists found for [album] by [artist] ("Попс 1993" is also looked up as "Попс"). */
    fun find(artist: String, album: String): List<Tracklist> {
        if (artist.isBlank() || album.isBlank()) return emptyList()
        val names = listOf(album.trim(), YEAR_AT_END.replace(album, "").trim()).filter { it.isNotBlank() }.distinct()
        for (name in names) {
            val found = runCatching { musicBrainz(artist, name) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        for (name in names) {
            val found = runCatching { deezer(artist, name) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun musicBrainz(artist: String, album: String): List<Tracklist> {
        fun esc(s: String) = s.replace("\\", "").replace("\"", "")
        val query = Http.enc("release:\"${esc(album)}\" AND artist:\"${esc(artist)}\"")
        val json = http.getJson("https://musicbrainz.org/ws/2/release/?query=$query&fmt=json&limit=10") ?: return emptyList()
        val releases = json["releases"].arr().orEmpty().filter { r ->
            QueryVariants.similarity(r["title"].str(), album) >= MIN_TITLE &&
                QueryVariants.similarity(r["artist-credit"].arr()?.firstOrNull()?.get("name").str(), artist) >= MIN_ARTIST
        }.take(MAX_RELEASES)
        val out = ArrayList<Tracklist>()
        for (r in releases) {
            val id = r["id"].str() ?: continue
            // MusicBrainz asks for no more than one request a second
            Thread.sleep(MUSICBRAINZ_PAUSE_MS)
            val full = http.getJson("https://musicbrainz.org/ws/2/release/$id?inc=recordings&fmt=json") ?: continue
            // the first disc: a tag number is the number on its disc
            val tracks = full["media"].arr()?.firstOrNull()?.get("tracks").arr().orEmpty().mapNotNull { tr ->
                val n = tr["position"].num()?.toInt() ?: tr["number"].str()?.toIntOrNull() ?: return@mapNotNull null
                val title = tr["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(n, title, tr["length"].num()?.toLong() ?: tr["recording"]["length"].num()?.toLong() ?: 0L)
            }
            if (tracks.isNotEmpty()) out += Tracklist("MusicBrainz", r["title"].str().orEmpty(), r["artist-credit"].arr()?.firstOrNull()?.get("name").str().orEmpty(), tracks)
        }
        return out
    }

    private fun deezer(artist: String, album: String): List<Tracklist> {
        val query = Http.enc("artist:\"${artist.replace("\"", "")}\" album:\"${album.replace("\"", "")}\"")
        val json = http.getJson("https://api.deezer.com/search/album?q=$query&limit=10") ?: return emptyList()
        val albums = json["data"].arr().orEmpty().filter { a ->
            QueryVariants.similarity(a["title"].str(), album) >= MIN_TITLE && QueryVariants.similarity(a["artist"]["name"].str(), artist) >= MIN_ARTIST
        }.take(MAX_RELEASES)
        val out = ArrayList<Tracklist>()
        for (a in albums) {
            val id = a["id"].str() ?: continue
            val list = http.getJson("https://api.deezer.com/album/$id/tracks?limit=200") ?: continue
            val tracks = list["data"].arr().orEmpty().mapNotNull { tr ->
                if ((tr["disk_number"].num()?.toInt() ?: 1) != 1) return@mapNotNull null
                val n = tr["track_position"].num()?.toInt() ?: return@mapNotNull null
                val title = tr["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(n, title, ((tr["duration"].num() ?: 0.0) * 1000).toLong())
            }
            if (tracks.isNotEmpty()) out += Tracklist("Deezer", a["title"].str().orEmpty(), a["artist"]["name"].str().orEmpty(), tracks)
        }
        return out
    }

    private companion object {
        /** "Попс 1993", "Попс (1993)" → "Попс" */
        val YEAR_AT_END = Regex("\\s*[(\\[]?(?:19|20)\\d\\d[)\\]]?\\s*$")
        const val MIN_TITLE = 0.8
        const val MIN_ARTIST = 0.7
        const val MAX_RELEASES = 2
        const val MUSICBRAINZ_PAUSE_MS = 1100L
    }
}
