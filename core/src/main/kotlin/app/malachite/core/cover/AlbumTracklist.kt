package app.malachite.core.cover

import app.malachite.core.net.Http
import app.malachite.core.net.QueryVariants
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.num
import app.malachite.core.net.str

/** The list of songs of an album, from the Deezer catalogue: title -> track number. */
class AlbumTracklist(private val http: Http) {
    fun find(artist: String, album: String): List<Pair<String, Int>>? {
        val q = Http.enc("artist:\"$artist\" album:\"$album\"")
        val found = http.getJson("https://api.deezer.com/search/album?q=$q&limit=10")?.get("data").arr().orEmpty()
        // the album with the closest title by the same artist; nothing when unsure
        val best = found.maxByOrNull { a ->
            QueryVariants.similarity(album, a["title"].str()) + QueryVariants.similarity(artist, a["artist"]["name"].str())
        } ?: return null
        if (QueryVariants.similarity(album, best["title"].str()) < 0.8 || QueryVariants.similarity(artist, best["artist"]["name"].str()) < 0.7) return null
        val id = best["id"].str() ?: return null
        val tracks = http.getJson("https://api.deezer.com/album/$id/tracks?limit=200")?.get("data").arr().orEmpty()
        return tracks.mapNotNull { t ->
            val title = t["title"].str() ?: return@mapNotNull null
            val n = t["track_position"].num()?.toInt() ?: return@mapNotNull null
            title to n
        }.takeIf { it.isNotEmpty() }
    }
}
