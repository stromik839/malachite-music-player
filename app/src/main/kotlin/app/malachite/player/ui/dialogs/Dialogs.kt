package app.malachite.player.ui.dialogs

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import app.malachite.core.i18n.tr
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.TagEdit
import app.malachite.core.cover.CoverFinder
import app.malachite.core.library.TrackInfo
import app.malachite.core.lyrics.Lrc
import app.malachite.core.lyrics.LyricsCleaner
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.player.MalachiteApp
import app.malachite.player.data.AndroidImagePrep
import app.malachite.player.data.CoverLoader
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.components.formatTime
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DialogHost() {
    val nav = LocalNavigator.current
    val d = nav.dialog ?: return
    val close = { nav.dialog = null }
    when (d) {
        is DialogRequest.AddToPlaylist -> AddToPlaylistDialog(d.paths, close)
        is DialogRequest.EditTags -> TagEditorDialog(d.track, close)
        is DialogRequest.FindCover -> CoverSearchDialog(d.tracks, close)
        is DialogRequest.FindLyrics -> LyricsSearchDialog(d.track, close)
        is DialogRequest.Delete -> DeleteDialog(d.track, close)
        is DialogRequest.Info -> InfoDialog(d.track, close)
        is DialogRequest.DeleteMany -> DeleteManyDialog(d.tracks, close)
        is DialogRequest.InfoMany -> InfoManyDialog(d.tracks, close)
        is DialogRequest.EditTagsMany -> EditTagsManyDialog(d.tracks, close)
        is DialogRequest.HealSelected -> HealSelectedDialog(d.paths, close)
    }
}

@Composable
private fun AddToPlaylistDialog(paths: List<String>, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val playlists by g.library.playlists.collectAsState()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Добавить в плейлист")) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(playlists) { p ->
                        // a row that looks like a button: it is clear what is tapped and that it can be
                        androidx.compose.material3.Surface(
                            onClick = { g.library.addToPlaylist(p.name, paths); nav.toast(tr("Добавлено в «${p.name}»")); close() },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(tr("${p.entries.size} песен"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, tr("Добавить в плейлист"), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (playlists.isNotEmpty()) Text(tr("или создайте новый:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(name, { name = it }, label = { Text(tr("Новый плейлист")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { g.library.createPlaylist(name, paths); nav.toast(tr("Создан «$name»")); close() }) { Text(tr("Создать")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Отмена")) } },
    )
}

@Composable
private fun TagEditorDialog(t: TrackInfo, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(t.title) }
    var artist by remember { mutableStateOf(t.artist) }
    var album by remember { mutableStateOf(t.album) }
    var albumArtist by remember { mutableStateOf(t.albumArtist) }
    var year by remember { mutableStateOf(t.year) }
    var genre by remember { mutableStateOf(t.genre) }
    var track by remember { mutableStateOf(if (t.track > 0) t.track.toString() else "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val format = runCatching { AudioFormat.valueOf(t.format) }.getOrDefault(AudioFormat.UNKNOWN)
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(tr("Теги файла")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!format.taggable) Text(tr("Формат ${format.name} не поддерживает безопасную запись тегов."), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(title, { title = it }, label = { Text(tr("Название")) }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(tr("Исполнитель")) }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text(tr("Альбом")) }, singleLine = true)
                OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text(tr("Исполнитель альбома")) }, singleLine = true)
                OutlinedTextField(year, { year = it }, label = { Text(tr("Год")) }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, label = { Text(tr("Жанр")) }, singleLine = true)
                OutlinedTextField(track, { track = it.filter { c -> c.isDigit() } }, label = { Text(tr("Номер трека")) }, singleLine = true)
                Text(tr("Изменения пишутся в сам файл через проверенную копию. Файл переместится в папку нового исполнителя/альбома."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && format.taggable, onClick = {
                fun ch(new: String, old: String) = new.trim().takeIf { it != old }
                val edit = TagEdit(
                    title = ch(title, t.title), artist = ch(artist, t.artist), album = ch(album, t.album),
                    albumArtist = ch(albumArtist, t.albumArtist), year = ch(year, t.year), genre = ch(genre, t.genre),
                    track = ch(track, if (t.track > 0) t.track.toString() else ""),
                )
                if (edit.isEmpty) { close(); return@TextButton }
                busy = true
                scope.launch {
                    val r = g.library.editTags(t, edit)
                    busy = false
                    r.onSuccess { newPath ->
                        if (newPath != t.path) g.player.remapQueue(mapOf(t.path to newPath))
                        nav.toast(tr("Теги сохранены")); close()
                    }.onFailure { error = tr("Не сохранено: ${it.message}") }
                }
            }) { Text(tr("Сохранить")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(tr("Отмена")) } },
    )
}

