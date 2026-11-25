package app.malachite.player.data

import app.malachite.core.i18n.tr
import kotlinx.serialization.Serializable

/**
 * The ways to order a list. Every list – songs, favourites, albums, artists, playlists, an
 * artist's songs and albums – offers those that make sense for it, always under the same names
 * and working the same way.
 */
@Serializable
enum class Order(val title: String) {
    TITLE(tr("По названию")),
    ARTIST(tr("По исполнителю")),
    ALBUM(tr("По альбому")),
    NEWEST(tr("По году: сначала новые")),
    OLDEST(tr("По году: сначала старые")),
    ADDED(tr("Недавно добавленные")),
    PLAYS(tr("Часто слушаю")),
    LAST_PLAYED(tr("Недавно слушал")),
    SIZE(tr("По числу песен")),
    ALBUMS(tr("По числу альбомов")),
    DURATION(tr("По длительности")),
    CREATED(tr("По дате создания")),
    UPDATED(tr("Недавно изменённые")),
}

/**
 * Ways to narrow a list; several can be on at once. For an album, an artist or a playlist they
 * speak of its songs: "without a cover" – one of its songs has none, "never played" – none of
 * them has been played yet, "with lyrics" – all of them have lyrics.
 */
@Serializable
enum class Narrow(val title: String) {
    FAVORITE(tr("Избранное")),
    PINNED(tr("Закреплённые")),
    WITH_LYRICS(tr("С текстом")),
    NO_LYRICS(tr("Без текста")),
    NO_COVER(tr("Без обложки")),
    NO_ALBUM(tr("Без альбома")),
    NEVER_PLAYED(tr("Ни разу не слушал")),
}

/** How one list is shown: its order, favourites on top or not, and the filters that are on. */
@Serializable
data class ListView(
    val order: Order = Order.TITLE,
    val favoritesFirst: Boolean = false,
    val filters: Set<Narrow> = emptySet(),
)

/** The lists that keep their own [ListView] (in the settings, so it stays after a restart). */
enum class ListKind(val key: String, val orders: List<Order>, val narrows: List<Narrow>, val favoritesFirst: Boolean = true) {
    SONGS(
        "songs",
        listOf(Order.TITLE, Order.ARTIST, Order.ALBUM, Order.NEWEST, Order.OLDEST, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NO_ALBUM, Narrow.NEVER_PLAYED),
    ),
    /** The songs found by the search of the library. */
    SEARCH(
        "search",
        listOf(Order.TITLE, Order.ARTIST, Order.ALBUM, Order.NEWEST, Order.OLDEST, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NO_ALBUM, Narrow.NEVER_PLAYED),
    ),
    /** The favourite songs: all of them are favourites, so no "favourites first" and no such filter. */
    FAVORITES(
        "favorites",
        listOf(Order.TITLE, Order.ARTIST, Order.ALBUM, Order.NEWEST, Order.OLDEST, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.DURATION),
        listOf(Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NO_ALBUM, Narrow.NEVER_PLAYED),
        favoritesFirst = false,
    ),
    ARTIST_SONGS(
        "artistSongs",
        listOf(Order.ALBUM, Order.TITLE, Order.NEWEST, Order.OLDEST, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NO_ALBUM, Narrow.NEVER_PLAYED),
    ),
    ALBUMS(
        "albums",
        listOf(Order.TITLE, Order.ARTIST, Order.NEWEST, Order.OLDEST, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.SIZE, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.PINNED, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NEVER_PLAYED),
    ),
    ARTIST_ALBUMS(
        "artistAlbums",
        listOf(Order.NEWEST, Order.OLDEST, Order.TITLE, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.SIZE, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.PINNED, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NEVER_PLAYED),
    ),
    ARTISTS(
        "artists",
        listOf(Order.TITLE, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.SIZE, Order.ALBUMS, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NO_ALBUM, Narrow.NEVER_PLAYED),
    ),
    PLAYLISTS(
        "playlists",
        listOf(Order.TITLE, Order.CREATED, Order.UPDATED, Order.ADDED, Order.PLAYS, Order.LAST_PLAYED, Order.SIZE, Order.DURATION),
        listOf(Narrow.FAVORITE, Narrow.PINNED, Narrow.WITH_LYRICS, Narrow.NO_LYRICS, Narrow.NO_COVER, Narrow.NEVER_PLAYED),
    );

    /** The name of [order] in this list ("by name" for people, "by title" for the rest). */
    fun titleOf(order: Order): String = if (this == ARTISTS && order == Order.TITLE) tr("По имени") else order.title
}

/**
 * How [kind] is shown now. A list nobody has set up yet starts from what its old single setting
 * said (the order of albums, of playlists, of artists, of an artist's albums), so an update
 * changes nothing that was chosen before.
 */
fun AppSettings.view(kind: ListKind): ListView = listViews[kind.key]?.let { v ->
    // an order this list does not offer (a setting from another version) falls back to its first one
    if (v.order in kind.orders) v.copy(filters = v.filters.filterTo(HashSet()) { it in kind.narrows })
    else v.copy(order = kind.orders.first(), filters = v.filters.filterTo(HashSet()) { it in kind.narrows })
} ?: when (kind) {
    ListKind.ALBUMS -> ListView(order = when (albumSort) {
        AlbumSort.TITLE -> Order.TITLE
        AlbumSort.ARTIST -> Order.ARTIST
        AlbumSort.YEAR -> Order.NEWEST
        AlbumSort.SIZE -> Order.SIZE
        AlbumSort.RECENT -> Order.ADDED
    })
    ListKind.PLAYLISTS -> ListView(order = when (playlistSort) {
        PlaylistSort.NAME -> Order.TITLE
        PlaylistSort.CREATED -> Order.CREATED
        PlaylistSort.UPDATED -> Order.UPDATED
        PlaylistSort.SIZE -> Order.SIZE
    })
    ListKind.ARTISTS -> ListView(order = when (artistSort) {
        ArtistSort.NAME -> Order.TITLE
        ArtistSort.SONGS -> Order.SIZE
        ArtistSort.ALBUMS -> Order.ALBUMS
        ArtistSort.PLAYS -> Order.PLAYS
        ArtistSort.LAST_PLAYED -> Order.LAST_PLAYED
        ArtistSort.ADDED -> Order.ADDED
    })
    ListKind.ARTIST_ALBUMS -> ListView(order = when (artistAlbumSort) {
        ArtistAlbumSort.NEWEST -> Order.NEWEST
        ArtistAlbumSort.OLDEST -> Order.OLDEST
        ArtistAlbumSort.TITLE -> Order.TITLE
        ArtistAlbumSort.SIZE -> Order.SIZE
    })
    ListKind.ARTIST_SONGS -> ListView(order = Order.ALBUM)
    else -> ListView()
}

/** The settings with [kind] shown as [v]. */
fun AppSettings.withView(kind: ListKind, v: ListView): AppSettings = copy(listViews = listViews + (kind.key to v))
