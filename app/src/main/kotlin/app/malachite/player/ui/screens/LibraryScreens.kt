package app.malachite.player.ui.screens

import app.malachite.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableIntStateOf
import app.malachite.player.ui.components.dragToReorder
import androidx.compose.foundation.gestures.scrollBy
import app.malachite.player.ui.components.dragToChoose
import app.malachite.player.ui.components.keyAt
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.unit.dp
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.TrackInfo
import app.malachite.core.text.TextFold
import app.malachite.player.MalachiteApp
import app.malachite.player.data.Album
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Navigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.SmartKind
import app.malachite.player.ui.components.CoverImage
import app.malachite.player.ui.components.EmptyState
import app.malachite.player.ui.components.MenuAction
import app.malachite.player.ui.components.SectionTitle
import app.malachite.player.ui.components.TrackRow
import app.malachite.player.ui.components.formatTime
import java.io.File

/** The context menu shown for every song everywhere in the app. */
fun trackActions(nav: Navigator, t: TrackInfo, queue: List<TrackInfo> = emptyList(), choose: (() -> Unit)? = null): List<MenuAction> {
    val g = MalachiteApp.graph
    val fav = g.library.data.value.user(t.path).favorite
    val albumKey = TextFold.strictKey(t.groupArtist) + "|" + TextFold.strictKey(t.album)
    return listOfNotNull(
        // several at once: the list's own choice starts with this song
        choose?.let { MenuAction(tr("Выбрать несколько"), app.malachite.player.ui.components.ActionIcons.choose) { it() } },
        MenuAction(tr("Играть следующей"), app.malachite.player.ui.components.ActionIcons.next) { g.player.playNext(t) },
        MenuAction(tr("Добавить в очередь"), app.malachite.player.ui.components.ActionIcons.queue) { g.player.addToQueue(listOf(t)) },
        MenuAction(tr("В плейлист…"), app.malachite.player.ui.components.ActionIcons.playlist) { nav.show(DialogRequest.AddToPlaylist(listOf(t.path))) },
        MenuAction(if (fav) tr("Убрать из избранного") else tr("В избранное"), if (fav) app.malachite.player.ui.components.ActionIcons.unfavorite else app.malachite.player.ui.components.ActionIcons.favorite) { g.library.toggleFavorite(t.path) },
        // not played in shuffle and left out of the smart playlists; the file stays
        if (g.library.isOnline(t.path)) null else {
            val disliked = g.library.data.value.user(t.path).disliked
            MenuAction(if (disliked) tr("Снова нравится") else tr("Не нравится"), if (disliked) app.malachite.player.ui.components.ActionIcons.dislikeOff else app.malachite.player.ui.components.ActionIcons.dislike) {
                g.library.setDisliked(listOf(t.path), !disliked)
                nav.toast(if (disliked) tr("Песня снова играет вперемешку") else tr("Не нравится: песня не играет вперемешку и не попадает в умные плейлисты"))
            }
        },
        MenuAction(tr("Найти текст"), app.malachite.player.ui.components.ActionIcons.lyrics) { nav.show(DialogRequest.FindLyrics(t)) },
        MenuAction(tr("Синхронизировать текст вручную"), app.malachite.player.ui.components.ActionIcons.sync) { nav.push(Screen.LyricsSync(t.path)) },
        MenuAction(if (t.hasArtwork) tr("Заменить обложку") else tr("Найти обложку"), app.malachite.player.ui.components.ActionIcons.cover) { nav.show(DialogRequest.FindCover(listOf(t))) },
        MenuAction(tr("Изменить теги"), app.malachite.player.ui.components.ActionIcons.tags) { nav.show(DialogRequest.EditTags(t)) },
        MenuAction(tr("К исполнителю"), app.malachite.player.ui.components.ActionIcons.artist) { nav.push(Screen.ArtistDetail(TextFold.strictKey(t.groupArtist))) },
        MenuAction(tr("К альбому"), app.malachite.player.ui.components.ActionIcons.album) { nav.push(Screen.AlbumDetail(albumKey)) },
        MenuAction(tr("Сведения о файле"), app.malachite.player.ui.components.ActionIcons.info) { nav.show(DialogRequest.Info(t)) },
        MenuAction(tr("Удалить (в корзину)"), app.malachite.player.ui.components.ActionIcons.delete, danger = true) { nav.show(DialogRequest.Delete(t)) },
    )
}

/**
 * A list of songs. A long press (or "choose several" in a song's menu, or the button next to
 * "listen") starts choosing: taps then choose, the panel on top does things with all of them.
 */
@Composable
fun TrackList(
    tracks: List<TrackInfo>,
    header: (@Composable () -> Unit)? = null,
    numbered: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    /** More things to do with the chosen songs, for this list only ("remove from the playlist"). */
    extraActions: (List<TrackInfo>) -> List<MenuAction> = { emptyList() },
) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val player by g.player.state.collectAsState()
    val data by g.library.data.collectAsState()
    val sel = app.malachite.player.ui.components.rememberSelection("songs")
    val keys = remember(tracks) { tracks.map { it.path } }
    androidx.compose.runtime.LaunchedEffect(keys) { sel.retain(keys) }
    val chosen = { tracks.filter { it.path in sel } }
    val batch = { songsActions(nav, chosen(), done = { sel.close() }, extra = extraActions(chosen())) }
    Column(Modifier.fillMaxSize()) {
        val byPath = remember(tracks) { tracks.associateBy { it.path } }
        if (sel.active) app.malachite.player.ui.components.SelectionBar(sel, keys, remember(tracks) { songPicks(tracks) }, remember(byPath) { songsByName { byPath[it] } }, batch)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var menuFor by remember { mutableStateOf<String?>(null) }
        app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
        androidx.compose.runtime.CompositionLocalProvider(app.malachite.player.ui.components.LocalListSelection provides sel) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().dragToChoose(sel, keys, { listState.keyAt(it) }, { listState.scrollBy(it) }) { menuFor = it },
                state = listState, contentPadding = contentPadding,
            ) {
                if (header != null) item { header() }
                itemsIndexed(tracks, key = { _, t -> t.path }) { i, t ->
                    val choosing = sel.active
                    TrackRow(
                        track = t,
                        playing = t.path == player.currentPath,
                        favorite = data.user(t.path).favorite,
                        onClick = if (choosing) ({ sel.toggle(t.path) }) else ({ g.player.play(tracks, i) }),
                        actions = if (choosing) emptyList() else trackActions(nav, t, tracks) { sel.start(t.path) },
                        number = if (numbered) t.track else null,
                        selected = if (choosing) t.path in sel else null,
                        onLongPressChoosing = { if (t.path !in sel) sel.toggle(t.path) },
                        menuActions = if (choosing) batch else null,
                    )
                }
            }
        }
    }
}

