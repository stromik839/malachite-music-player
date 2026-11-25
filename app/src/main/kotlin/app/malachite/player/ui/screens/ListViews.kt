package app.malachite.player.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import app.malachite.core.library.LibraryData
import app.malachite.core.library.TrackInfo
import app.malachite.core.text.TextFold
import app.malachite.player.MalachiteApp
import app.malachite.player.data.ListKind
import app.malachite.player.data.ListView
import app.malachite.player.data.Narrow
import app.malachite.player.data.Order
import app.malachite.player.data.withView

/**
 * One row for every list: the order (a chip that opens the choice), "favourites first", and the
 * filters – the same chips doing the same thing in the songs, the favourites, the albums, the
 * artists and the playlists. [leading] goes first (the "choose" chip of the list).
 */
@Composable
fun ListViewBar(kind: ListKind, view: ListView, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}) {
    val g = MalachiteApp.graph
    fun set(v: ListView) = g.settings.update { it.withView(kind, v) }
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(kind.titleOf(view.order)) },
                leadingIcon = { Icon(Icons.Filled.Sort, tr("Сортировка"), Modifier.size(18.dp)) },
            )
            DropdownMenu(open, { open = false }) {
                kind.orders.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(kind.titleOf(o), fontWeight = if (o == view.order) FontWeight.Bold else FontWeight.Normal) },
                        leadingIcon = { if (o == view.order) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { open = false; set(view.copy(order = o)) },
                    )
                }
            }
        }
        if (kind.favoritesFirst) FilterChip(
            view.favoritesFirst, { set(view.copy(favoritesFirst = !view.favoritesFirst)) },
            label = { Text(tr("Сначала избранные")) },
            leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)) },
        )
        kind.narrows.forEach { f ->
            FilterChip(
                f in view.filters,
                onClick = {
                    // "with lyrics" and "without lyrics" cannot both be on
                    val opposite = when (f) { Narrow.WITH_LYRICS -> Narrow.NO_LYRICS; Narrow.NO_LYRICS -> Narrow.WITH_LYRICS; else -> null }
                    val now = if (f in view.filters) view.filters - f else (view.filters + f).let { if (opposite != null) it - opposite else it }
                    set(view.copy(filters = now))
                },
                label = { Text(f.title) },
            )
        }
    }
}

/**
 * What of the player's data [view] reads: a list is filtered and sorted again only when that
 * changed. After every song the data changes (its play count, the time): all the songs were
 * sorted again on the main thread – into a new list, so every row was composed anew.
 */
fun viewDataKey(data: LibraryData, view: ListView): Any {
    // each part is one element of the key (a map or a set as a whole, never spread into the list)
    val parts = ArrayList<Any>(5)
    if (view.order == Order.ADDED) parts.add(data.tracks.mapValues { it.value.addedAt })
    if (view.order == Order.PLAYS) parts.add(data.tracks.mapValues { it.value.playCount })
    if (view.order == Order.LAST_PLAYED) parts.add(data.tracks.mapValues { it.value.lastPlayed })
    if (view.favoritesFirst || Narrow.FAVORITE in view.filters) parts.add(data.tracks.filterValues { it.favorite }.keys)
    if (Narrow.NEVER_PLAYED in view.filters) parts.add(data.tracks.filterValues { it.playCount > 0 }.keys)
    if (Narrow.WITH_LYRICS in view.filters || Narrow.NO_LYRICS in view.filters) {
        parts.add(data.tracks.mapValues { (it.value.lyricsId != null) to it.value.noLyrics })
    }
    return parts
}

/** Names turned into sort keys once each (many songs share an artist or an album). */
private class Keys {
    private val known = HashMap<String, String>()
    operator fun get(s: String): String = known.getOrPut(s) { TextFold.strictKey(s) }
}

private val YEAR = Regex("\\d{4}")
private val years = HashMap<String, Int>()

/** The year of a tag as a number ("2003-05-01" → 2003); 0 when there is none. */
fun yearOf(tag: String): Int = if (tag.isBlank()) 0 else synchronized(years) {
    years.getOrPut(tag) { YEAR.find(tag)?.value?.toIntOrNull() ?: 0 }
}

