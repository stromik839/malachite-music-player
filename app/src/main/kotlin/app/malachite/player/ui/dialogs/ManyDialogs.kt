package app.malachite.player.ui.dialogs

import app.malachite.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.malachite.core.audio.TagEdit
import app.malachite.core.library.TrackInfo
import app.malachite.player.MalachiteApp
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.components.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chosen songs go to the player's trash (they can be brought back from there). */
@Composable
fun DeleteManyDialog(tracks: List<TrackInfo>, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    // "stop": the song being moved is finished, the rest stay where they are
    val stop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(tr("Удалить выбранные песни (${tracks.size})?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Файлы перемещаются в корзину плеера (.malachite/trash) — их можно вернуть, пока корзина не очищена."))
                if (busy) {
                    LinearProgressIndicator(progress = { done.toFloat() / tracks.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("$done / ${tracks.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    var failed = 0
                    withContext(Dispatchers.IO) {
                        for (t in tracks) {
                            if (stop.get()) break
                            val f = g.library.file(t)
                            val ok = f != null && g.library.safe?.trash(f, g.library.root)?.isOk == true
                            if (!ok) failed++
                            withContext(Dispatchers.Main) { done++ }
                        }
                        g.library.refreshTracks(tracks.map { it.path })
                    }
                    busy = false
                    nav.toast(
                        when {
                            stop.get() -> tr("Остановлено: в корзине ${done - failed} из ${tracks.size}")
                            failed == 0 -> tr("Перемещено в корзину: ${tracks.size}")
                            else -> tr("В корзине: ${tracks.size - failed}, не удалось: $failed")
                        },
                    )
                    close()
                }
            }) { Text(tr("Удалить")) }
        },
        dismissButton = {
            if (busy) TextButton(onClick = { stop.set(true) }) { Text(tr("Остановить")) }
            else TextButton(onClick = close) { Text(tr("Отмена")) }
        },
    )
}

/** What the chosen songs are, all together. */
@Composable
fun InfoManyDialog(tracks: List<TrackInfo>, close: () -> Unit) {
    val data = MalachiteApp.graph.library.data.value
    val formats = remember(tracks) {
        tracks.groupingBy { it.format.ifBlank { it.extension.uppercase() } }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ${it.value}" }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Сведения о выбранном")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                @Composable fun row(k: String, v: String) = Text("$k: $v", style = MaterialTheme.typography.bodyMedium)
                row(tr("Песен"), tracks.size.toString())
                row(tr("Длительность"), formatTime(tracks.sumOf { it.durationMs }))
                row(tr("Размер"), tr("%.1f МБ").format(tracks.sumOf { it.size } / 1048576.0))
                row(tr("Форматы"), formats)
                row(tr("Исполнителей"), tracks.map { it.groupArtist.lowercase() }.filter { it.isNotBlank() }.distinct().size.toString())
                row(tr("Альбомов"), tracks.map { it.album.lowercase() }.filter { it.isNotBlank() }.distinct().size.toString())
                row(tr("С обложкой"), tracks.count { it.hasArtwork }.toString())
                row(tr("С текстом"), tracks.count { data.user(it.path).lyricsId != null }.toString())
                row(tr("В избранном"), tracks.count { data.user(it.path).favorite }.toString())
                row(tr("Без альбома"), tracks.count { it.album.isBlank() }.toString())
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("OK") } },
    )
}

/**
 * The same artist, album, album artist, year or genre written into all chosen songs. A field
 * shows the value when all songs share it; a field left as it was changes nothing, a shared value
 * cleared is erased.
 */
@Composable
fun EditTagsManyDialog(tracks: List<TrackInfo>, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    fun shared(f: (TrackInfo) -> String) = tracks.map(f).distinct().singleOrNull().orEmpty()
    val start = remember(tracks) {
        listOf(shared { it.artist }, shared { it.album }, shared { it.albumArtist }, shared { it.year }, shared { it.genre })
    }
    var artist by remember { mutableStateOf(start[0]) }
    var album by remember { mutableStateOf(start[1]) }
    var albumArtist by remember { mutableStateOf(start[2]) }
    var year by remember { mutableStateOf(start[3]) }
    var genre by remember { mutableStateOf(start[4]) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    // "stop": the song being written is finished (it is never left half-written), the rest stay as they were
    val stop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val differs = tr("у песен разное — оставить")
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(tr("Теги у выбранных (${tracks.size})")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable fun field(v: String, set: (String) -> Unit, label: String, first: String) = OutlinedTextField(
                    v, set, label = { Text(label) }, singleLine = true, enabled = !busy,
                    placeholder = if (first.isEmpty()) ({ Text(differs) }) else null,
                )
                field(artist, { artist = it }, tr("Исполнитель"), start[0])
                field(album, { album = it }, tr("Альбом"), start[1])
                field(albumArtist, { albumArtist = it }, tr("Исполнитель альбома"), start[2])
                field(year, { year = it }, tr("Год"), start[3])
                field(genre, { genre = it }, tr("Жанр"), start[4])
                Text(
                    tr("Пишется только то, что вы изменили. Файлы переедут в папки нового исполнителя и альбома."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    LinearProgressIndicator(progress = { done.toFloat() / tracks.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("$done / ${tracks.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                fun ch(new: String, first: String) = new.trim().takeIf { it != first.trim() }
                val edit = TagEdit(
                    artist = ch(artist, start[0]), album = ch(album, start[1]), albumArtist = ch(albumArtist, start[2]),
                    year = ch(year, start[3]), genre = ch(genre, start[4]),
                )
                if (edit.isEmpty) { close(); return@TextButton }
                busy = true
                scope.launch {
                    var failed = 0
                    val moves = HashMap<String, String>()
                    for (t in tracks) {
                        if (stop.get()) break
                        g.library.editTags(t, edit).onSuccess { p -> if (p != t.path) moves[t.path] = p }.onFailure { failed++ }
                        done++
                    }
                    if (moves.isNotEmpty()) g.player.remapQueue(moves)
                    busy = false
                    nav.toast(
                        when {
                            stop.get() -> tr("Остановлено: записано ${done - failed} из ${tracks.size}")
                            failed == 0 -> tr("Теги записаны: ${tracks.size}")
                            else -> tr("Записано: ${tracks.size - failed}, не получилось: $failed")
                        },
                    )
                    close()
                }
            }) { Text(tr("Записать")) }
        },
        dismissButton = {
            if (busy) TextButton(onClick = { stop.set(true) }) { Text(tr("Остановить")) }
            else TextButton(onClick = close) { Text(tr("Отмена")) }
        },
    )
}

/** Healing for the chosen songs only: each step by itself or all at once. */
@Composable
fun HealSelectedDialog(paths: List<String>, close: () -> Unit) = app.malachite.player.ui.heal.HealChoiceDialog(paths, close)