@Composable
fun PlayButtons(tracks: List<TrackInfo>, extra: (@Composable () -> Unit)? = null) {
    val g = MalachiteApp.graph
    // On a narrow screen (a phone with the tab panel at the side) the icons step aside, so the
    // words stay on one line ("Вперемешку" used to break into "Вперем / ешку").
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val narrow = maxWidth < 330.dp
        val pad = if (narrow) PaddingValues(horizontal = 10.dp) else ButtonDefaults.ContentPadding
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp)) {
            Button(onClick = { g.player.play(tracks, 0) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = pad) {
                if (!narrow) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)) }
                Text(tr("Слушать"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = { g.player.play(tracks, 0, shuffle = true) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = pad) {
                if (!narrow) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)) }
                Text(tr("Вперемешку"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            // choosing several songs of this list
            val sel = app.malachite.player.ui.components.LocalListSelection.current
            if (sel != null && !sel.active && tracks.size > 1) {
                IconButton(onClick = { sel.start() }) { Icon(androidx.compose.material.icons.Icons.Filled.CheckBox, tr("Выбрать несколько")) }
            }
            extra?.invoke()
        }
    }
}

enum class SongSort(val title: String) { TITLE(tr("По названию")), ARTIST(tr("По исполнителю")), ALBUM(tr("По альбому")), ADDED(tr("Недавно добавленные")), PLAYS(tr("Часто слушаю")), DURATION(tr("По длительности")) }

/** Narrowing a list of songs; several can be on at once. */
enum class SongFilter(val title: String) {
    FAVORITE(tr("Избранное")), WITH_LYRICS(tr("С текстом")), NO_LYRICS(tr("Без текста")), NO_COVER(tr("Без обложки")), NO_ALBUM(tr("Без альбома"));

    fun accepts(t: TrackInfo, u: app.malachite.core.library.TrackUserData): Boolean = when (this) {
        FAVORITE -> u.favorite
        WITH_LYRICS -> u.lyricsId != null
        NO_LYRICS -> u.lyricsId == null && !u.noLyrics
        NO_COVER -> !t.hasArtwork
        NO_ALBUM -> t.album.isBlank()
    }
}

fun sortSongs(tracks: List<TrackInfo>, sort: SongSort, data: app.malachite.core.library.LibraryData): List<TrackInfo> = when (sort) {
    SongSort.TITLE -> tracks
    SongSort.ARTIST -> tracks.sortedWith(compareBy({ TextFold.strictKey(it.displayArtist) }, { it.album }, { it.track }))
    SongSort.ALBUM -> tracks.sortedWith(compareBy({ TextFold.strictKey(it.album) }, { it.track }))
    SongSort.ADDED -> tracks.sortedByDescending { data.user(it.path).addedAt.takeIf { a -> a > 0 } ?: it.lastModified }
    SongSort.PLAYS -> tracks.sortedByDescending { data.user(it.path).playCount }
    SongSort.DURATION -> tracks.sortedByDescending { it.durationMs }
}

/** One scrollable row: a clearly labelled sort chip, then the filters. */
@Composable
fun SortAndFilterRow(sort: SongSort, onSort: (SongSort) -> Unit, filters: Set<SongFilter>, onFilters: (Set<SongFilter>) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            androidx.compose.material3.AssistChip(
                onClick = { sortMenu = true },
                label = { Text(sort.title) },
                leadingIcon = { Icon(Icons.Filled.Sort, tr("Сортировка"), Modifier.size(18.dp)) },
            )
            DropdownMenu(sortMenu, { sortMenu = false }) {
                SongSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(s.title, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) }, onClick = { onSort(s); sortMenu = false })
                }
            }
        }
        SongFilter.entries.forEach { f ->
            androidx.compose.material3.FilterChip(
                selected = f in filters,
                onClick = {
                    // "with lyrics" and "without lyrics" cannot both be on
                    val opposite = when (f) { SongFilter.WITH_LYRICS -> SongFilter.NO_LYRICS; SongFilter.NO_LYRICS -> SongFilter.WITH_LYRICS; else -> null }
                    onFilters(if (f in filters) filters - f else (filters + f).let { if (opposite != null) it - opposite else it })
                },
                label = { Text(f.title) },
            )
        }
    }
}

private enum class SearchScope(val title: String) { ALL(tr("Всё")), SONGS(tr("Песни")), ALBUMS(tr("Альбомы")), ARTISTS(tr("Исполнители")), PLAYLISTS(tr("Плейлисты")) }

@Composable
fun SongsScreen() {
    val g = MalachiteApp.graph
    val tracks by g.library.tracks.collectAsState()
    val scan by g.library.scan.collectAsState()
    val data by g.library.data.collectAsState()
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(emptySet<SongFilter>()) }
    val nav = LocalNavigator.current
    if (tracks.isEmpty() && !scan.running) {
        EmptyState(Icons.Filled.LibraryMusic, tr("Главная папка пуста"), tr("Песни появятся после импорта с устройства.")) {
            Button(onClick = { nav.push(Screen.ImportScreen) }) { Text(tr("Найти песни на устройстве")) }
        }
        return
    }
    val sorted = remember(tracks, sort, data, filters) {
        sortSongs(tracks.filter { t -> filters.all { f -> f.accepts(t, data.user(t.path)) } }, sort, data)
    }
    Column {
        if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        TrackList(sorted, header = {
            Column {
                PlayButtons(sorted)
                Text(
                    tr("${sorted.size} песен • ${formatTime(sorted.sumOf { it.durationMs })}") + if (filters.isNotEmpty()) tr(" (из ${tracks.size})") else "",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SortAndFilterRow(sort, { sort = it }, filters) { filters = it }
            }
        })
    }
}

