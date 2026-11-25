package app.malachite.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import app.malachite.player.ui.components.dragToChoose
import app.malachite.player.ui.components.keyAt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.core.audio.TagEdit
import app.malachite.core.i18n.tr
import app.malachite.core.library.LibraryLayout
import app.malachite.core.library.TrackInfo
import app.malachite.core.text.TextFold
import app.malachite.player.MalachiteApp
import app.malachite.player.data.Album
import app.malachite.player.data.AlbumSort
import app.malachite.player.data.Shelf
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.components.CoverImage
import app.malachite.player.ui.components.EmptyState
import kotlinx.coroutines.launch

/** Songs without an album of their own: kept out of the grid of albums, behind one tile. */
fun Album.hasNoAlbum(): Boolean = title == LibraryLayout.UNKNOWN_ALBUM || tracks.all { it.album.isBlank() }

@Composable
private fun gridCells(): GridCells {
    val settings by MalachiteApp.graph.settings.state.collectAsState()
    // On a phone: the number of columns from the settings. On wider windows the same tile
    // size is kept and more columns appear, instead of a few giant covers.
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val columns = settings.albumGridColumns.coerceIn(1, 4)
    return if (widthDp >= 600) GridCells.Adaptive((360 / columns).coerceIn(120, 220).dp) else GridCells.Fixed(columns)
}

