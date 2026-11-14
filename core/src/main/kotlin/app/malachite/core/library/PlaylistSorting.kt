package app.malachite.core.library

import java.text.Collator
import java.util.Locale

/** Ways to put the songs of a playlist in order (the new order is written into the playlist). */
enum class PlaylistOrder {
    /** Songs of one album together, albums by artist and year, songs by their track numbers. */
    ALBUMS,
    ARTISTS,
    TITLES,
    /** The oldest albums first. */
    YEARS,
    /** The songs that came into the main folder last first. */
    ADDED,
    LONGEST,
    REVERSE,
}

object PlaylistSorting {
    private val YEAR = Regex("\\d{4}")

    /** Names compared the way people read them: case and «ё» aside, Russian and Latin letters in their own order. */
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.PRIMARY }

    private fun yearOf(t: TrackInfo): Int = YEAR.find(t.year)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun names(f: (TrackInfo) -> String) = Comparator<TrackInfo> { a, b -> collator.compare(f(a).trim(), f(b).trim()) }

    /** Within an album: the disc, the track number (songs without one after), then the title. */
    private val inAlbum: Comparator<TrackInfo> =
        compareBy<TrackInfo> { it.disc }
            .thenBy { if (it.track > 0) it.track else Int.MAX_VALUE }
            .then(names { it.displayTitle })

    private val albums: Comparator<TrackInfo> =
        names { it.groupArtist }
            .thenBy { yearOf(it) }
            .then(names { it.album })
            .then(inAlbum)

    /**
     * [tracks] in the order [by]; songs that compare equal keep their places. [addedAt] tells when
     * a song came into the main folder.
     */
    fun sort(tracks: List<TrackInfo>, by: PlaylistOrder, addedAt: (TrackInfo) -> Long = { it.lastModified }): List<TrackInfo> = when (by) {
        PlaylistOrder.ALBUMS -> tracks.sortedWith(albums)
        PlaylistOrder.ARTISTS -> tracks.sortedWith(names { it.displayArtist }.then(albums))
        PlaylistOrder.TITLES -> tracks.sortedWith(names { it.displayTitle }.then(names { it.displayArtist }))
        PlaylistOrder.YEARS -> tracks.sortedWith(compareBy<TrackInfo> { yearOf(it) }.then(albums))
        PlaylistOrder.ADDED -> tracks.sortedByDescending { addedAt(it) }
        PlaylistOrder.LONGEST -> tracks.sortedByDescending { it.durationMs }
        PlaylistOrder.REVERSE -> tracks.reversed()
    }
}