@Composable
fun AlbumCard(a: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CoverImage(a.cover, Modifier.fillMaxSize(), size = 400.dp, corner = 14.dp, sizePx = 400)
        }
        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("${a.artist} • ${a.tracks.size}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ArtistsScreen() {
    val g = MalachiteApp.graph
    val artists by g.library.artists.collectAsState()
    val nav = LocalNavigator.current
    if (artists.isEmpty()) {
        EmptyState(Icons.Filled.Person, tr("Нет исполнителей"), tr("Добавьте песни в главную папку."))
        return
    }
    val sel = app.malachite.player.ui.components.rememberSelection("artists")
    val keys = remember(artists) { artists.map { it.key } }
    androidx.compose.runtime.LaunchedEffect(keys) { sel.retain(keys) }
    val batch = { songsActions(nav, artists.filter { it.key in sel }.flatMap { it.tracks }, done = { sel.close() }) }
    Column(Modifier.fillMaxSize()) {
        val byKey = remember(artists) { artists.associateBy { it.key } }
        if (sel.active) app.malachite.player.ui.components.SelectionBar(
            sel, keys, remember(artists) { groupPicks(artists, { it.key }, { it.tracks }) },
            remember(byKey) { app.malachite.player.ui.components.ByName(listOf(tr("Исполнитель") to { k: String -> listOfNotNull(byKey[k]?.name) })) }, batch,
        )
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var menuFor by remember { mutableStateOf<String?>(null) }
        app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().dragToChoose(sel, keys, { listState.keyAt(it) }, { listState.scrollBy(it) }) { menuFor = it },
            state = listState,
        ) {
            if (!sel.active && artists.size > 1) item {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    androidx.compose.material3.AssistChip(onClick = { sel.start() }, label = { Text(tr("Выбрать")) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.CheckBox, null, Modifier.size(18.dp)) })
                }
            }
            items(artists, key = { it.key }) { a ->
                ChoosableItem(
                    chosen = if (sel.active) a.key in sel else null,
                    onClick = { if (sel.active) sel.toggle(a.key) else nav.push(Screen.ArtistDetail(a.key)) },
                    onChoose = { sel.start(a.key) }, batch = batch,
                    ownMenu = {
                        listOf(
                            MenuAction(tr("Слушать"), app.malachite.player.ui.components.ActionIcons.play) { g.player.play(a.tracks) },
                            MenuAction(tr("Вперемешку"), app.malachite.player.ui.components.ActionIcons.shuffle) { g.player.play(a.tracks, shuffle = true) },
                            MenuAction(tr("Добавить в очередь"), app.malachite.player.ui.components.ActionIcons.queue) { g.player.addToQueue(a.tracks) },
                        )
                    },
                    title = a.name,
                ) {
                    ListItem(
                        headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(tr("Альбомов: ${a.albums.size} • песен: ${a.tracks.size}")) },
                        leadingContent = { CoverImage(a.albums.firstOrNull()?.cover, size = 48.dp, corner = 24.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * A row that can be chosen: a tap opens it (or chooses it while choosing); a long press shows
 * its own menu with "choose several" first, or – while choosing – what to do with all chosen.
 */
@Composable
fun ChoosableItem(
    chosen: Boolean?,
    onClick: () -> Unit,
    onChoose: () -> Unit,
    batch: () -> List<MenuAction>,
    ownMenu: () -> List<MenuAction> = { emptyList() },
    trailing: (@Composable () -> Unit)? = null,
    /** What the item is, on top of its menu. */
    title: String = "",
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .then(if (chosen == true) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = if (chosen != null) null else ({ menu = true })),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { content() }
            if (chosen != null) androidx.compose.material3.Checkbox(chosen, { onClick() }, Modifier.padding(end = 8.dp))
            else trailing?.invoke()
        }
        // while choosing, holding and sliding is handled by the list (and its menu is for all chosen)
        if (chosen == null) app.malachite.player.ui.components.SelectionMenu(menu, { menu = false }, title) { listOf(MenuAction(tr("Выбрать несколько"), app.malachite.player.ui.components.ActionIcons.choose, action = onChoose)) + ownMenu() }
    }
}

@Composable
fun ArtistDetailScreen(key: String) {
    val g = MalachiteApp.graph
    val artists by g.library.artists.collectAsState()
    val nav = LocalNavigator.current
    val a = artists.firstOrNull { it.key == key } ?: run { EmptyState(Icons.Filled.Person, tr("Исполнитель не найден"), tr("Возможно, его переименовали при исцелении.")); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(a.name, Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            val data by g.library.data.collectAsState()
            val disliked = data.artistDisliked(a.name)
            PlayButtons(a.tracks) {
                IconButton(onClick = {
                    g.library.setArtistDisliked(a.name, !disliked)
                    nav.toast(if (disliked) tr("«${a.name}» снова играет вперемешку") else tr("«${a.name}»: не нравится — не играет вперемешку и не попадает в умные плейлисты"))
                }) {
                    Icon(
                        if (disliked) app.malachite.player.ui.components.ActionIcons.dislike else app.malachite.player.ui.components.ActionIcons.dislikeOff,
                        if (disliked) tr("Снова нравится") else tr("Не нравится"),
                        tint = if (disliked) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
                    )
                }
            }
            if (disliked) Text(
                tr("Не нравится: песни этого исполнителя не играют вперемешку и не попадают в умные плейлисты. Файлы на месте; нажатие на песню играет её как обычно."),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle(tr("Альбомы"))
        }
        items(a.albums, key = { it.key }) { al ->
            ListItem(
                headlineContent = { Text(al.title) },
                supportingContent = { Text(listOf(al.year, tr("${al.tracks.size} песен")).filter { it.isNotBlank() }.joinToString(" • ")) },
                leadingContent = { CoverImage(al.cover, size = 56.dp) },
                modifier = Modifier.clickable { nav.push(Screen.AlbumDetail(al.key)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
fun AlbumDetailScreen(key: String) {
    val g = MalachiteApp.graph
    val albums by g.library.albums.collectAsState()
    val nav = LocalNavigator.current
    val a = albums.firstOrNull { it.key == key } ?: run { EmptyState(Icons.Filled.Album, tr("Альбом не найден"), tr("Возможно, его переименовали при исцелении.")); return }
    TrackList(a.tracks, numbered = true, header = {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverImage(a.cover, size = 220.dp, corner = 18.dp, sizePx = 600)
            Spacer(Modifier.size(12.dp))
            Text(a.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                a.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { nav.push(Screen.ArtistDetail(a.key.substringBefore('|'))) },
            )
            Text(listOf(a.year, tr("${a.tracks.size} песен"), formatTime(a.durationMs)).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (a.tracks.any { !it.hasArtwork }) {
                TextButton(onClick = { nav.show(DialogRequest.FindCover(a.tracks)) }) { Text(tr("Найти и вшить обложку в файлы альбома")) }
            }
            if (!a.hasNoAlbum()) {
                val shelf by app.malachite.player.data.Shelf.state.collectAsState()
                var menu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { app.malachite.player.data.Shelf.togglePinnedAlbum(a.key) }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.PushPin, tr("Закрепить вверху"),
                            tint = if (a.key in shelf.pinnedAlbums) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { app.malachite.player.data.Shelf.toggleFavoriteAlbum(a.key) }) {
                        Icon(if (a.key in shelf.favoriteAlbums) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Filled.FavoriteBorder,
                            tr("В избранное"), tint = if (a.key in shelf.favoriteAlbums) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Edit, tr("Изменить")) }
                        // after a rename the album has another key: this screen is replaced by the renamed album
                        AlbumMenu(a, menu, onRenamed = { newKey -> nav.back(); nav.push(Screen.AlbumDetail(newKey)) }) { menu = false }
                    }
                }
            }
        }
        PlayButtons(a.tracks)
    })
}

@Composable
fun PlaylistsScreen() {
    val g = MalachiteApp.graph
    val playlists by g.library.playlists.collectAsState()
    val nav = LocalNavigator.current
    var create by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val settings by g.settings.state.collectAsState()
    val shelf by app.malachite.player.data.Shelf.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { app.malachite.player.data.Shelf.ensureLoaded() }
    val ordered = remember(playlists, settings.playlistSort, shelf) {
        val sorted = when (settings.playlistSort) {
            app.malachite.player.data.PlaylistSort.NAME -> playlists.sortedBy { it.name.lowercase() }
            app.malachite.player.data.PlaylistSort.CREATED -> playlists.sortedByDescending { it.createdAt }
            app.malachite.player.data.PlaylistSort.UPDATED -> playlists.sortedByDescending { it.updatedAt }
            app.malachite.player.data.PlaylistSort.SIZE -> playlists.sortedByDescending { it.entries.size }
        }
        sorted.sortedByDescending { it.name in shelf.pinnedPlaylists }
    }
    // only "my playlists" can be chosen (the smart ones are made by the player)
    val sel = app.malachite.player.ui.components.rememberSelection("playlists")
    val keys = remember(ordered) { ordered.map { it.name } }
    androidx.compose.runtime.LaunchedEffect(keys) { sel.retain(keys) }
    var deleting by remember { mutableStateOf<List<String>?>(null) }
    val batch = { playlistsActions(nav, ordered.filter { it.name in sel }, done = { sel.close() }, delete = { deleting = it }) }
    val songsOf = { pl: app.malachite.core.library.Playlist -> pl.entries.mapNotNull { e -> g.library.track(e.relativePath) } }
    Column(Modifier.fillMaxSize()) {
    if (sel.active) app.malachite.player.ui.components.SelectionBar(
        sel, keys, remember(ordered) { groupPicks(ordered, { it.name }, songsOf) },
        app.malachite.player.ui.components.ByName(listOf(tr("Название") to { k: String -> listOf(k) })), batch,
    )
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().dragToChoose(sel, keys, { listState.keyAt(it) }, { listState.scrollBy(it) }) { menuFor = it },
        state = listState,
    ) {
        item { SectionTitle(tr("Умные плейлисты")) }
        items(SmartKind.entries) { k ->
            ListItem(
                headlineContent = { Text(k.title) },
                leadingContent = { Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { nav.push(Screen.SmartPlaylist(k)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("Мои плейлисты"), Modifier.weight(1f))
                TextButton(onClick = { create = true }) { Icon(Icons.Filled.Add, null); Text(tr("Создать")) }
            }
        }
        if (playlists.isEmpty()) item {
            Text(tr("Плейлистов пока нет. Каждый плейлист — это файл в папке _Playlists главной папки (песни не копируются)."),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (playlists.size > 1) item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sel.active) androidx.compose.material3.AssistChip(onClick = { sel.start() }, label = { Text(tr("Выбрать")) },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.CheckBox, null, Modifier.size(18.dp)) })
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { sortMenu = true }, label = { Text(settings.playlistSort.title) },
                        leadingIcon = { Icon(Icons.Filled.Sort, tr("Сортировка"), Modifier.size(18.dp)) },
                    )
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        app.malachite.player.data.PlaylistSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.title) }, onClick = { sortMenu = false; g.settings.update { it.copy(playlistSort = s) } })
                        }
                    }
                }
            }
        }
        items(ordered, key = { it.name }) { p ->
            val pinned = p.name in shelf.pinnedPlaylists
            ChoosableItem(
                chosen = if (sel.active) p.name in sel else null,
                onClick = { if (sel.active) sel.toggle(p.name) else nav.push(Screen.PlaylistDetail(p.name)) },
                onChoose = { sel.start(p.name) }, batch = batch,
                ownMenu = { playlistsActions(nav, listOf(p), done = {}, delete = { deleting = it }) },
                title = p.name,
                trailing = {
                    IconButton(onClick = { app.malachite.player.data.Shelf.togglePinnedPlaylist(p.name) }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.PushPin, if (pinned) tr("Открепить") else tr("Закрепить вверху"),
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                    }
                },
            ) {
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(tr("${p.entries.size} песен • создан ${p.createdAtIso.take(10)}")) },
                    leadingContent = {
                        app.malachite.player.ui.components.PlaylistCover(p.name, p.entries.firstOrNull()?.let { e -> g.library.track(e.relativePath) }, 48.dp)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    }
    deleting?.let { names ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (names.size == 1) tr("Удалить плейлист «${names.first()}»?") else tr("Удалить плейлисты (${names.size})?")) },
            text = { Text(tr("Удаляются только файлы плейлистов. Сами песни остаются в главной папке.")) },
            confirmButton = {
                TextButton(onClick = {
                    names.forEach { g.library.deletePlaylist(it) }
                    deleting = null
                    sel.close()
                    nav.toast(tr("Удалено плейлистов: ${names.size}"))
                }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Отмена")) } },
        )
    }
    if (create) TextInputDialog(tr("Новый плейлист"), "", tr("Название"), onDismiss = { create = false }) { name ->
        g.library.createPlaylist(name); create = false
    }
}

