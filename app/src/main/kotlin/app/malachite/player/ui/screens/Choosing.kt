package app.malachite.player.ui.screens

import app.malachite.core.i18n.tr
import app.malachite.core.library.TrackInfo
import app.malachite.player.MalachiteApp
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.Navigator
import app.malachite.player.ui.components.MenuAction
import app.malachite.player.ui.components.Pick
import app.malachite.player.ui.components.Recent

/**
 * What can be done with several chosen songs at once (from the panel above the list and from a
 * long press on any chosen song). [done] ends the choice; it is called after playing, the rest
 * keeps the songs chosen (a dialog may still be cancelled).
 */
fun songsActions(nav: Navigator, tracks: List<TrackInfo>, done: () -> Unit, extra: List<MenuAction> = emptyList()): List<MenuAction> {
    if (tracks.isEmpty()) return emptyList()
    val g = MalachiteApp.graph
    val data = g.library.data.value
    val paths = tracks.map { it.path }
    val allFavorite = tracks.all { data.user(it.path).favorite }
    return listOf(
        MenuAction(tr("Слушать (${tracks.size})"), app.malachite.player.ui.components.ActionIcons.play) { g.player.play(tracks); done() },
        MenuAction(tr("Вперемешку"), app.malachite.player.ui.components.ActionIcons.shuffle) { g.player.play(tracks, shuffle = true); done() },
        MenuAction(tr("Играть следующими"), app.malachite.player.ui.components.ActionIcons.next) { g.player.playNext(tracks); done() },
        MenuAction(tr("Добавить в очередь"), app.malachite.player.ui.components.ActionIcons.queue) { g.player.addToQueue(tracks); done() },
        MenuAction(tr("В плейлист…"), app.malachite.player.ui.components.ActionIcons.playlist) { nav.show(DialogRequest.AddToPlaylist(paths)) },
        MenuAction(if (allFavorite) tr("Убрать из избранного") else tr("В избранное"), if (allFavorite) app.malachite.player.ui.components.ActionIcons.unfavorite else app.malachite.player.ui.components.ActionIcons.favorite) {
            g.library.setFavorite(paths, !allFavorite)
            nav.toast(if (allFavorite) tr("Убрано из избранного: ${paths.size}") else tr("В избранном: ${paths.size}"))
        },
        run {
            val allDisliked = tracks.all { data.user(it.path).disliked }
            MenuAction(if (allDisliked) tr("Снова нравятся") else tr("Не нравятся"), if (allDisliked) app.malachite.player.ui.components.ActionIcons.dislikeOff else app.malachite.player.ui.components.ActionIcons.dislike) {
                g.library.setDisliked(paths, !allDisliked)
                nav.toast(if (allDisliked) tr("Снова играют вперемешку: ${paths.size}") else tr("Не нравятся: ${paths.size} — не играют вперемешку и не попадают в умные плейлисты"))
            }
        },
        // an album of one's own: the same album name written into all chosen songs, the files move into its folder
        MenuAction(tr("Собрать в свой альбом…"), app.malachite.player.ui.components.ActionIcons.tags) { nav.show(DialogRequest.EditTagsMany(tracks)) },
        MenuAction(tr("Изменить теги у всех…"), app.malachite.player.ui.components.ActionIcons.tags) { nav.show(DialogRequest.EditTagsMany(tracks)) },
        MenuAction(tr("Исцелить выбранные…"), app.malachite.player.ui.components.ActionIcons.heal) { nav.show(DialogRequest.HealSelected(paths)) },
        MenuAction(tr("Одна обложка для всех…"), app.malachite.player.ui.components.ActionIcons.cover) { nav.show(DialogRequest.FindCover(tracks)) },
        MenuAction(tr("Сведения"), app.malachite.player.ui.components.ActionIcons.info) { nav.show(DialogRequest.InfoMany(tracks)) },
    ) + extra + MenuAction(tr("Удалить (в корзину)…"), app.malachite.player.ui.components.ActionIcons.delete, danger = true) { nav.show(DialogRequest.DeleteMany(tracks)) }
}

/** When a song came into the main folder (its import, or the file's date). */
private fun addedAt(t: TrackInfo): Long =
    MalachiteApp.graph.library.data.value.user(t.path).addedAt.takeIf { it > 0 } ?: t.lastModified

/** How long before the newest song the songs of one go still count as "the last addition". */
private const val ONE_GO_MS = 2 * Recent.HOUR

/** "No songs in the last hour; the newest came on 13 Nov at 23:31 (11 h ago)". */
private fun noneSince(what: String, songs: List<TrackInfo>): () -> String = {
    val newest = songs.maxOfOrNull { addedAt(it) }?.takeIf { it > 0 }
    tr("$what: новых песен нет") + (newest?.let { tr(". Последние появились ${Recent.describe(it)}") } ?: "")
}