/**
 * Searches covers by artist / album / title from the file's own tags (with swapped and
 * transliterated variants), shows the candidates and embeds the chosen one into the files.
 */
@Composable
private fun CoverSearchDialog(tracks: List<TrackInfo>, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val first = tracks.first()
    var artist by remember { mutableStateOf(first.groupArtist) }
    var album by remember { mutableStateOf(first.album) }
    // the title can be corrected too: a messy title is the usual reason nothing is found
    var title by remember { mutableStateOf(app.malachite.core.text.Watermarks.searchTitle(first.displayTitle, first.groupArtist)) }
    var results by remember { mutableStateOf<List<CoverFinder.Candidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // true only while the picture is being written into the files (a few seconds, must not be cut short)
    var writing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun search() {
        busy = true; status = null
        scope.launch {
            // without a connection: say so and wait for it instead of spinning
            app.malachite.player.data.Net.whenOnline({ waiting -> status = if (waiting) tr("Нет интернета. Поиск начнётся сам, как только появится связь.") else null }) {}
            results = withContext(Dispatchers.IO) {
                runCatching {
                    // a link left in the file by the downloader (YouTube…) gives the picture of this very recording
                    val link = if (tracks.size == 1) g.library.file(first)?.let { f -> app.malachite.core.audio.TagIO.read(f)?.comment }.orEmpty() else ""
                    g.coverFinder.search(artist, album, title, durationMs = if (tracks.size == 1) first.durationMs else 0, link = link)
                }.getOrDefault(emptyList())
            }
            busy = false
            if (results.isNullOrEmpty()) status = tr("Ничего не найдено. Попробуйте исправить исполнителя, альбом или название (можно латиницей или кириллицей) — или выберите свою картинку.")
        }
    }
    LaunchedEffect(Unit) { search() }

    // The user's own picture goes through exactly the same steps as a found one:
    // prepared, written into the file itself, checked that the music did not change.
    // Before it is written, the user chooses by hand which part of the picture becomes the cover.
    var cropping by remember { mutableStateOf<ByteArray?>(null) }
    fun embedOwn(bytes: ByteArray) {
        busy = true; writing = true; status = tr("Вшиваю вашу картинку…")
        scope.launch {
            val art = withContext(Dispatchers.IO) { AndroidImagePrep.prepare(bytes, false) }
            if (art == null) { busy = false; writing = false; status = tr("Этот файл не читается как картинка"); return@launch }
            val n = g.library.embedCover(tracks.map { it.path }, art, tr("своя картинка"))
            CoverLoader.invalidate()
            busy = false; writing = false
            nav.toast(tr("Обложка вшита в файлов: $n из ${tracks.size}"))
            close()
        }
    }
    val pickOwn = app.malachite.player.ui.components.rememberImagePicker { bytes ->
        if (bytes == null) status = tr("Не удалось прочитать картинку") else cropping = bytes
    }
    cropping?.let { picked ->
        app.malachite.player.ui.components.CropDialog(
            picked, onDismiss = { cropping = null }, onUseWhole = { cropping = null; embedOwn(picked) },
        ) { cut -> cropping = null; embedOwn(cut) }
    }

    AlertDialog(
        // closing during the search simply drops the search (opened by mistake – leave at once)
        onDismissRequest = { if (!writing) close() },
        title = { Text(if (tracks.size > 1) tr("Обложка для ${tracks.size} песен") else tr("Обложка")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = pickOwn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Выбрать свою картинку"))
                }
                if (first.hasArtwork) androidx.compose.material3.OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val current = withContext(Dispatchers.IO) { g.library.file(first)?.let { f -> runCatching { app.malachite.core.audio.TagIO.readArtwork(f) }.getOrNull() } }
                        if (current == null) status = tr("Не удалось прочитать картинку") else cropping = current
                    }
                }) { Text(tr("Обрезать текущую обложку")) }
                Text(tr("Или найдите в интернете:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(artist, { artist = it }, label = { Text(tr("Исполнитель")) }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text(tr("Альбом (пусто — искать по песне)")) }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text(tr("Название песни")) }, singleLine = true)
                TextButton(onClick = { search() }, enabled = !busy) { Text(tr("Искать снова")) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val list = results.orEmpty()
                if (list.isNotEmpty()) {
                    Text(tr("Нажмите на подходящую — она будет вшита в сам файл"), style = MaterialTheme.typography.bodySmall)
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(220.dp)) {
                        items(list) { c ->
                            Column(Modifier.padding(4.dp).clickable(enabled = !busy) {
                                busy = true; writing = true; status = tr("Загружаю и вшиваю…")
                                scope.launch {
                                    val art = withContext(Dispatchers.IO) { g.coverFinder.download(c)?.let { AndroidImagePrep.prepare(it, c.square) } }
                                    if (art == null) { busy = false; writing = false; status = tr("Не удалось загрузить картинку"); return@launch }
                                    val n = g.library.embedCover(tracks.map { it.path }, art, c.source)
                                    CoverLoader.invalidate()
                                    busy = false; writing = false
                                    nav.toast(tr("Обложка вшита в файлов: $n из ${tracks.size}"))
                                    close()
                                }
                            }) {
                                AsyncImage(
                                    model = c.previewUrl, contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                        .border(if (c.score >= CoverFinder.AUTO_THRESHOLD) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(c.album, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text("${c.source} • ${(c.score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !writing, onClick = close) { Text(if (busy && !writing) tr("Отмена") else tr("Закрыть")) } },
    )
}

@Composable
private fun LyricsSearchDialog(t: TrackInfo, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val settings by g.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(t.displayTitle) }
    var artist by remember { mutableStateOf(t.displayArtist) }
    var results by remember { mutableStateOf<List<LyricsFinder.Candidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var own by remember { mutableStateOf(false) }
    var ownText by remember { mutableStateOf("") }

    var offline by remember { mutableStateOf(false) }
    fun search() {
        busy = true
        scope.launch {
            app.malachite.player.data.Net.whenOnline({ offline = it }) {}
            results = withContext(Dispatchers.IO) {
                runCatching {
                    g.lyricsFinder.search(title, artist, t.album, t.durationMs, LyricsCleaner.Options(settings.lyricsRemoveAllParentheses))
                }.getOrDefault(emptyList())
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { search() }

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(if (own) tr("Свой текст") else tr("Текст песни")) },
        text = {
            if (own) {
                Column {
                    OutlinedTextField(ownText, { ownText = it }, Modifier.fillMaxWidth().height(260.dp),
                        label = { Text(tr("Вставьте текст или LRC")) })
                    Text(tr("Пометки вроде [Припев] или (Куплет 1) будут убраны автоматически."), style = MaterialTheme.typography.bodySmall)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(tr("Название")) }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(tr("Исполнитель")) }, singleLine = true)
                Row {
                    TextButton(onClick = { search() }, enabled = !busy) { Text(tr("Искать")) }
                    TextButton(onClick = { own = true }) { Text(tr("Вставить свой")) }
                }
                if (offline) Text(tr("Нет интернета. Поиск начнётся сам, как только появится связь."), style = MaterialTheme.typography.bodySmall)
                if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                val list = results.orEmpty()
                if (!busy && results != null && list.isEmpty()) Text(tr("Не найдено. Можно вставить текст вручную и синхронизировать его кнопкой."))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list) { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                // picked by hand: healing never swaps it for another text
                                withContext(Dispatchers.IO) { g.library.saveLyrics(t, c.lyrics, c.source + tr(", выбран вручную")) }
                                nav.toast(tr("Текст сохранён")); close()
                            }
                        }.padding(vertical = 8.dp)) {
                            Text("${c.artist} — ${c.title}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                (if (c.synced) tr("синхронизированный") else tr("без времени")) + " • " + c.source +
                                    (if (c.durationSec > 0) " • " + formatTime((c.durationSec * 1000).toLong()) else "") +
                                    tr(" • совпадение ${(c.score * 100).toInt()}%") +
                                    (if (c.foreignLetters) tr(" • другим алфавитом") else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(c.lyrics.lines.take(2).joinToString(" / ") { it.text }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            if (own) TextButton(enabled = ownText.isNotBlank(), onClick = {
                val l = Lrc.parse(ownText, tr("вручную"))
                scope.launch {
                    withContext(Dispatchers.IO) { g.library.saveLyrics(t, l, tr("вручную")) }
                    nav.toast(if (l.synced) tr("Сохранён синхронизированный текст") else tr("Текст сохранён — его можно синхронизировать"))
                    close()
                }
            }) { Text(tr("Сохранить")) }
        },
        dismissButton = { TextButton(onClick = { if (own) own = false else close() }) { Text(if (own) tr("Назад") else tr("Закрыть")) } },
    )
}

@Composable
private fun DeleteDialog(t: TrackInfo, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Удалить «${t.displayTitle}»?")) },
        text = { Text(tr("Файл перемещается в корзину плеера (.malachite/trash) — его можно вернуть, пока корзина не очищена.")) },
        confirmButton = {
            TextButton(onClick = {
                val f = g.library.file(t)
                val res = f?.let { g.library.safe?.trash(it, g.library.root) }
                g.library.refreshTrack(t.path)
                nav.toast(if (res?.isOk == true) tr("Перемещено в корзину") else tr("Не удалось удалить"))
                close()
            }) { Text(tr("Удалить")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Отмена")) } },
    )
}

@Composable
private fun InfoDialog(t: TrackInfo, close: () -> Unit) {
    val g = MalachiteApp.graph
    val data by g.library.data.collectAsState()
    val u = data.user(t.path)
    val f = g.library.file(t)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Сведения")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                @Composable fun row(k: String, v: String) {
                    if (v.isNotBlank()) Text("$k: $v", style = MaterialTheme.typography.bodyMedium)
                }
                row(tr("Путь"), f?.absolutePath.orEmpty())
                row(tr("Формат"), t.format + " (." + t.extension + ")")
                row(tr("Длительность"), formatTime(t.durationMs))
                row(tr("Битрейт"), if (t.bitrateKbps > 0) tr("${t.bitrateKbps} кбит/с") else "")
                row(tr("Частота"), if (t.sampleRate > 0) tr("${t.sampleRate} Гц") else "")
                row(tr("Размер"), tr("%.1f МБ").format(t.size / 1048576.0))
                row(tr("Обложка в файле"), if (t.hasArtwork) tr("есть") else tr("нет"))
                row(tr("Прослушиваний"), u.playCount.toString())
                row(tr("Текст"), u.lyricsSource ?: if (u.noLyrics) tr("нет слов") else tr("нет"))
                row(tr("Импортирован из"), u.importedFrom.orEmpty())
                row(tr("Обложка взята из"), u.coverSource.orEmpty())
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("OK") } },
    )
}