/** What can be done with chosen playlists; [delete] asks before the playlist files go. */
fun playlistsActions(
    nav: Navigator,
    playlists: List<app.malachite.core.library.Playlist>,
    done: () -> Unit,
    delete: (List<String>) -> Unit,
): List<MenuAction> {
    if (playlists.isEmpty()) return emptyList()
    val g = MalachiteApp.graph
    val tracks = playlists.flatMap { p -> p.entries.mapNotNull { e -> g.library.track(e.relativePath) } }
    val shelf = app.malachite.player.data.Shelf.state.value
    val allPinned = playlists.all { it.name in shelf.pinnedPlaylists }
    return listOf(
        MenuAction(tr("Слушать (${tracks.size})"), app.malachite.player.ui.components.ActionIcons.play) { g.player.play(tracks); done() },
        MenuAction(tr("Вперемешку"), app.malachite.player.ui.components.ActionIcons.shuffle) { g.player.play(tracks, shuffle = true); done() },
        MenuAction(tr("Добавить в очередь"), app.malachite.player.ui.components.ActionIcons.queue) { g.player.addToQueue(tracks); done() },
        MenuAction(tr("Собрать в другой плейлист…"), app.malachite.player.ui.components.ActionIcons.playlist) { nav.show(DialogRequest.AddToPlaylist(tracks.map { it.path }.distinct())) },
        MenuAction(if (allPinned) tr("Открепить") else tr("Закрепить вверху"), app.malachite.player.ui.components.ActionIcons.pin) {
            playlists.filter { (it.name in shelf.pinnedPlaylists) == allPinned }.forEach { app.malachite.player.data.Shelf.togglePinnedPlaylist(it.name) }
        },
        MenuAction(if (playlists.size == 1) tr("Удалить плейлист…") else tr("Удалить плейлисты (${playlists.size})…"), app.malachite.player.ui.components.ActionIcons.delete, danger = true) { delete(playlists.map { it.name }) },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text.trim()) }) { Text(tr("Готово")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

@Composable
fun PlaylistDetailScreen(name: String) {
    val g = MalachiteApp.graph
    val playlists by g.library.playlists.collectAsState()
    val tracks by g.library.tracks.collectAsState()
    val player by g.player.state.collectAsState()
    val nav = LocalNavigator.current
    val p = playlists.firstOrNull { it.name == name } ?: run { EmptyState(Icons.Filled.QueueMusic, tr("Плейлист не найден"), ""); return }
    val byPath = remember(tracks) { tracks.associateBy { it.path } }
    val stored = p.entries.mapNotNull { byPath[it.relativePath] }
    // a song carried to another place: shown there at once, while the playlist file is written in the background
    var moved by remember(p.entries) { mutableStateOf<List<TrackInfo>?>(null) }
    val list = moved ?: stored
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cropping by remember { mutableStateOf<ByteArray?>(null) }
    fun saveCover(bytes: ByteArray) {
        scope.launch {
            val art = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.malachite.player.data.AndroidImagePrep.prepare(bytes, square = true)?.also { g.library.setPlaylistCover(p.name, it.bytes) }
            }
            nav.toast(if (art != null) tr("Обложка плейлиста сохранена") else tr("Этот файл не читается как картинка"))
        }
    }
    val pickCover = app.malachite.player.ui.components.rememberImagePicker { bytes ->
        if (bytes == null) nav.toast(tr("Не удалось прочитать картинку")) else cropping = bytes
    }
    cropping?.let { picked ->
        app.malachite.player.ui.components.CropDialog(picked, onDismiss = { cropping = null }) { cut -> cropping = null; saveCover(cut) }
    }
    // the songs of this playlist can be chosen (one song may be in it twice: the place counts)
    val sel = app.malachite.player.ui.components.rememberSelection("playlist")
    val keys = remember(list) { list.mapIndexed { i, t -> "$i|${t.path}" } }
    androidx.compose.runtime.LaunchedEffect(keys) { sel.retain(keys) }
    val chosenIdx = { keys.indices.filter { keys[it] in sel } }
    val batch = {
        val idx = chosenIdx()
        songsActions(nav, idx.map { list[it] }, done = { sel.close() }, extra = listOf(
            MenuAction(tr("Убрать из плейлиста (${idx.size})"), app.malachite.player.ui.components.ActionIcons.remove) {
                val gone = idx.toSet()
                g.library.savePlaylistOrder(p, list.map { it.path }.filterIndexed { j, _ -> j !in gone })
                sel.close()
                nav.toast(tr("Убрано из плейлиста: ${gone.size}"))
            },
        ))
    }
    Column(Modifier.fillMaxSize()) {
    if (sel.active) app.malachite.player.ui.components.SelectionBar(
        sel, keys, remember(list) { songPicks(list) { i, t -> "$i|${t.path}" } },
        remember(list) { songsByName { k -> list.getOrNull(k.substringBefore('|').toIntOrNull() ?: -1) } }, batch,
    )
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
    /** What can be done with the song at place [i] of the playlist. */
    fun rowActions(i: Int, t: TrackInfo): List<MenuAction> = trackActions(nav, t) { sel.start("$i|${t.path}") } + listOfNotNull(
        MenuAction(tr("Убрать из плейлиста"), app.malachite.player.ui.components.ActionIcons.remove) { g.library.savePlaylistOrder(p, list.map { it.path }.filterIndexed { j, _ -> j != i }) },
        if (i > 0) MenuAction(tr("Поднять выше"), app.malachite.player.ui.components.ActionIcons.up) { g.library.savePlaylistOrder(p, list.map { it.path }.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) } else null,
        if (i < list.size - 1) MenuAction(tr("Опустить ниже"), app.malachite.player.ui.components.ActionIcons.down) { g.library.savePlaylistOrder(p, list.map { it.path }.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) } else null,
    )
    // held without carrying: the song's menu
    var holdMenu by remember { mutableIntStateOf(-1) }
    list.getOrNull(holdMenu)?.let { t ->
        app.malachite.player.ui.components.ActionSheet(
            true, { holdMenu = -1 }, t.displayTitle, listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" • "),
            leading = { CoverImage(t, size = 44.dp) },
        ) { rowActions(holdMenu, t) }
    }
    val reorder = app.malachite.player.ui.components.rememberReorderState()
    // sorting the playlist: the new order is written into it; the order before can be brought back
    var sortSheet by remember { mutableStateOf(false) }
    var beforeSort by remember(p.name) { mutableStateOf<List<String>?>(null) }
    fun sortBy(order: app.malachite.core.library.PlaylistOrder) {
        val data = g.library.data.value
        val now = app.malachite.core.library.PlaylistSorting.sort(list, order) { t -> data.user(t.path).addedAt.takeIf { it > 0 } ?: t.lastModified }
        if (now.map { it.path } == list.map { it.path }) { nav.toast(tr("Плейлист уже в таком порядке")); return }
        beforeSort = list.map { it.path }
        moved = now
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { g.library.savePlaylistOrder(p, now.map { it.path }) }
        nav.toast(tr("Порядок сохранён в плейлисте. Вернуть прежний — там же, в сортировке"))
    }
    app.malachite.player.ui.components.ActionSheet(sortSheet, { sortSheet = false }, tr("Сортировать плейлист"), tr("Новый порядок записывается в плейлист")) {
        listOfNotNull(
            MenuAction(tr("По альбомам"), Icons.Filled.Album) { sortBy(app.malachite.core.library.PlaylistOrder.ALBUMS) },
            MenuAction(tr("По исполнителям"), Icons.Filled.Person) { sortBy(app.malachite.core.library.PlaylistOrder.ARTISTS) },
            MenuAction(tr("По названию"), Icons.Filled.SortByAlpha) { sortBy(app.malachite.core.library.PlaylistOrder.TITLES) },
            MenuAction(tr("По году, старые первыми"), Icons.Filled.CalendarMonth) { sortBy(app.malachite.core.library.PlaylistOrder.YEARS) },
            MenuAction(tr("Недавно добавленные первыми"), Icons.Filled.NewReleases) { sortBy(app.malachite.core.library.PlaylistOrder.ADDED) },
            MenuAction(tr("Длинные первыми"), Icons.Filled.Timer) { sortBy(app.malachite.core.library.PlaylistOrder.LONGEST) },
            MenuAction(tr("Перевернуть"), Icons.Filled.SwapVert) { sortBy(app.malachite.core.library.PlaylistOrder.REVERSE) },
            beforeSort?.let { old ->
                MenuAction(tr("Вернуть порядок до сортировки"), Icons.Filled.Restore) {
                    val byPathNow = list.associateBy { it.path }
                    val back = old.mapNotNull { byPathNow[it] }
                    beforeSort = null
                    moved = back
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { g.library.savePlaylistOrder(p, back.map { it.path }) }
                    nav.toast(tr("Прежний порядок вернулся"))
                }
            },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(app.malachite.player.ui.components.LocalListSelection provides sel) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
    LazyColumn(
        Modifier.fillMaxSize()
            .dragToChoose(sel, keys, { listState.keyAt(it) }, { listState.scrollBy(it) }) { menuFor = it }
            .dragToReorder(
                reorder, listState, enabled = !sel.active,
                indexOf = { k -> (k as? String)?.substringBefore('|')?.toIntOrNull()?.takeIf { it in list.indices } ?: -1 },
                onMove = { from, to ->
                    val now = list.toMutableList().apply { add(to, removeAt(from)) }
                    moved = now
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { g.library.savePlaylistOrder(p, now.map { it.path }) }
                },
                onHold = { holdMenu = it },
            ),
        state = listState,
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    app.malachite.player.ui.components.PlaylistCover(
                        p.name, list.firstOrNull(), 84.dp, 14.dp,
                        Modifier.clickable { if (g.library.playlistCover(p.name) != null) coverMenu = true else pickCover() },
                    )
                    androidx.compose.material3.DropdownMenu(coverMenu, { coverMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Выбрать другую картинку")) }, onClick = { coverMenu = false; pickCover() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Обрезать эту картинку")) }, onClick = {
                            coverMenu = false
                            runCatching { g.library.playlistCover(p.name)?.readBytes() }.getOrNull()?.let { cropping = it }
                        })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Убрать свою обложку")) }, onClick = { coverMenu = false; g.library.setPlaylistCover(p.name, null) })
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("${list.size} песен • ${formatTime(list.sumOf { it.durationMs })}"), style = MaterialTheme.typography.bodySmall)
                    if (list.size < p.entries.size) Text(tr("Не найдено в главной папке: ${p.entries.size - list.size}"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(tr("Нажмите на картинку, чтобы поставить свою обложку"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, tr("Переименовать")) }
                IconButton(onClick = { delete = true }) { Icon(Icons.Filled.Delete, tr("Удалить")) }
            }
            PlayButtons(list) {
                if (list.size > 1 && !sel.active) IconButton(onClick = { sortSheet = true }) {
                    Icon(Icons.Filled.Sort, tr("Сортировать плейлист"))
                }
            }
            if (list.size > 1 && !sel.active) Text(
                tr("Зажмите песню и перенесите её выше или ниже, чтобы поменять порядок"),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(list, key = { i, t -> "$i|${t.path}" }) { i, t ->
            val choosing = sel.active
            // the carried song is drawn above the list (below); its place stays empty, the others step aside
            val carried = reorder.from == i
            val shift by androidx.compose.animation.core.animateFloatAsState(reorder.shiftOf(i), label = "rowShift")
            Box(
                Modifier.graphicsLayer {
                    translationY = if (carried) 0f else shift
                    alpha = if (carried) 0f else 1f
                },
            ) {
            TrackRow(
                track = t, playing = t.path == player.currentPath, favorite = false,
                onClick = if (choosing) ({ sel.toggle("$i|${t.path}") }) else ({ g.player.play(list, i) }),
                selected = if (choosing) "$i|${t.path}" in sel else null,
                onLongPressChoosing = { if ("$i|${t.path}" !in sel) sel.toggle("$i|${t.path}") },
                menuActions = if (choosing) batch else null,
                actions = if (choosing) emptyList() else rowActions(i, t),
                // holding is handled by the list: carry the song, or its menu when it is not carried
                onLongClick = {},
            )
            }
        }
    }
    // the song in the finger: it stays on the screen however far the list scrolls under it
    list.getOrNull(reorder.from)?.let { t ->
        Box(
            Modifier.fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(0, reorder.top.roundToInt()) }
                .zIndex(1f)
                .graphicsLayer {
                    scaleX = 1.02f
                    scaleY = 1.02f
                    shadowElevation = 16.dp.toPx()
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            TrackRow(track = t, playing = t.path == player.currentPath, favorite = false, onClick = {}, actions = emptyList(), onLongClick = {})
        }
    }
    }
    }
    }
    if (rename) TextInputDialog(tr("Переименовать"), p.name, tr("Название"), { rename = false }) {
        g.library.renamePlaylist(p, it); app.malachite.player.data.Shelf.playlistRenamed(p.name, it); rename = false; nav.back()
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text(tr("Удалить плейлист «${p.name}»?")) },
        text = { Text(tr("Удаляется только файл плейлиста. Сами песни остаются в главной папке.")) },
        confirmButton = { TextButton(onClick = { g.library.deletePlaylist(p.name); delete = false; nav.back() }) { Text(tr("Удалить")) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(tr("Отмена")) } },
    )
}

