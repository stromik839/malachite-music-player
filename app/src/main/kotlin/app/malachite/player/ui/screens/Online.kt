package app.malachite.player.ui.screens

import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.OutlinedButton
import app.malachite.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.core.online.OnlineCatalog
import app.malachite.player.MalachiteApp
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.components.formatTime
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads keep going when the screen is left: their state lives outside it. */
private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val downloading = mutableStateMapOf<String, Float>()
private val downloadingTitles = mutableStateMapOf<String, String>()
private val downloaded = mutableStateListOf<String>()

/** Songs being downloaded right now: title and progress 0..1 (below 0 = size unknown). For the "Processes" screen. */
fun activeDownloads(): List<Pair<String, Float>> = downloading.map { (id, progress) -> (downloadingTitles[id] ?: id) to progress }

/**
 * Downloads an online song into the main folder, where it becomes an ordinary song.
 * [report] gets one line about the result (for a toast).
 */
fun downloadOnline(item: OnlineCatalog.Item, report: (String) -> Unit) {
    if (item.downloadUrl == null) { report(tr("Эту песню автор не открыл для скачивания")); return }
    if (item.id in downloading) return
    val g = MalachiteApp.graph
    downloading[item.id] = 0f
    downloadingTitles[item.id] = listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" — ")
    downloadScope.launch {
        val result = withContext(Dispatchers.IO) { g.library.addDownloaded(item, g.http) { p -> downloading[item.id] = p } }
        downloading.remove(item.id)
        downloadingTitles.remove(item.id)
        if (result.isSuccess) {
            downloaded += item.id
            report(tr("Скачано в главную папку: ${item.title}"))
        } else report(tr("Не скачалось: ${result.exceptionOrNull()?.message ?: tr("ошибка")}"))
    }
}

/** "Download all" of a release or an artist, running in the background. */
private data class BulkDownload(
    val listId: String,
    val title: String,
    val total: Int,
    val ok: Int = 0,
    val failed: Int = 0,
    /** The song being downloaded now (its progress is in [downloading]). */
    val currentId: String? = null,
)

private val bulk = androidx.compose.runtime.mutableStateOf<BulkDownload?>(null)
private var bulkJob: Job? = null

/**
 * Downloads every song of [items] whose author allows it, one after another (the catalogues and
 * the phone are spared a dozen downloads at once). The songs still waiting show as queued; a
 * stop leaves the ones already downloaded in the main folder.
 */