/** When a song came into the main folder (its import, or the file's date). */
private fun added(t: TrackInfo, data: LibraryData): Long = data.user(t.path).addedAt.takeIf { it > 0 } ?: t.lastModified

/** Whether one song passes [f]. */
private fun passes(f: Narrow, t: TrackInfo, data: LibraryData): Boolean {
    val u = data.user(t.path)
    return when (f) {
        Narrow.FAVORITE -> u.favorite
        Narrow.PINNED -> true
        Narrow.WITH_LYRICS -> u.lyricsId != null
        Narrow.NO_LYRICS -> u.lyricsId == null && !u.noLyrics
        Narrow.NO_COVER -> !t.hasArtwork
        Narrow.NO_ALBUM -> t.album.isBlank()
        Narrow.NEVER_PLAYED -> u.playCount == 0
    }
}

/**
 * The songs as [view] wants them. [titleRank]: the place of each song in the library (which
 * keeps them by title) – null when [tracks] is in that order already, as the song tab is.
 */
fun viewSongs(tracks: List<TrackInfo>, view: ListView, data: LibraryData, titleRank: Map<String, Int>? = null): List<TrackInfo> {
    var list = if (view.filters.isEmpty()) tracks else tracks.filter { t -> view.filters.all { passes(it, t, data) } }
    val rank = { t: TrackInfo -> titleRank?.get(t.path) ?: 0 }
    val keys = Keys()
    list = when (view.order) {
        Order.TITLE -> if (titleRank == null) list else list.sortedBy(rank)
        Order.ARTIST -> list.sortedWith(compareBy<TrackInfo>({ keys[it.displayArtist] }, { keys[it.album] }, { it.disc }, { it.track }).thenBy(rank))
        Order.ALBUM -> list.sortedWith(compareBy<TrackInfo>({ keys[it.album] }, { it.disc }, { it.track }).thenBy(rank))
        // songs without a year go to the end either way
        Order.NEWEST -> list.sortedWith(compareBy<TrackInfo> { yearOf(it.year) == 0 }.thenByDescending { yearOf(it.year) }.thenBy(rank))
        Order.OLDEST -> list.sortedWith(compareBy<TrackInfo> { yearOf(it.year) == 0 }.thenBy { yearOf(it.year) }.thenBy(rank))
        Order.ADDED -> list.sortedByDescending { added(it, data) }
        Order.PLAYS -> list.sortedWith(compareByDescending<TrackInfo> { data.user(it.path).playCount }.thenBy(rank))
        Order.LAST_PLAYED -> list.sortedByDescending { data.user(it.path).lastPlayed }
        Order.DURATION -> list.sortedByDescending { it.durationMs }
        else -> list
    }
    // a stable sort: within the favourites and within the rest the order stays
    if (view.favoritesFirst) list = list.sortedBy { !data.user(it.path).favorite }
    return list
}

/** What a group of songs (an album, an artist, a playlist) is, worked out once for sorting and filtering. */
private class Facts(tracks: List<TrackInfo>, data: LibraryData) {
    var added = 0L
    var plays = 0
    var lastPlayed = 0L
    var duration = 0L
    var anyNoCover = false
    var allLyrics = tracks.isNotEmpty()
    var anyNoLyrics = false
    var anyNoAlbum = false
    val size = tracks.size

    init {
        for (t in tracks) {
            val u = data.user(t.path)
            added = maxOf(added, u.addedAt.takeIf { it > 0 } ?: t.lastModified)
            plays += u.playCount
            lastPlayed = maxOf(lastPlayed, u.lastPlayed)
            duration += t.durationMs
            if (!t.hasArtwork) anyNoCover = true
            if (u.lyricsId == null) allLyrics = false
            if (u.lyricsId == null && !u.noLyrics) anyNoLyrics = true
            if (t.album.isBlank()) anyNoAlbum = true
        }
    }