@Composable
fun SmartPlaylistScreen(kind: SmartKind) {
    val g = MalachiteApp.graph
    val tracks by g.library.tracks.collectAsState()
    val data by g.library.data.collectAsState()
    val list = remember(tracks, data, kind) {
        when (kind) {
            SmartKind.FAVORITES -> tracks.filter { data.user(it.path).favorite }
            // what the user does not like is left out of the made-up lists
            SmartKind.MOST_PLAYED -> tracks.filter { data.user(it.path).playCount > 0 && !data.disliked(it) }.sortedByDescending { data.user(it.path).playCount }.take(100)
            SmartKind.RECENTLY_ADDED -> tracks.filter { !data.disliked(it) }.sortedByDescending { data.user(it.path).addedAt.takeIf { a -> a > 0 } ?: it.lastModified }.take(100)
            SmartKind.RECENTLY_PLAYED -> tracks.filter { data.user(it.path).lastPlayed > 0 && !data.disliked(it) }.sortedByDescending { data.user(it.path).lastPlayed }.take(100)
        }
    }
    if (list.isEmpty()) {
        EmptyState(Icons.Filled.Star, kind.title, if (kind == SmartKind.FAVORITES) tr("Отмечайте песни сердечком — они появятся здесь.") else tr("Пока пусто."))
        return
    }
    TrackList(list, header = { PlayButtons(list) })
}