@Composable
fun AlbumsScreen() {
    val g = MalachiteApp.graph
    val all by g.library.albums.collectAsState()
    val settings by g.settings.state.collectAsState()
    val shelf by Shelf.state.collectAsState()
    val nav = LocalNavigator.current
    LaunchedEffect(Unit) { Shelf.ensureLoaded() }
    if (all.isEmpty()) {
        EmptyState(Icons.Filled.Album, tr("Нет альбомов"), tr("Добавьте песни в главную папку."))
        return
    }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val loose = remember(all) { all.filter { it.hasNoAlbum() }.flatMap { it.tracks } }
    val albums = remember(all, settings.albumSort, shelf, onlyFavorites) {
        val real = all.filter { !it.hasNoAlbum() && (!onlyFavorites || it.key in shelf.favoriteAlbums) }
        val sorted = when (settings.albumSort) {
            AlbumSort.TITLE -> real.sortedBy { TextFold.strictKey(it.title) }
            AlbumSort.ARTIST -> real.sortedWith(compareBy({ TextFold.strictKey(it.artist) }, { it.year }, { TextFold.strictKey(it.title) }))
            AlbumSort.YEAR -> real.sortedWith(compareByDescending<Album> { it.year }.thenBy { TextFold.strictKey(it.title) })
            AlbumSort.SIZE -> real.sortedByDescending { it.tracks.size }
            AlbumSort.RECENT -> real.sortedByDescending { a -> a.tracks.maxOfOrNull { it.lastModified } ?: 0L }
        }
        // pinned albums stay at the top in whatever order was chosen
        sorted.sortedByDescending { it.key in shelf.pinnedAlbums }
    }
    val sel = app.malachite.player.ui.components.rememberSelection("albums")
    val keys = remember(albums) { albums.map { it.key } }
    LaunchedEffect(keys) { sel.retain(keys) }
    val batch = { albumsActions(nav, albums.filter { it.key in sel }, done = { sel.close() }) }
    Column(Modifier.fillMaxSize()) {
        val byKey = remember(albums) { albums.associateBy { it.key } }
        if (sel.active) app.malachite.player.ui.components.SelectionBar(
            sel, keys, remember(albums) { groupPicks(albums, { it.key }, { it.tracks }) },
            remember(byKey) {
                app.malachite.player.ui.components.ByName(listOf(
                    tr("Исполнитель") to { k: String -> listOfNotNull(byKey[k]?.artist) },
                    tr("Альбом") to { k: String -> listOfNotNull(byKey[k]?.title) },
                ))
            },
            batch,
            // all the albums of one artist, of one year
            groups = remember(albums) {
                listOf(
                    app.malachite.player.ui.components.GroupChoice(tr("Исполнитель")) {
                        albums.groupBy { it.artist }.map { (name, als) -> name to als.map { it.key } }.sortedBy { app.malachite.core.text.TextFold.strictKey(it.first) }
                    },
                    app.malachite.player.ui.components.GroupChoice(tr("Год")) {
                        albums.filter { it.year.isNotBlank() }.groupBy { it.year }.map { (y, als) -> y to als.map { it.key } }.sortedByDescending { it.first }
                    },
                )
            },
        )
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        var menuFor by remember { mutableStateOf<String?>(null) }
        app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!sel.active && albums.size > 1) AssistChip(onClick = { sel.start() }, label = { Text(tr("Выбрать")) },
                leadingIcon = { Icon(Icons.Filled.CheckBox, null, Modifier.size(18.dp)) })
            Box {
                AssistChip(onClick = { sortMenu = true }, label = { Text(settings.albumSort.title) }, leadingIcon = { Icon(Icons.Filled.Sort, tr("Сортировка"), Modifier.size(18.dp)) })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    AlbumSort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.title) }, onClick = { sortMenu = false; g.settings.update { it.copy(albumSort = s) } })
                    }
                }
            }
            FilterChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, label = { Text(tr("Избранные")) },
                leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)) })
        }
        LazyVerticalGrid(
            gridCells(),
            Modifier.fillMaxSize().dragToChoose(sel, keys, { gridState.keyAt(it) }, { gridState.scrollBy(it) }) { menuFor = it },
            state = gridState, contentPadding = PaddingValues(8.dp),
        ) {
            if (loose.isNotEmpty() && !onlyFavorites) item(key = "\u0000no-album") { NoAlbumCard(loose) { nav.push(Screen.NoAlbum) } }
            items(albums, key = { it.key }) { a ->
                AlbumGridItem(
                    a, pinned = a.key in shelf.pinnedAlbums, favorite = a.key in shelf.favoriteAlbums,
                    chosen = if (sel.active) a.key in sel else null,
                    onChoose = { sel.start(a.key) }, batch = batch,
                    onClick = { if (sel.active) sel.toggle(a.key) else nav.push(Screen.AlbumDetail(a.key)) },
                )
            }
        }
        if (albums.isEmpty() && onlyFavorites) {
            Text(tr("Избранных альбомов пока нет. Удерживайте альбом, чтобы добавить его в избранное или закрепить."),
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * An album in the grid: a tap opens it, a long press shows what can be done with it. While
 * albums are being chosen ([chosen] not null) a tap chooses and a long press shows what can be
 * done with all chosen ones.
 */
@Composable
private fun AlbumGridItem(
    a: Album, pinned: Boolean, favorite: Boolean,
    chosen: Boolean? = null, onChoose: () -> Unit = {}, batch: () -> List<app.malachite.player.ui.components.MenuAction> = { emptyList() },
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        // while choosing, holding and sliding belongs to the grid (and its menu is for all chosen)
        Column(Modifier.then(app.malachite.player.ui.components.A11yGroup).combinedClickable(onClick = onClick, onLongClick = if (chosen != null) null else ({ menu = true })).padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CoverImage(a.cover, Modifier.fillMaxSize(), size = 400.dp, corner = 14.dp, sizePx = 400)
                if (chosen != null) ChosenMark(chosen, Modifier.align(Alignment.TopStart))
                if (pinned || favorite) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape).padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (pinned) Icon(Icons.Filled.PushPin, tr("Закреплён"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        if (favorite) Icon(Icons.Filled.Favorite, tr("В избранном"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text("${a.artist} • ${a.tracks.size}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chosen == null) AlbumMenu(a, menu, onChoose = onChoose) { menu = false }
    }
}

/** The round mark in the corner of a chosen (or not yet chosen) tile. */
@Composable
fun ChosenMark(chosen: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(6.dp).size(26.dp).clip(CircleShape)
            .background(if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(Icons.Filled.Check, tr("Выбрано"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** What can be done with several chosen albums: as albums (pin, favourite) and as their songs. */
fun albumsActions(nav: app.malachite.player.ui.Navigator, albums: List<Album>, done: () -> Unit): List<app.malachite.player.ui.components.MenuAction> {
    if (albums.isEmpty()) return emptyList()
    val shelf = Shelf.state.value
    val allPinned = albums.all { it.key in shelf.pinnedAlbums }
    val allFavorite = albums.all { it.key in shelf.favoriteAlbums }
    return listOf(
        app.malachite.player.ui.components.MenuAction(if (allPinned) tr("Открепить") else tr("Закрепить вверху"), app.malachite.player.ui.components.ActionIcons.pin) {
            albums.filter { (it.key in shelf.pinnedAlbums) == allPinned }.forEach { Shelf.togglePinnedAlbum(it.key) }
        },
        app.malachite.player.ui.components.MenuAction(if (allFavorite) tr("Убрать из избранных альбомов") else tr("В избранные альбомы"), if (allFavorite) app.malachite.player.ui.components.ActionIcons.unfavorite else app.malachite.player.ui.components.ActionIcons.favorite) {
            albums.filter { (it.key in shelf.favoriteAlbums) == allFavorite }.forEach { Shelf.toggleFavoriteAlbum(it.key) }
        },
    ) + songsActions(nav, albums.flatMap { it.tracks }, done)
}

/** Pin, favourite, rename, cover – the same list for the grid and for the open album. */
@Composable
fun AlbumMenu(a: Album, expanded: Boolean, onRenamed: (String) -> Unit = {}, onChoose: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val shelf by Shelf.state.collectAsState()
    var rename by remember { mutableStateOf(false) }
    DropdownMenu(expanded, onDismiss) {
        if (onChoose != null) DropdownMenuItem(text = { Text(tr("Выбрать несколько")) }, onClick = { onDismiss(); onChoose() })
        DropdownMenuItem(text = { Text(if (a.key in shelf.pinnedAlbums) tr("Открепить") else tr("Закрепить вверху")) }, onClick = { onDismiss(); Shelf.togglePinnedAlbum(a.key) })
        DropdownMenuItem(text = { Text(if (a.key in shelf.favoriteAlbums) tr("Убрать из избранного") else tr("В избранное")) }, onClick = { onDismiss(); Shelf.toggleFavoriteAlbum(a.key) })
        DropdownMenuItem(text = { Text(tr("Переименовать")) }, onClick = { onDismiss(); rename = true })
        DropdownMenuItem(text = { Text(tr("Заменить или обрезать обложку")) }, onClick = { onDismiss(); nav.show(DialogRequest.FindCover(a.tracks)) })
    }
    if (rename) RenameAlbumDialog(a, onDismiss = { rename = false }, onRenamed = onRenamed)
}

/** The new name is written into every file of the album (tags), through the usual safe way. */
@Composable
private fun RenameAlbumDialog(a: Album, onDismiss: () -> Unit, onRenamed: (String) -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(a.title) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Название альбома")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Альбом")) }, singleLine = true, enabled = !busy)
                Text(tr("Название записывается в каждый файл альбома, файлы переедут в папку с новым именем."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) {
                    LinearProgressIndicator(progress = { if (a.tracks.isEmpty()) 0f else done.toFloat() / a.tracks.size }, modifier = Modifier.fillMaxWidth())
                    Text("$done / ${a.tracks.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && name.trim() != a.title, onClick = {
                val newName = name.trim()
                busy = true
                scope.launch {
                    var failed = 0
                    for (t in a.tracks) {
                        if (g.library.editTags(t, TagEdit(album = newName)).isFailure) failed++
                        done++
                    }
                    val newKey = a.key.substringBefore('|') + "|" + TextFold.strictKey(newName)
                    Shelf.albumRenamed(a.key, newKey)
                    busy = false
                    nav.toast(if (failed == 0) tr("Альбом переименован") else tr("Переименовано, не получилось для файлов: $failed"))
                    onDismiss()
                    onRenamed(newKey)
                }
            }) { Text(tr("Сохранить")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

/** One tile for all songs without an album: its cover is made of the four newest covers among them. */
@Composable
private fun NoAlbumCard(loose: List<TrackInfo>, onClick: () -> Unit) {
    val four = remember(loose) { loose.filter { it.hasArtwork }.sortedByDescending { it.lastModified }.take(4) }
    Column(Modifier.then(app.malachite.player.ui.components.A11yGroup).combinedClickable(onClick = onClick).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (four.isEmpty()) Icon(Icons.Filled.MusicNote, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Column(Modifier.fillMaxSize()) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..1) Box(Modifier.weight(1f).fillMaxHeight()) {
                        four.getOrNull(row * 2 + col)?.let { CoverImage(it, Modifier.fillMaxSize(), size = 200.dp, corner = 0.dp, sizePx = 220) }
                    }
                }
            }
        }
        Text(tr("Без альбома"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(tr("${loose.size} песен"), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The songs without an album, laid out like the albums are: a cover, the title, the artist. */
@Composable
fun NoAlbumScreen() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val all by g.library.albums.collectAsState()
    // only the song playing (each tile follows its own), not every change of the player
    val playerState = g.player.state.collectAsState()
    val playingPath = remember { androidx.compose.runtime.derivedStateOf { playerState.value.currentPath } }
    val songs = remember(all) { all.filter { it.hasNoAlbum() }.flatMap { it.tracks }.sortedBy { TextFold.strictKey(it.displayTitle) } }
    if (songs.isEmpty()) {
        EmptyState(Icons.Filled.Album, tr("Песен без альбома нет"), tr("У каждой песни в главной папке есть свой альбом."))
        return
    }
    val sel = app.malachite.player.ui.components.rememberSelection("songs")
    val keys = remember(songs) { songs.map { it.path } }
    LaunchedEffect(keys) { sel.retain(keys) }
    val batch = { songsActions(nav, songs.filter { it.path in sel }, done = { sel.close() }) }
    Column(Modifier.fillMaxSize()) {
    val byPath = remember(songs) { songs.associateBy { it.path } }
    if (sel.active) app.malachite.player.ui.components.SelectionBar(
        sel, keys, remember(songs) { songPicks(songs) }, remember(byPath) { songsByName { byPath[it] } }, batch,
        groups = remember(songs) { songGroups(songs) },
    )
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    app.malachite.player.ui.components.SelectionMenu(menuFor != null, { menuFor = null }, tr("Выбрано: ${sel.size}"), batch)
    androidx.compose.runtime.CompositionLocalProvider(app.malachite.player.ui.components.LocalListSelection provides sel) {
    LazyVerticalGrid(
        gridCells(),
        Modifier.weight(1f).fillMaxWidth().dragToChoose(sel, keys, { gridState.keyAt(it) }, { gridState.scrollBy(it) }) { menuFor = it },
        state = gridState, contentPadding = PaddingValues(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { PlayButtons(songs) }
        itemsIndexed(songs, key = { _, t -> t.path }) { i, t ->
            var menu by remember { mutableStateOf(false) }
            val playing by remember(t.path) { androidx.compose.runtime.derivedStateOf { playingPath.value == t.path } }
            val chosen = if (sel.active) t.path in sel else null
            Box {
                Column(Modifier.then(app.malachite.player.ui.components.A11yGroup).combinedClickable(
                    onClick = { if (chosen != null) sel.toggle(t.path) else g.player.play(songs, i) },
                    onLongClick = if (chosen != null) null else ({ menu = true }),
                ).padding(8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        CoverImage(t, Modifier.fillMaxSize(), size = 400.dp, corner = 14.dp, sizePx = 400)
                        if (chosen != null) ChosenMark(chosen, Modifier.align(Alignment.TopStart))
                    }
                    Text(
                        t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (chosen == null && menu) app.malachite.player.ui.components.ActionSheet(
                    true, { menu = false }, t.displayTitle, t.displayArtist,
                    leading = { CoverImage(t, size = 44.dp) },
                ) { trackActions(nav, t, songs) { sel.start(t.path) } }
            }
        }
    }
    }
    }
}