    fun passes(f: Narrow, favorite: Boolean, pinned: Boolean): Boolean = when (f) {
        Narrow.FAVORITE -> favorite
        Narrow.PINNED -> pinned
        Narrow.WITH_LYRICS -> allLyrics
        Narrow.NO_LYRICS -> anyNoLyrics
        Narrow.NO_COVER -> anyNoCover
        Narrow.NO_ALBUM -> anyNoAlbum
        Narrow.NEVER_PLAYED -> plays == 0
    }
}

/**
 * Albums, artists or playlists as [view] wants them: filtered by what their songs are, sorted
 * (keys worked out once per item, not at every comparison – that stalled the artist page),
 * favourites first when asked, the pinned ones always on top.
 */
fun <T> viewGroups(
    items: List<T>,
    view: ListView,
    data: LibraryData,
    title: (T) -> String,
    tracks: (T) -> List<TrackInfo>,
    favorite: (T) -> Boolean,
    pinned: (T) -> Boolean = { false },
    artist: (T) -> String = { "" },
    year: (T) -> Int = { 0 },
    albums: (T) -> Int = { 0 },
    created: (T) -> Long = { 0L },
    updated: (T) -> Long = { 0L },
    /** [items] are kept by title already (the library's albums and artists). */
    inTitleOrder: Boolean = false,
): List<T> {
    class Item(val item: T, val facts: Facts, val index: Int)
    var list = items.mapIndexed { i, t -> Item(t, Facts(tracks(t), data), i) }
    if (view.filters.isNotEmpty()) list = list.filter { x -> view.filters.all { f -> x.facts.passes(f, favorite(x.item), pinned(x.item)) } }
    val keys = Keys()
    val byTitle: Comparator<Item> = if (inTitleOrder) compareBy<Item> { it.index } else compareBy<Item> { keys[title(it.item)] }
    val comparator: Comparator<Item> = when (view.order) {
        Order.TITLE -> byTitle
        Order.ARTIST -> compareBy<Item>({ keys[artist(it.item)] }, { year(it.item) }).then(byTitle)
        Order.NEWEST -> compareBy<Item> { year(it.item) == 0 }.thenByDescending { year(it.item) }.then(byTitle)
        Order.OLDEST -> compareBy<Item> { year(it.item) == 0 }.thenBy { year(it.item) }.then(byTitle)
        Order.ADDED -> compareByDescending<Item> { it.facts.added }.then(byTitle)
        Order.PLAYS -> compareByDescending<Item> { it.facts.plays }.then(byTitle)
        Order.LAST_PLAYED -> compareByDescending<Item> { it.facts.lastPlayed }.then(byTitle)
        Order.SIZE -> compareByDescending<Item> { it.facts.size }.then(byTitle)
        Order.ALBUMS -> compareByDescending<Item> { albums(it.item) }.then(byTitle)
        Order.DURATION -> compareByDescending<Item> { it.facts.duration }.then(byTitle)
        Order.CREATED -> compareByDescending<Item> { created(it.item) }.then(byTitle)
        Order.UPDATED -> compareByDescending<Item> { updated(it.item) }.then(byTitle)
        Order.ALBUM -> byTitle
    }
    // pinned ones on top, then the favourites when asked, each part in the chosen order
    val sorted = list.sortedWith(
        compareBy<Item> { !pinned(it.item) }
            .thenBy { if (view.favoritesFirst) !favorite(it.item) else false }
            .then(comparator),
    )
    return sorted.map { it.item }
}

/** The place of each song in the library's own order (by title). */
fun titleRanks(library: List<TrackInfo>): Map<String, Int> {
    val out = HashMap<String, Int>(library.size * 2)
    library.forEachIndexed { i, t -> out[t.path] = i }
    return out
}

/** Shown when the filters leave nothing: the row of filters stays above it, so they can be undone. */
@Composable
fun NothingMatches(modifier: Modifier = Modifier) {
    Text(
        tr("Ничего не подходит под выбранные фильтры"),
        modifier.fillMaxWidth().padding(24.dp),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