/** Browses the real folder structure of the main folder: Artist → Album → songs. */
@Composable
fun FolderScreen(relative: String) {
    val g = MalachiteApp.graph
    val tracks by g.library.tracks.collectAsState()
    val nav = LocalNavigator.current
    val root = g.library.root ?: return
    val prefix = if (relative.isEmpty()) "" else "$relative/"
    val inHere = tracks.filter { it.path.startsWith(prefix) }
    val subdirs = inHere.map { it.path.removePrefix(prefix) }.filter { it.contains('/') }.map { it.substringBefore('/') }.distinct().sortedBy { it.lowercase() }
    val files = inHere.filter { !it.path.removePrefix(prefix).contains('/') }
    Column {
        Text(File(root, relative).absolutePath, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TrackList(files, header = {
            Column {
                subdirs.forEach { d ->
                    val count = inHere.count { it.path.startsWith("$prefix$d/") }
                    ListItem(
                        headlineContent = { Text(d) },
                        supportingContent = { Text(tr("$count песен")) },
                        leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { nav.push(Screen.Folder(prefix + d)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        })
    }
}

/** Search that ignores case, spaces, decoration and alphabet ("kino" finds "Кино"). */
@Composable
fun SearchScreen() {
    val g = MalachiteApp.graph
    val tracks by g.library.tracks.collectAsState()
    val albums by g.library.albums.collectAsState()
    val artists by g.library.artists.collectAsState()
    val nav = LocalNavigator.current
    var q by rememberSaveable { mutableStateOf("") }
    val key = TextFold.strictKey(q)
    val loose = TextFold.loosen(key)
    fun match(s: String): Boolean {
        if (key.isEmpty()) return false
        val k = TextFold.strictKey(s)
        return k.contains(key) || TextFold.loosen(k).contains(loose)
    }
    val data by g.library.data.collectAsState()
    val playlists by g.library.playlists.collectAsState()
    val onlineEnabled = g.settings.state.collectAsState().value.onlineEnabled
    var scope by rememberSaveable { mutableStateOf(SearchScope.ALL) }
    var filters by rememberSaveable { mutableStateOf(emptySet<SongFilter>()) }
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    val showSongs = scope == SearchScope.ALL || scope == SearchScope.SONGS
    // with a filter on and nothing typed, the filter alone picks the songs ("all favourites")
    val songs = remember(q, tracks, filters, sort, data, showSongs) {
        if (!showSongs || (key.isEmpty() && filters.isEmpty())) emptyList()
        else sortSongs(
            tracks.asSequence()
                .filter { key.isEmpty() || match(it.displayTitle) || match(it.displayArtist) || match(it.album) || match(it.fileName.substringBeforeLast('.')) }
                .filter { t -> filters.all { f -> f.accepts(t, data.user(t.path)) } }
                .take(500).toList(),
            sort, data,
        )
    }
    val foundArtists = remember(q, artists, scope) {
        if (scope == SearchScope.ALL || scope == SearchScope.ARTISTS) artists.filter { match(it.name) }.take(if (scope == SearchScope.ALL) 20 else 200) else emptyList()
    }
    val foundAlbums = remember(q, albums, scope) {
        if (scope == SearchScope.ALL || scope == SearchScope.ALBUMS) albums.filter { match(it.title) || match(it.artist) }.take(if (scope == SearchScope.ALL) 20 else 200) else emptyList()
    }
    val foundPlaylists = remember(q, playlists, scope) {
        if (scope == SearchScope.ALL || scope == SearchScope.PLAYLISTS) playlists.filter { match(it.name) }.take(50) else emptyList()
    }
    // What was searched for is remembered: on "search" on the keyboard, after the words stayed the
    // same for a few seconds with something found, and when the screen is left.
    val found = songs.isNotEmpty() || foundArtists.isNotEmpty() || foundAlbums.isNotEmpty() || foundPlaylists.isNotEmpty()
    val keep by androidx.compose.runtime.rememberUpdatedState(if (found) q.trim() else "")
    androidx.compose.runtime.LaunchedEffect(q, found) {
        if (!found || q.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        app.malachite.player.data.SearchHistory.add(app.malachite.player.data.SearchHistory.Kind.LIBRARY, q)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (keep.isNotEmpty()) app.malachite.player.data.SearchHistory.add(app.malachite.player.data.SearchHistory.Kind.LIBRARY, keep) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it },
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            placeholder = { Text(tr("Песня, исполнитель, альбом… (можно латиницей)")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (q.isNotEmpty()) ({ IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, tr("Очистить")) } }) else null,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                app.malachite.player.data.SearchHistory.add(app.malachite.player.data.SearchHistory.Kind.LIBRARY, q)
            }),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchScope.entries.forEach { sc ->
                androidx.compose.material3.FilterChip(selected = scope == sc, onClick = { scope = sc }, label = { Text(sc.title) })
            }
        }
        if (showSongs) SortAndFilterRow(sort, { sort = it }, filters) { filters = it }
        TrackList(songs, header = {
            Column {
                // nothing typed: the recent searches, a tap searches again
                if (q.isBlank() && filters.isEmpty()) app.malachite.player.data.RecentSearches(app.malachite.player.data.SearchHistory.Kind.LIBRARY) { q = it }
                if (foundArtists.isNotEmpty()) SectionTitle(tr("Исполнители"))
                foundArtists.forEach { a ->
                    ListItem(headlineContent = { Text(a.name) }, leadingContent = { Icon(Icons.Filled.Person, null) },
                        modifier = Modifier.clickable { nav.push(Screen.ArtistDetail(a.key)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
                if (foundAlbums.isNotEmpty()) SectionTitle(tr("Альбомы"))
                foundAlbums.forEach { a ->
                    ListItem(headlineContent = { Text(a.title) }, supportingContent = { Text(a.artist) }, leadingContent = { CoverImage(a.cover, size = 40.dp) },
                        modifier = Modifier.clickable { nav.push(Screen.AlbumDetail(a.key)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
                if (foundPlaylists.isNotEmpty()) SectionTitle(tr("Плейлисты"))
                foundPlaylists.forEach { p ->
                    ListItem(
                        headlineContent = { Text(p.name) }, supportingContent = { Text(tr("${p.entries.size} песен")) },
                        leadingContent = { app.malachite.player.ui.components.PlaylistCover(p.name, p.entries.firstOrNull()?.let { e -> g.library.track(e.relativePath) }, 40.dp) },
                        modifier = Modifier.clickable { nav.push(Screen.PlaylistDetail(p.name)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (songs.isNotEmpty()) SectionTitle(tr("Песни: ${songs.size}"))
                if ((q.isNotEmpty() || filters.isNotEmpty()) && songs.isEmpty() && foundAlbums.isEmpty() && foundArtists.isEmpty() && foundPlaylists.isEmpty()) {
                    Text(tr("Ничего не найдено"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onlineEnabled && q.isNotBlank()) {
                    TextButton(onClick = { nav.push(Screen.Online(q.trim())) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(tr("Искать «${q.trim()}» в интернете"))
                    }
                }
            }
        })
    }
}