/** Quick choices for a list of songs; [key] turns a song into the key of the list. */
fun songPicks(tracks: List<TrackInfo>, key: (Int, TrackInfo) -> String = { _, t -> t.path }): List<Pick> {
    fun pick(title: String, whenNone: (() -> String)? = null, test: (TrackInfo) -> Boolean) =
        Pick(title, whenNone) { tracks.mapIndexedNotNull { i, t -> if (test(t)) key(i, t) else null } }
    val data = { MalachiteApp.graph.library.data.value }
    return listOf(
        // the songs that came in the latest go (one download, one import), however long ago it was
        Pick(tr("Последнее пополнение")) {
            val newest = tracks.maxOfOrNull { addedAt(it) } ?: 0L
            if (newest <= 0) emptyList() else tracks.mapIndexedNotNull { i, t -> if (addedAt(t) >= newest - ONE_GO_MS) key(i, t) else null }
        },
        pick(tr("Появились за последний час"), noneSince(tr("За последний час"), tracks)) { addedAt(it) >= System.currentTimeMillis() - Recent.HOUR },
        pick(tr("За сутки"), noneSince(tr("За сутки"), tracks)) { addedAt(it) >= System.currentTimeMillis() - Recent.DAY },
        pick(tr("За неделю"), noneSince(tr("За неделю"), tracks)) { addedAt(it) >= System.currentTimeMillis() - Recent.WEEK },
        pick(tr("Без обложки")) { !it.hasArtwork },
        pick(tr("Без текста")) { val u = data().user(it.path); u.lyricsId == null && !u.noLyrics },
        pick(tr("Без альбома")) { it.album.isBlank() },
        pick(tr("Без исполнителя")) { it.artist.isBlank() && it.albumArtist.isBlank() },
        pick(tr("Избранные")) { data().user(it.path).favorite },
    )
}

/** Quick choices for groups of songs (albums, artists, playlists): by their newest song. */
fun <T> groupPicks(items: List<T>, key: (T) -> String, songs: (T) -> List<TrackInfo>): List<Pick> {
    val all = { items.flatMap(songs) }
    fun pick(title: String, whenNone: (() -> String)? = null, test: (List<TrackInfo>) -> Boolean) =
        Pick(title, whenNone) { items.filter { test(songs(it)) }.map(key) }
    return listOf(
        Pick(tr("Последнее пополнение")) {
            val newest = all().maxOfOrNull { addedAt(it) } ?: 0L
            if (newest <= 0) emptyList() else items.filter { songs(it).any { t -> addedAt(t) >= newest - ONE_GO_MS } }.map(key)
        },
        pick(tr("С песнями за последний час"), { noneSince(tr("За последний час"), all())() }) { ts -> ts.any { addedAt(it) >= System.currentTimeMillis() - Recent.HOUR } },
        pick(tr("За сутки"), { noneSince(tr("За сутки"), all())() }) { ts -> ts.any { addedAt(it) >= System.currentTimeMillis() - Recent.DAY } },
        pick(tr("За неделю"), { noneSince(tr("За неделю"), all())() }) { ts -> ts.any { addedAt(it) >= System.currentTimeMillis() - Recent.WEEK } },
        pick(tr("Без обложки")) { ts -> ts.isNotEmpty() && ts.none { it.hasArtwork } },
    )
}

/** "Choose by name" for songs: by artist (or album artist), album or title. */
fun songsByName(track: (String) -> TrackInfo?): app.malachite.player.ui.components.ByName = app.malachite.player.ui.components.ByName(
    listOf(
        tr("Исполнитель") to { k: String -> track(k)?.let { listOf(it.displayArtist, it.albumArtist) }.orEmpty() },
        tr("Альбом") to { k: String -> listOfNotNull(track(k)?.album) },
        tr("Название") to { k: String -> listOfNotNull(track(k)?.displayTitle) },
    ),
)

/**
 * "Choose by artist / album / year / format" for a list of songs (the songs of one artist in two
 * taps); [key] turns a song into the key of the list. Spellings of one name count as one.
 */
fun songGroups(tracks: List<TrackInfo>, key: (Int, TrackInfo) -> String = { _, t -> t.path }): List<app.malachite.player.ui.components.GroupChoice> {
    fun by(title: String, newestFirst: Boolean = false, label: (TrackInfo) -> String?) = app.malachite.player.ui.components.GroupChoice(title) {
        // one entry per name however it is written ("Кино", "КИНО"); shown as most songs write it
        val keys = LinkedHashMap<String, MutableList<String>>()
        val spellings = HashMap<String, HashMap<String, Int>>()
        tracks.forEachIndexed { i, t ->
            val name = label(t)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val fold = app.malachite.core.text.TextFold.strictKey(name).ifEmpty { name }
            keys.getOrPut(fold) { ArrayList() } += key(i, t)
            spellings.getOrPut(fold) { HashMap() }.merge(name, 1, Int::plus)
        }
        val list = keys.map { (fold, k) -> (spellings[fold]?.maxByOrNull { it.value }?.key ?: fold) to k.toList() }
        if (newestFirst) list.sortedByDescending { it.first } else list.sortedBy { app.malachite.core.text.TextFold.strictKey(it.first) }
    }
    return listOf(
        by(tr("Исполнитель")) { it.displayArtist },
        by(tr("Альбом")) { t -> t.album.takeIf { it.isNotBlank() }?.let { a -> if (t.groupArtist.isBlank()) a else "$a — ${t.groupArtist}" } },
        by(tr("Год"), newestFirst = true) { t -> app.malachite.core.library.LibraryLayout.reliableYear(t.year) },
        by(tr("Формат")) { t -> t.extension.uppercase() },
    )
}
