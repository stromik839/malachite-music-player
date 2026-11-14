package app.malachite.player.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import app.malachite.core.lyrics.LyricLine
import app.malachite.core.lyrics.Lyrics
import app.malachite.core.lyrics.LyricsEdit
import app.malachite.player.MalachiteApp
import app.malachite.player.data.SyncDraft
import app.malachite.player.data.SyncDrafts
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.components.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Corrections of synced lyrics without syncing the whole song again: fix the words of a line,
 * add or remove a line, or sync again from one line onward – everything before it keeps its time.
 */
@Composable
fun LyricsEditScreen(path: String) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val t = g.library.track(path) ?: run { Text(tr("Песня не найдена"), Modifier.padding(16.dp)); return }
    var original by remember { mutableStateOf<Lyrics?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var changed by remember { mutableStateOf(false) }
    /** The line whose words are being fixed; for a new line: the line it goes after (and [adding]). */
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var resyncFrom by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        original = withContext(Dispatchers.IO) { g.library.loadLyrics(t) }
        lines = original?.lines.orEmpty()
        loaded = true
    }
    val l = original
    if (!loaded) return
    if (l == null || l.lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("У этой песни пока нет сохранённого текста."))
            Button(onClick = { nav.back(); nav.push(Screen.LyricsSync(t.path)) }) { Text(tr("Синхронизировать текст вручную")) }
        }
        return
    }
    val wordMode = lines.any { it.words.isNotEmpty() }

    fun save() {
        val fixed = l.copy(lines = lines.sortedBy { it.startMs }, source = l.source)
        changed = false
        g.library.saveLyricsInBackground(t, fixed, tr("исправлено вручную")) { ok ->
            if (ok) nav.toast(tr("Текст сохранён")) else { changed = true; nav.toast(tr("Не удалось сохранить текст")) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            tr("Нажмите на строку, чтобы исправить в ней слова. Если число слов не меняется, время каждого слова остаётся прежним. " +
                "Через меню строки её можно удалить, добавить новую или синхронизировать заново только с этого места."),
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(lines, key = { i, line -> "$i|${line.startMs}" }) { i, line ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { adding = false; editing = i }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(line.startMs + l.offsetMs), Modifier.width(56.dp),
                        style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(line.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Ещё")) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Исправить слова")) }, onClick = { menu = false; adding = false; editing = i })
                            DropdownMenuItem(text = { Text(tr("Добавить строку после")) }, onClick = { menu = false; adding = true; editing = i })
                            if (i == 0) DropdownMenuItem(text = { Text(tr("Добавить строку перед")) }, onClick = { menu = false; adding = true; editing = -1 })
                            DropdownMenuItem(text = { Text(tr("Удалить строку")) }, onClick = {
                                menu = false
                                lines = LyricsEdit.delete(lines, i)
                                changed = true
                            })
                            DropdownMenuItem(text = { Text(tr("Синхронизировать заново с этой строки")) }, onClick = { menu = false; resyncFrom = i })
                            DropdownMenuItem(text = { Text(tr("Перейти сюда в песне")) }, onClick = {
                                menu = false
                                if (g.player.state.value.currentPath != t.path) g.player.play(listOf(t))
                                g.player.seekTo(line.startMs + l.offsetMs)
                            })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = changed, onClick = { lines = l.lines; changed = false }) { Text(tr("Отменить изменения")) }
            Button(enabled = changed, onClick = { save() }, modifier = Modifier.weight(1f)) { Text(tr("Сохранить")) }
        }
    }

    // ---- fixing the words of a line, or a new line
    editing?.let { i ->
        val line = lines.getOrNull(i)
        var text by remember(i, adding) { mutableStateOf(if (adding) "" else line?.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (adding) tr("Новая строка") else tr("Исправить слова")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tr("Текст строки")) })
                    if (adding) {
                        Text(tr("Строка получит время между соседними. Чтобы оно было точным, синхронизируйте с неё заново."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (line != null && LyricsEdit.timingGuessed(line, text)) {
                        Text(
                            tr("Слов стало ${LyricsEdit.words(text).size} вместо ${line.words.size}: время слов в этой строке будет расставлено примерно. Для точности синхронизируйте с этой строки заново."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    } else if (line != null && line.words.isNotEmpty()) {
                        Text(tr("Число слов то же — время каждого слова сохранится."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = LyricsEdit.words(text).isNotEmpty(), onClick = {
                    lines = if (adding) LyricsEdit.insertAfter(lines, i, text)
                    else lines.mapIndexed { j, ln -> if (j == i) LyricsEdit.retext(ln, text, lines.getOrNull(j + 1)?.startMs) else ln }
                    changed = true
                    editing = null
                }) { Text(tr("Готово")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Отмена")) } },
        )
    }

    // ---- syncing again from one line: what is before it stays as it is
    resyncFrom?.let { from ->
        AlertDialog(
            onDismissRequest = { resyncFrom = null },
            title = { Text(tr("Синхронизировать заново с этой строки?")) },
            text = {
                Text(tr("Строки до неё сохранят своё время. С неё и до конца песни вы отметите время заново — песня начнётся чуть раньше этой строки. Несохранённые исправления войдут в синхронизацию."))
            },
            confirmButton = {
                TextButton(onClick = {
                    resyncFrom = null
                    val r = LyricsEdit.resyncFrom(lines, l.offsetMs, from, wordMode)
                    val settings = g.settings.value
                    val draft = SyncDraft(
                            path = t.path, title = t.displayTitle, artist = t.displayArtist, text = r.lines.joinToString("\n"),
                            removeParens = false, wordMode = r.wordMode, speed = 0.75f, reactionMs = settings.syncReactionMs,
                            cursor = r.saved.cursor, total = r.saved.starts.size, starts = r.saved.starts, ends = r.saved.ends,
                            updatedAt = System.currentTimeMillis(), lines = r.lines,
                        )
                    // written before the sync screen opens: it reads the draft at once
                    scope.launch {
                        withContext(Dispatchers.IO) { SyncDrafts.saveNow(draft) }
                        nav.back()
                        nav.push(Screen.LyricsSync(t.path, resume = true))
                    }
                }) { Text(tr("Начать")) }
            },
            dismissButton = { TextButton(onClick = { resyncFrom = null }) { Text(tr("Отмена")) } },
        )
    }
}
