package app.malachite.player.ui.screens

import app.malachite.core.i18n.tr
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.player.MalachiteApp
import app.malachite.player.data.Converter
import app.malachite.player.data.SongTools
import app.malachite.player.data.Storage
import app.malachite.player.service.LongTaskService
import app.malachite.player.ui.settings.Group
import app.malachite.player.ui.settings.Hint
import app.malachite.player.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files the system's file manager may offer as songs ("application/ogg" is Ogg / Opus). */
private val AUDIO_TYPES = arrayOf("audio/*", "application/ogg", "application/octet-stream")

/**
 * The «Добавить» tab: songs added by hand (a path, or files picked in the file manager) when the
 * player has not noticed them by itself, and songs turned into another format.
 */
@Composable
fun AddScreen() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by g.library.tracks.collectAsState()

    // ---- adding
    var path by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    val pickToAdd = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            adding = true
            added = null
            scope.launch { added = SongTools.addUris(context, uris); adding = false }
        }
    }

    // ---- converting
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<SongTools.Picked?>(null) }
    var about by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Converter.Target?>(null) }
    var fix by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val targets = remember { Converter.available() }
    fun choose(p: SongTools.Picked?) {
        picked = p
        target = null
        fix = false
        about = ""
        error = if (p == null) tr("Такой песни нет: проверьте путь") else null
        result = null
        if (p != null) scope.launch { about = withContext(Dispatchers.IO) { SongTools.describe(p) } }
    }
    val pickToConvert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { choose(SongTools.pickForConverting(context, uri)) }
    }

    SettingsPage {
        Group(tr("Добавить песню")) {
            Hint(tr("Если плеер не заметил новую песню, добавьте её сами: впишите путь к файлу или к папке или выберите файлы в файловом менеджере. Файл копируется в главную папку, оригинал остаётся на месте."))
            OutlinedTextField(
                path, { path = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Путь к песне или к папке")) },
                placeholder = { Text("/storage/emulated/0/Download/") },
                singleLine = true,
            )
            // the usual places of this phone: a tap puts one into the field
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SongTools.knownPlaces(context).forEach { place ->
                    SuggestionChip(onClick = { path = place }, label = { Text(place, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    adding = true
                    added = null
                    scope.launch { added = SongTools.addPath(context, path); adding = false }
                }, enabled = !adding && path.isNotBlank()) { Text(tr("Добавить по пути")) }
                OutlinedButton(onClick = { pickToAdd.launch(AUDIO_TYPES) }, enabled = !adding) { Text(tr("Выбрать файлы…")) }
            }
            if (adding) LinearProgressIndicator(Modifier.fillMaxWidth())
            added?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!Storage.hasAllFilesAccess(context)) {
                Hint(tr("Чтобы добавлять по пути, плееру нужен «Доступ ко всем файлам». Кнопка «Выбрать файлы…» работает и без него."))
                StoragePermissionButton()
            }
            Hint(tr("Пути бывают такими: память телефона — /storage/emulated/0/ (то же самое — /sdcard/), загрузки — /storage/emulated/0/Download/, SD-карта — /storage/1234-ABCD/ (у каждой карты своё имя). Можно короче: «Download/песня.mp3». Папка — добавятся все песни из неё."))
        }

        Group(tr("Перевести в другой формат")) {
            Hint(tr("Выберите песню — из библиотеки, файлом с телефона или по пути выше — и формат. Песня из библиотеки заменяется новой, старый файл уходит в корзину главной папки. Файл не из библиотеки остаётся на месте, а в главную папку добавляется переведённая копия. Если что-то пойдёт не так, ничего не меняется, а здесь будет написано почему."))
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Найти песню в библиотеке")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            val matches = remember(query, tracks) {
                if (query.trim().length < 2) emptyList() else {
                    val q = query.trim()
                    tracks.asSequence().filter {
                        it.displayTitle.contains(q, true) || it.displayArtist.contains(q, true) || it.fileName.contains(q, true)
                    }.take(8).toList()
                }
            }
            matches.forEach { t ->
                Column(Modifier.fillMaxWidth().clickable { choose(SongTools.pick(t.path)); query = "" }.padding(vertical = 6.dp)) {
                    Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.displayArtist + " • " + t.fileName.substringAfterLast('.', "").uppercase(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickToConvert.launch(AUDIO_TYPES) }, enabled = !working) { Text(tr("Файл с телефона…")) }
                OutlinedButton(onClick = { choose(SongTools.pick(SongTools.resolve(path).absolutePath)) }, enabled = !working && path.isNotBlank()) { Text(tr("Взять путь выше")) }
            }
            picked?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (p.libraryPath != null) tr("Песня из библиотеки") else tr("Файл не из библиотеки"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(tr("Во что перевести"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    targets.forEach { tg ->
                        FilterChip(target == tg && !fix, { target = tg; fix = false }, label = { Text(Converter.title(tg)) })
                    }
                    SongTools.wrongExtension(p)?.let { ext ->
                        FilterChip(fix, { fix = true; target = null }, label = { Text(tr("Только исправить расширение → .$ext")) })
                    }
                }
                if (Converter.Target.FLAC !in targets) Hint(tr("FLAC недоступен: в этом телефоне нет его кодировщика."))
                Hint(tr("MP3 не предлагается: в Android нет кодировщика MP3. M4A (AAC) звучит так же хорошо и занимает меньше места."))
                Button(onClick = {
                    val chosen = target
                    working = true
                    result = null
                    error = null
                    progress = 0f
                    LongTaskService.start(context, tr("Перевод песни в другой формат"))
                    scope.launch {
                        if (fix) {
                            result = SongTools.fixExtension(p)
                        } else if (chosen != null) {
                            SongTools.convert(context, p, chosen) { f ->
                                progress = f
                                LongTaskService.progress(context, p.name, (f * 100).toInt(), 100)
                            }.onSuccess { result = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
                        }
                        LongTaskService.stop(context)
                        working = false
                        // the song has a new file now: it is chosen again from its new place
                        if (error == null) { picked = null; about = "" }
                    }
                }, enabled = !working && (target != null || fix)) { Text(if (fix) tr("Исправить") else tr("Перевести")) }
            }
            if (working) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let {
                Text(tr("Не получилось: $it"), color = MaterialTheme.colorScheme.error)
                if (picked != null) Hint(tr("Исходный файл не тронут."))
            }
        }
    }
}
