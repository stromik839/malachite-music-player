package app.malachite.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import app.malachite.player.MalachiteApp
import app.malachite.player.data.SyncDraft
import app.malachite.player.data.SyncDrafts
import app.malachite.player.data.Tab
import app.malachite.player.heal.HealingEngine
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.settings.Group
import app.malachite.player.ui.settings.Hint
import app.malachite.player.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything that is running or was left unfinished, one group per kind of work: healing,
 * manual lyric syncs, the search for music on the device, downloads. Each line shows how far
 * the work has got; unfinished syncs can be continued from the very word they stopped at.
 */
@Composable
fun ProcessesScreen() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val heal by g.healing.state.collectAsState()
    val scan by g.library.scan.collectAsState()
    val drafts by SyncDrafts.all.collectAsState()
    val downloads = activeDownloads()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SyncDrafts.refresh() } }

    SettingsPage {
        // first: the password protection of the player's own data
        Group(tr("Защита данных плеера")) { ProtectionGroupContent() }
        Group(tr("Исцеление")) {
            if (heal.interrupted.isNotEmpty()) app.malachite.player.ui.heal.InterruptedHealCard(heal.interrupted)
            if (heal.running != null || heal.queued.isNotEmpty()) {
                Text(tr("Выполняется: ${heal.running?.let { HealingEngine.title(it) } ?: "…"}"), fontWeight = FontWeight.SemiBold)
                val p = heal.progress
                LinearProgressIndicator(progress = { if (p != null && p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                p?.let { Text("${it.done} / ${it.total}: ${it.message}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (heal.queued.isNotEmpty()) Text(tr("В очереди: ") + heal.queued.joinToString { HealingEngine.title(it) }, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { nav.openTab(Tab.HEAL) }) { Text(tr("Открыть исцеление")) }
                    TextButton(onClick = { g.healing.cancel() }) { Text(tr("Остановить после текущего файла")) }
                }
            } else if (heal.interrupted.isEmpty()) {
                Hint(tr("Сейчас ничего не исцеляется."))
                TextButton(onClick = { nav.openTab(Tab.HEAL) }) { Text(tr("Открыть исцеление")) }
            }
        }
        Group(tr("Синхронизация текстов")) {
            if (drafts.isEmpty()) Hint(tr("Незаконченных синхронизаций нет. Если уйти с экрана синхронизации, не закончив, работа появится здесь."))
            drafts.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                DraftRow(d)
            }
        }
        Group(tr("Поиск и добавление музыки")) {
            if (scan.running) {
                Text(scan.message.ifBlank { tr("Работаю…") }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f }, modifier = Modifier.fillMaxWidth())
                if (scan.total > 0) Text("${scan.done} / ${scan.total}", style = MaterialTheme.typography.bodySmall)
            } else Hint(tr("Главная папка сейчас не перечитывается."))
        }
        Group(tr("Скачивание из онлайн-раздела")) {
            if (downloads.isEmpty()) Hint(tr("Сейчас ничего не скачивается."))
            downloads.forEach { (id, title, progress) ->
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { stopDownload(id) }) { Text(tr("Остановить")) }
            }
        }
    }
}

@Composable
private fun DraftRow(d: SyncDraft) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    // the file may have been renamed or moved by healing since the draft was saved
    val track = g.library.track(d.path)
        ?: g.library.tracks.collectAsState().value.firstOrNull { it.displayTitle == d.title && it.displayArtist == d.artist }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (d.artist.isNotBlank()) Text(d.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            when {
                d.cursor >= d.total -> tr("Всё отмечено — осталось проверить и сохранить")
                d.wordMode -> tr("Отмечено слов: ${d.cursor} из ${d.total}")
                else -> tr("Отмечено строк: ${d.cursor} из ${d.total}")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = track != null, onClick = {
                val t = track ?: return@Button
                // saved under the old name: carry the draft over to where the song is now
                if (t.path != d.path) { SyncDrafts.save(d.copy(path = t.path)); SyncDrafts.delete(d.path) }
                nav.push(Screen.LyricsSync(t.path, resume = true))
            }) { Text(tr("Продолжить")) }
            TextButton(onClick = { SyncDrafts.delete(d.path) }) { Text(tr("Удалить")) }
        }
        if (track == null) Hint(tr("Песня не найдена в главной папке."))
    }
}