private fun downloadAllOnline(listId: String, title: String, items: List<OnlineCatalog.Item>, report: (String) -> Unit) {
    if (bulkJob?.isActive == true) { report(tr("Уже скачивается: ${bulk.value?.title.orEmpty()}")); return }
    val todo = items.filter { !it.isRelease && it.downloadUrl != null && it.id !in downloaded && it.id !in downloading }
    if (todo.isEmpty()) { report(tr("Скачивать нечего: всё уже скачано или авторы не открыли скачивание")); return }
    val g = MalachiteApp.graph
    for (item in todo) {
        downloading[item.id] = 0f
        downloadingTitles[item.id] = listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" — ")
    }
    bulk.value = BulkDownload(listId, title, todo.size)
    bulkJob = downloadScope.launch {
        var ok = 0
        var failed = 0
        try {
            for (item in todo) {
                bulk.value = bulk.value?.copy(currentId = item.id)
                val result = withContext(Dispatchers.IO) { g.library.addDownloaded(item, g.http) { p -> downloading[item.id] = p } }
                downloading.remove(item.id)
                downloadingTitles.remove(item.id)
                if (result.isSuccess) { downloaded += item.id; ok++ } else failed++
                bulk.value = bulk.value?.copy(ok = ok, failed = failed)
            }
            report(
                if (failed == 0) tr("Скачано в главную папку: $ok из ${todo.size}")
                else tr("Скачано в главную папку: $ok из ${todo.size}, не скачалось: $failed"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            report(tr("Скачивание остановлено: скачано $ok из ${todo.size}"))
            throw e
        } finally {
            // the songs that were still waiting are not downloaded
            for (item in todo) if (item.id !in downloaded) { downloading.remove(item.id); downloadingTitles.remove(item.id) }
            bulk.value = null
        }
    }
}

private fun stopDownloadAll() { bulkJob?.cancel() }

/**
 * The online section: search in open catalogues, listen, download into the main folder.
 * It does nothing until it is switched on in the settings, and goes to the internet only
 * when the user searches, listens or downloads here.
 */
@Composable
fun OnlineScreen(initialQuery: String = "") {
    val g = MalachiteApp.graph
    val settings by g.settings.state.collectAsState()
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()

    if (!settings.onlineEnabled) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Онлайн-раздел выключен"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                tr("Плеер работает без интернета. Если включить раздел, здесь можно будет искать музыку в открытых каталогах " +
                    "Audius и Internet Archive, слушать её и скачивать в главную папку, а остальные песни находить по " +
                    "30-секундным отрывкам. В сеть плеер выходит только когда вы сами ищете, слушаете или скачиваете."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { g.settings.update { it.copy(onlineEnabled = true) } }) { Text(tr("Включить онлайн-раздел")) }
        }
        return
    }

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<OnlineCatalog.Results?>(null) }
    // which of the found things are shown (all were found by one search: switching is instant)
    var kind by rememberSaveable { mutableStateOf(OnlineCatalog.Kind.ALL) }
    // the 30-second excerpts are folded away while there are whole songs to listen to
    var showExcerpts by rememberSaveable { mutableStateOf(false) }
    var release by remember { mutableStateOf<OnlineCatalog.Item?>(null) }
    var releaseTracks by remember { mutableStateOf<List<OnlineCatalog.Item>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Online songs play through the same player as everything else: queue, notification,
    // lock screen, widget. They are just not part of the library until they are downloaded.
    val player by g.player.state.collectAsState()

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        app.malachite.player.data.SearchHistory.add(app.malachite.player.data.SearchHistory.Kind.ONLINE, q)
        job?.cancel()
        busy = true; message = null; release = null; releaseTracks = null
        job = scope.launch {
            app.malachite.player.data.Net.whenOnline({ waiting -> message = if (waiting) tr("Нет интернета. Поиск начнётся сам, как только появится связь.") else null }) {}
            val found = withContext(Dispatchers.IO) { runCatching { g.online.find(q) } }
            busy = false
            results = found.getOrNull() ?: OnlineCatalog.Results(emptyList(), emptyList(), emptyList())
            if (found.isFailure) message = tr("Не удалось выполнить поиск: проверьте интернет")
            else if (results?.isEmpty == true) message = tr("Ничего не найдено. В открытых каталогах есть не всё: в основном независимые музыканты, свободные релизы и записи концертов.")
        }
    }

    fun open(item: OnlineCatalog.Item) {
        job?.cancel()
        release = item; releaseTracks = null; busy = true; message = null
        job = scope.launch {
            val tracks = withContext(Dispatchers.IO) { runCatching { g.online.tracksOf(item) } }
            busy = false
            releaseTracks = tracks.getOrDefault(emptyList())
            if (tracks.isFailure) message = tr("Не удалось открыть релиз") else if (releaseTracks.isNullOrEmpty()) message = tr("В этом релизе нет песен в поддерживаемом формате")
        }
    }

    /** Plays [item]; the other playable songs of the same list follow it, like in any list of songs. */
    fun play(item: OnlineCatalog.Item, among: List<OnlineCatalog.Item>) {
        val playable = among.filter { !it.isRelease && it.streamUrl.isNotBlank() }
        val tracks = playable.map { g.library.registerOnline(it) }
        val index = playable.indexOfFirst { it.id == item.id }
        if (index < 0) return
        // the song that is already playing: the button pauses / continues it
        if (player.currentPath == app.malachite.player.data.ONLINE_PREFIX + item.id) { g.player.toggle(); return }
        message = null
        g.player.play(tracks, index)
    }

    fun download(item: OnlineCatalog.Item) = downloadOnline(item) { nav.toast(it) }

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank() && results == null) search() }

    // songs heard before whose whole sound is kept: they play without internet
    val context = androidx.compose.ui.platform.LocalContext.current
    var kept by remember { mutableStateOf<List<OnlineCatalog.Item>>(emptyList()) }
    LaunchedEffect(player.currentPath) {
        kept = withContext(Dispatchers.IO) { runCatching { app.malachite.player.playback.OnlineCache.playableOffline(context) }.getOrDefault(emptyList()) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            placeholder = { Text(tr("Исполнитель, альбом или песня")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { TextButton(onClick = { search() }, enabled = !busy) { Text(tr("Найти")) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        // what to show: everything, or only artists, albums or songs
        if (release == null) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (k in OnlineCatalog.Kind.entries) {
                    androidx.compose.material3.FilterChip(kind == k, { kind = k }, label = { Text(kindTitle(k), maxLines = 1) })
                }
            }
        }
        if (busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.weight(1f))
                TextButton(onClick = { job?.cancel(); busy = false }) { Text(tr("Отмена")) }
            }
        }
        message?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        val opened = release
        val found = results
        val showSongs = kind == OnlineCatalog.Kind.ALL || kind == OnlineCatalog.Kind.SONGS
        val list = if (opened != null) releaseTracks.orEmpty() else if (showSongs) found?.songs.orEmpty() else emptyList()
        // whole songs first, then the excerpts of the big catalogue
        val whole = list.filter { !it.preview }
        val excerpts = list.filter { it.preview }
        // on "everything" a few of the best artists and albums, on their own chip all of them
        val allArtists = remember(found) {
            val f = found
            if (f == null) emptyList() else f.artists.ifEmpty { artistsOf(f.songs, g.online) }
        }
        val artists = when (kind) {
            OnlineCatalog.Kind.ALL -> allArtists.take(3)
            OnlineCatalog.Kind.ARTISTS -> allArtists
            else -> emptyList()
        }
        val albums = when (kind) {
            OnlineCatalog.Kind.ALL -> found?.albums.orEmpty().take(4)
            OnlineCatalog.Kind.ALBUMS -> found?.albums.orEmpty()
            else -> emptyList()
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (opened != null) item {
                Row(Modifier.fillMaxWidth().clickable { release = null; releaseTracks = null; message = null }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад к результатам"))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(opened.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val about = if (opened.isArtist) listOf(tr("Исполнитель"), opened.source)
                        else listOf(opened.artist, opened.source, opened.license)
                        Text(about.filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (opened != null && whole.isNotEmpty()) item {
                DownloadAllRow(opened, whole) { downloadAllOnline(opened.id, opened.title, whole) { nav.toast(it) } }
            }
            // before a search: what was searched for lately, and what was heard and plays without internet
            if (opened == null && results == null && !busy) item {
                app.malachite.player.data.RecentSearches(app.malachite.player.data.SearchHistory.Kind.ONLINE) { query = it; search() }
            }
            if (opened == null && found == null && kept.isNotEmpty()) {
                item { OnlineHeader(tr("Слушали — играют без интернета (${kept.size})")) }
                items(kept, key = { "kept|" + it.id }) { song ->
                    val path = app.malachite.player.data.ONLINE_PREFIX + song.id
                    OnlineRow(
                        item = song, playing = player.currentPath == path, sounding = player.currentPath == path && player.isPlaying,
                        progress = null, done = false,
                        onOpen = {}, onPlay = { play(song, kept) }, onDownload = { download(song) },
                    )
                }
            }
            // artists: open one to see (and download) all their songs
            if (opened == null && artists.isNotEmpty()) {
                item { OnlineHeader(tr("Исполнители")) }
                items(artists, key = { it.id }) { artist -> ArtistRow(artist) { open(artist) } }
                if (kind == OnlineCatalog.Kind.ALL && allArtists.size > artists.size) item {
                    TextButton(onClick = { kind = OnlineCatalog.Kind.ARTISTS }, Modifier.padding(horizontal = 4.dp)) { Text(tr("Все исполнители (${allArtists.size})")) }
                }
            }
            if (opened == null && albums.isNotEmpty()) {
                item { OnlineHeader(tr("Альбомы и плейлисты")) }
                items(albums, key = { it.id }) { album ->
                    OnlineRow(
                        item = album, playing = false, sounding = false, progress = null, done = false,
                        onOpen = { open(album) }, onPlay = {}, onDownload = {},
                    )
                }
                val total = found?.albums.orEmpty().size
                if (kind == OnlineCatalog.Kind.ALL && total > albums.size) item {
                    TextButton(onClick = { kind = OnlineCatalog.Kind.ALBUMS }, Modifier.padding(horizontal = 4.dp)) { Text(tr("Все альбомы ($total)")) }
                }
            }
            if (opened == null && found != null && !found.isEmpty && !busy && artists.isEmpty() && albums.isEmpty() && list.isEmpty()) item {
                Text(tr("Здесь ничего не нашлось — посмотрите на других вкладках поиска."), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (opened == null && results == null && !busy) item {
                Text(
                    tr("Можно писать через запятую: исполнитель, альбом, песня — в любом порядке и любой частью. " +
                        "Точное совпадение не нужно: первыми идут самые похожие."),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (opened == null && results == null && !busy) item {
                Text(
                    tr("Целиком песни есть в открытых каталогах: Audius (музыканты сами выкладывают свои треки; скачать можно то, " +
                        "что автор разрешил) и Internet Archive (открытая библиотека: свободные релизы, записи концертов). " +
                        "Почти любую выпущенную песню найдёт каталог Deezer, но у него можно слушать только отрывок в 30 секунд: " +
                        "целиком такие песни бесплатно и открыто никто не раздаёт. Права на записи принадлежат их авторам."),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (whole.isNotEmpty() && opened == null) item { OnlineHeader(tr("Можно слушать целиком")) }
            items(whole, key = { it.id }) { item ->
                OnlineRow(
                    item = item,
                    playing = player.currentPath == app.malachite.player.data.ONLINE_PREFIX + item.id,
                    sounding = player.isPlaying,
                    progress = downloading[item.id],
                    done = item.id in downloaded,
                    onOpen = { open(item) },
                    onPlay = { play(item, whole) },
                    onDownload = { download(item) },
                )
            }
            val excerptsOpen = showExcerpts || whole.isEmpty()
            if (excerpts.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = whole.isNotEmpty()) { showExcerpts = !showExcerpts }.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Отрывки по 30 секунд (Deezer): ${excerpts.size}"),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    )
                    if (whole.isNotEmpty()) {
                        TextButton(onClick = { showExcerpts = !showExcerpts }) { Text(if (excerptsOpen) tr("Скрыть") else tr("Показать")) }
                    }
                }
            }
            if (excerptsOpen) items(excerpts, key = { it.id }) { item ->
                OnlineRow(
                    item = item,
                    playing = player.currentPath == app.malachite.player.data.ONLINE_PREFIX + item.id,
                    sounding = player.isPlaying,
                    progress = null,
                    done = false,
                    onOpen = {},
                    onPlay = { play(item, excerpts) },
                    onDownload = {},
                )
            }
        }
    }
}

/** Artists to offer when the catalogues listed none: those with several songs among the results. */
private fun artistsOf(songs: List<OnlineCatalog.Item>, catalog: OnlineCatalog): List<OnlineCatalog.Item> =
    songs.filter { !it.preview && it.artistId.isNotEmpty() }
        .groupBy { it.artistId }
        .filter { it.value.size >= 2 }
        .entries.sortedByDescending { it.value.size }
        .mapNotNull { catalog.artist(it.value.first()) }
        .take(5)

@Composable
private fun kindTitle(k: OnlineCatalog.Kind): String = when (k) {
    OnlineCatalog.Kind.ALL -> tr("Всё")
    OnlineCatalog.Kind.ARTISTS -> tr("Исполнители")
    OnlineCatalog.Kind.ALBUMS -> tr("Альбомы")
    OnlineCatalog.Kind.SONGS -> tr("Песни")
}

@Composable
private fun ArtistRow(artist: OnlineCatalog.Item, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            if (artist.coverUrl != null) AsyncImage(artist.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.title + if (artist.verified) " ✓" else "",
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val about = listOfNotNull(
                artist.source,
                artist.count.takeIf { it > 0 }?.let { tr("песен: $it") },
                artist.popularity.takeIf { it > 0 }?.let { tr("слушателей: ${compactCount(it)}") },
            )
            Text(about.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 1234567 → "1.2M", 45300 → "45K". */
private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    else -> n.toString()
}

/** "Download all (N)" for an opened release or artist, or how far the download got. */
@Composable
private fun DownloadAllRow(opened: OnlineCatalog.Item, songs: List<OnlineCatalog.Item>, onStart: () -> Unit) {
    val running = bulk.value
    val open = songs.count { it.downloadUrl != null && it.id !in downloaded }
    val closed = songs.count { it.downloadUrl == null }
    val context = androidx.compose.ui.platform.LocalContext.current
    // about how much it will take on the phone: the catalogue's sizes, or a guess from the length
    val size = remember(songs, downloaded.size) {
        val (bytes, exact) = estimatedBytes(songs.filter { it.downloadUrl != null && it.id !in downloaded })
        if (bytes <= 0) "" else (if (exact) "" else "≈ ") + android.text.format.Formatter.formatShortFileSize(context, bytes)
    }
    // what will happen is said before it starts: how many songs, where they go, what is left out
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("Скачать «${opened.title}»?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Песен: $open. Они скачаются по очереди в главную папку и разложатся по папкам исполнителя и альбома, как остальная музыка."))
                    if (size.isNotEmpty()) Text(tr("Займут примерно: $size"))
                    if (closed > 0) Text(tr("Только для прослушивания (автор не открыл скачивание), пропускаются: $closed."))
                    Text(tr("Загрузку можно остановить; уже скачанные песни останутся."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { asking = false; onStart() }) { Text(tr("Скачать")) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(tr("Отмена")) } },
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (running != null && running.listId == opened.id) {
                Text(tr("Скачано ${running.ok} из ${running.total}"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                OutlinedButton(onClick = { stopDownloadAll() }) { Text(tr("Остановить"), maxLines = 1) }
            } else {
                Button(onClick = { asking = true }, enabled = open > 0 && running == null) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (open > 0) tr("Скачать всё ($open)") else tr("Всё скачано"), maxLines = 1)
                }
                if (open > 0 && size.isNotEmpty()) {
                    Text(size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        if (running != null && running.listId == opened.id) {
            // one stretch per song between the dots; the coloured part is what has come down
            val current = running.currentId?.let { downloading[it] }?.coerceIn(0f, 1f) ?: 0f
            SongsProgress(
                songs = running.total,
                done = (running.ok + running.failed + current).coerceAtMost(running.total.toFloat()),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (closed > 0) {
            Text(
                tr("Песен только для прослушивания (автор не открыл скачивание): $closed"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running != null && running.listId != opened.id) {
            Text(tr("Сейчас скачивается «${running.title}»: ${running.ok} из ${running.total}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bytes [songs] will take, and whether every size was known (else a guess from their length). */
private fun estimatedBytes(songs: List<OnlineCatalog.Item>): Pair<Long, Boolean> {
    var sum = 0L
    var exact = true
    for (t in songs) {
        if (t.sizeBytes > 0) sum += t.sizeBytes
        else {
            exact = false
            // an MP3 of the kind these catalogues give: about 192 kbit/s
            sum += t.durationSec.coerceAtLeast(0) * 192_000L / 8
        }
    }
    return sum to exact
}

/**
 * Progress of "download all", one stretch per song between dots (the first and last dot are the
 * start and the end). The coloured line is what has come down, the pale one what is still to
 * come; a song half downloaded colours half of its stretch. With many songs the dots between
 * become small marks, and vanish when there is no room for them. Colours of the theme.
 */
@Composable
private fun SongsProgress(songs: Int, done: Float, modifier: Modifier = Modifier) {
    val doneColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val dotColor = MaterialTheme.colorScheme.tertiary
    val shown by androidx.compose.animation.core.animateFloatAsState(done, androidx.compose.animation.core.tween(350), label = "songsProgress")
    androidx.compose.foundation.Canvas(modifier.height(16.dp)) {
        if (songs <= 0) return@Canvas
        val dot = 5.dp.toPx()
        val line = 4.dp.toPx()
        val y = size.height / 2
        val left = dot
        val right = size.width - dot
        val step = (right - left) / songs
        val x = left + step * shown.coerceIn(0f, songs.toFloat())
        drawLine(restColor, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), line, androidx.compose.ui.graphics.StrokeCap.Round)
        if (x > left) drawLine(doneColor, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(x, y), line, androidx.compose.ui.graphics.StrokeCap.Round)
        // the dots between songs: full while there is room, smaller marks when crowded, none when packed
        val inner = when {
            step >= dot * 3 -> dot
            step >= dot * 1.2f -> dot * 0.45f
            else -> 0f
        }
        for (k in 0..songs) {
            val r = if (k == 0 || k == songs) dot else inner
            if (r <= 0f) continue
            drawCircle(dotColor, r, androidx.compose.ui.geometry.Offset(left + step * k, y))
        }
    }
}

@Composable
private fun OnlineHeader(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun OnlineRow(
    item: OnlineCatalog.Item,
    playing: Boolean,
    sounding: Boolean,
    progress: Float?,
    done: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable { if (item.isRelease) onOpen() else onPlay() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(if (item.isRelease) Icons.Filled.Album else Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                if (item.coverUrl != null) AsyncImage(item.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(
                    listOfNotNull(
                        item.artist.takeIf { it.isNotBlank() },
                        item.source + when {
                            !item.isRelease -> ""
                            item.releaseType == "album" -> tr(" — альбом")
                            item.releaseType == "playlist" -> tr(" — плейлист")
                            else -> tr(" — релиз")
                        },
                        if (item.isRelease) item.count.takeIf { it > 0 }?.let { tr("песен: $it") } else null,
                        if (item.preview) item.album.takeIf { it.isNotBlank() } else item.durationSec.takeIf { it > 0 }?.let { formatTime(it * 1000L) },
                        item.license.takeIf { it.isNotBlank() },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!item.isRelease && !item.preview && item.downloadUrl == null) {
                    Text(tr("Только слушать: автор не открыл скачивание"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!item.isRelease) {
                IconButton(onClick = onPlay) { Icon(if (playing && sounding) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing && sounding) tr("Пауза") else tr("Слушать")) }
                when {
                    done -> Icon(Icons.Filled.Check, tr("Скачано"), Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                    progress != null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (progress >= 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                    item.downloadUrl != null -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, tr("Скачать в главную папку")) }
                }
            }
        }
    }
}
