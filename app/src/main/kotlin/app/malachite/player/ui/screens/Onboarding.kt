package app.malachite.player.ui.screens

import app.malachite.core.i18n.tr
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.library.ImportFilter
import app.malachite.core.library.LibraryStore
import app.malachite.player.MalachiteApp
import app.malachite.player.data.DeviceScanner
import app.malachite.player.data.Storage
import app.malachite.player.service.LongTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Keeps asking the system whether storage access was granted (after returning from Settings). */
@Composable
fun rememberStorageGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Storage.hasAllFilesAccess(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            granted = Storage.hasAllFilesAccess(context)
            delay(1000)
        }
    }
    return granted
}

@Composable
fun StoragePermissionButton() {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Button(onClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                settingsLauncher.launch(Storage.allFilesAccessIntent(context))
            } catch (e: Exception) {
                Storage.allFilesAccessFallbackIntent()?.let { runCatching { settingsLauncher.launch(it) } }
            }
        } else legacyLauncher.launch(Storage.legacyPermissions())
    }) { Text(tr("Выдать доступ к файлам")) }
}

@Composable
fun OnboardingScreen() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val settings by g.settings.state.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    val granted = rememberStorageGranted()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var path by remember { mutableStateOf(settings.mainFolder.ifEmpty { Storage.defaultMainFolder().absolutePath }) }
    var error by remember { mutableStateOf<String?>(null) }

    // on tablets and laptops the text keeps a readable width instead of stretching edge to edge
    val side = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 680) / 2).coerceAtLeast(0).dp
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(horizontal = side).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LibraryMusic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column {
                Text("Malachite", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(tr("Офлайн-плеер без рекламы и лишнего"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (step) {
            0 -> StepCard(tr("Одна главная папка для всей музыки")) {
                Text(
                    tr("Плеер соберёт все песни с телефона в одну главную папку и разложит их так:\n\n" +
                        "Главная папка → Исполнитель → Альбом → песни\n\n" +
                        "Это нужно для точного анализа, «исцеления» названий и обложек и для простого переноса на другой телефон " +
                        "(папку можно запаковать в ZIP). В плеере показываются только песни из главной папки.\n\n" +
                        "Голосовые сообщения мессенджеров, записи диктофона, рингтоны и звуки уведомлений в плеер не попадают."),
                )
                Text(tr("Это предупреждение показывается один раз."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { step = 1 }) { Text(tr("Понятно")) }
            }
            1 -> StepCard(tr("Разрешения")) {
                Text(tr("Чтобы находить музыку по всему телефону, переносить её в главную папку и вшивать в файлы обложки и теги, нужен «Доступ ко всем файлам»."))
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp)); Text(tr("Доступ к файлам выдан"))
                    }
                } else StoragePermissionButton()
                if (Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(tr("Разрешить уведомления (плеер и прогресс)")) }
                }
                Button(onClick = { step = 2 }, enabled = granted) { Text(tr("Дальше")) }
            }
            2 -> StepCard(tr("Где будет главная папка")) {
                val existing = remember(granted) { if (granted) Storage.locateMainFolder(context, settings.mainFolder) else null }
                if (existing != null) {
                    Text(tr("Найдена существующая главная папка Malachite (например, перенесённая с другого телефона):\n${existing.absolutePath}"))
                    Button(onClick = { path = existing.absolutePath }) { Text(tr("Использовать её")) }
                }
                OutlinedTextField(path, { path = it; error = null }, Modifier.fillMaxWidth(), label = { Text(tr("Путь к главной папке")) }, singleLine = true)
                Text(tr("Можно указать полный путь (/storage/emulated/0/Music/Моя музыка) или только имя папки во внутренней памяти."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val f = Storage.resolveUserPath(path)
                    if (!f.exists() && !f.mkdirs()) { error = tr("Не удалось создать папку: ${f.absolutePath}"); return@Button }
                    val st = Storage.check(context, f.absolutePath)
                    if (st != Storage.FolderStatus.OK) { error = st.message; return@Button }
                    val restored = LibraryStore.isLibraryRoot(f) && g.settings.restoreFrom(f)
                    path = f.absolutePath
                    g.settings.update { it.copy(mainFolder = f.absolutePath) }
                    if (restored) g.settings.update { it.copy(mainFolder = f.absolutePath) }
                    step = 3
                }) { Text(tr("Дальше")) }
            }
            3 -> StepCard(tr("Полный переход на Malachite?")) {
                Text(tr("Удалять ли все другие копии песен (вне главной папки) после того, как они скопированы и проверены?"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.deleteOriginals, { g.settings.update { it.copy(deleteOriginals = true) } })
                    Text(tr("Да — оставить музыку только в главной папке"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!settings.deleteOriginals, { g.settings.update { it.copy(deleteOriginals = false) } })
                    Text(tr("Нет — только копировать, оригиналы не трогать"))
                }
                Text(tr("Оригинал удаляется только после того, как копия сверена байт-в-байт. Разрешение можно отозвать в любой момент: Настройки → «Удалять песни вне главной папки»."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    g.openLibrary(File(settings.mainFolder))
                    step = 4
                }) { Text(tr("Дальше")) }
            }
            else -> StepCard(tr("Сбор музыки")) {
                ImportPanel(onFinished = { g.settings.update { it.copy(onboardingDone = true, whatsNewSeen = app.malachite.player.ui.help.WhatsNew.ID) } })
                TextButton(onClick = { g.settings.update { it.copy(onboardingDone = true, whatsNewSeen = app.malachite.player.ui.help.WhatsNew.ID) } }) { Text(tr("Пропустить — сделаю позже")) }
            }
        }
    }
}

/** Searches the device for music and copies it into the main folder. */
@Composable
fun ImportPanel(doneLabel: String = tr("Готово"), onFinished: () -> Unit = {}) {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by g.settings.state.collectAsState()
    var found by remember { mutableStateOf<DeviceScanner.Found?>(null) }
    var busy by remember { mutableStateOf(false) }
    // "stop": the file being copied is finished, the rest stay where they are
    val stopImport = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var progress by remember { mutableStateOf(0f to "") }
    var result by remember { mutableStateOf<DeviceScanner.ImportResult?>(null) }
    var showRejected by remember { mutableStateOf(false) }
    val forced = remember { mutableStateOf(setOf<String>()) }
    // folders the user unchecked in this search
    val skippedFolders = remember { mutableStateOf(setOf<String>()) }
    var showFolders by remember { mutableStateOf(false) }

    fun search() {
        busy = true
        found = null
        scope.launch {
            found = withContext(Dispatchers.IO) {
                g.deviceScanner.find { d, t -> progress = (if (t > 0) d.toFloat() / t else 0f) to tr("Проверено файлов: $d из $t") }
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) {
            LinearProgressIndicator(progress = { progress.first }, modifier = Modifier.fillMaxWidth())
            Text(progress.second, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        val f = found
        if (f == null && !busy) {
            Text(tr("Плеер просмотрит телефон (и SD-карту) и найдёт музыку."))
            Button(onClick = { search() }) { Text(tr("Найти музыку на устройстве")) }
        }
        if (f != null && result == null) {
            Text(tr("Найдено песен: ${f.music.size}"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (f.already > 0) Text(
                tr("Уже в библиотеке: ${f.already} — они на вкладке «Песни» и не предлагаются снова."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (f.music.isEmpty()) NothingFound(f, settings.importRules.minDurationSec, onAllowMessengers = {
                g.settings.update { st -> st.copy(importRules = st.importRules.copy(importFromMessengers = true)) }
                search()
            }, onRetry = { search() })
            // Review by folder: uncheck what is not music, or exclude a folder for good.
            val byFolder = remember(f) {
                f.music.groupBy { it.file.parent ?: "" }.toList().sortedByDescending { it.second.size }
            }
            TextButton(onClick = { showFolders = !showFolders }) {
                Text(if (showFolders) tr("Скрыть папки") else tr("Проверить по папкам (${byFolder.size}) — снять лишнее"))
            }
            if (showFolders) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                        items(byFolder) { (folder, list) ->
                            val on = folder !in skippedFolders.value
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, { v ->
                                    skippedFolders.value = if (v) skippedFolders.value - folder else skippedFolders.value + folder
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(folder.removePrefix(Storage.primaryRoot().absolutePath).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    Text(tr("${list.size} файлов"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    g.settings.update { st ->
                                        st.copy(importRules = st.importRules.copy(excludedFolders = (st.importRules.excludedFolders + folder).distinct()))
                                    }
                                    skippedFolders.value = skippedFolders.value + folder
                                }) { Text(tr("Никогда"), style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                Text(tr("«Никогда» — папка добавляется в исключённые и больше не просматривается (изменить: Настройки → Библиотека)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val reasons = f.rejectedByReason
            if (reasons.isNotEmpty()) {
                Text(
                    tr("Не музыка (пропущено): ") + reasons.entries.joinToString(", ") { (k, v) -> "${verdictTitle(k)} — $v" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showRejected = !showRejected }) { Text(if (showRejected) tr("Скрыть пропущенные") else tr("Показать пропущенные (можно добавить вручную)")) }
            }
            if (showRejected) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                        items(f.rejected) { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(c.file.absolutePath in forced.value, { on ->
                                    forced.value = if (on) forced.value + c.file.absolutePath else forced.value - c.file.absolutePath
                                })
                                Column {
                                    Text(c.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text("${verdictTitle(c.verdict)} • ${c.file.parent}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            app.malachite.player.ui.settings.DeleteOriginalsControl(compact = true)
            val toImport = f.music.filter { (it.file.parent ?: "") !in skippedFolders.value } +
                f.rejected.filter { it.file.absolutePath in forced.value }
            if (busy) OutlinedButton(onClick = { stopImport.set(true) }) { Text(tr("Остановить после текущего файла")) }
            Button(enabled = toImport.isNotEmpty() && !busy, onClick = {
                busy = true
                stopImport.set(false)
                LongTaskService.start(context, tr("Сбор музыки в главную папку"))
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        g.deviceScanner.import(toImport, stopped = { stopImport.get() }) { d, t, name ->
                            progress = (if (t > 0) d.toFloat() / t else 0f) to tr("Копирую $d из $t: $name")
                            LongTaskService.progress(context, name, d, t)
                        }
                    }
                    withContext(Dispatchers.IO) { g.library.rescan() }
                    LongTaskService.stop(context)
                    busy = false
                }
            }) { Text(tr("Добавить ${toImport.size} в главную папку")) }
        }
        result?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (r.failed == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(tr("Добавлено: ${r.imported}. Пропущено: ${r.skipped}. Ошибок: ${r.failed}.") +
                    if (r.deletedOriginals > 0) tr(" Удалено оригиналов: ${r.deletedOriginals}.") else "")
            }
            r.errors.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(tr("Совет: откройте раздел «Исцеление», чтобы исправить названия, найти обложки и удалить клоны."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onFinished) { Text(doneLabel) }
        }
    }
}

/**
 * A search that found no music says why and what helps: a missing permission, music that lies in
 * messenger folders, or no audio files at all (songs of streaming apps are not files other players can play).
 */
@Composable
private fun NothingFound(f: DeviceScanner.Found, minSec: Int, onAllowMessengers: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val reasons = f.rejectedByReason
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Почему ничего не нашлось"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!Storage.hasAllFilesAccess(context)) {
                Text(tr("У плеера нет «Доступа ко всем файлам», поэтому он видит не всю музыку."))
                StoragePermissionButton()
            }
            val messenger = reasons[ImportFilter.Verdict.MESSENGER] ?: 0
            if (messenger > 0) {
                Text(tr("В папках Telegram, WhatsApp и других мессенджеров лежит аудиофайлов: $messenger. Сами они не берутся: там обычно много лишнего — мемы, отрывки, звуки."))
                Button(onClick = onAllowMessengers) { Text(tr("Взять музыку и из мессенджеров")) }
            }
            val short = reasons[ImportFilter.Verdict.TOO_SHORT] ?: 0
            if (short > 0) Text(tr("Короче $minSec секунд: $short — это обычно звуки и отрывки. Порог меняется в «Настройки → Библиотека»."))
            if (f.rejected.isEmpty() && f.already == 0) {
                Text(tr("На телефоне не нашлось ни одного аудиофайла. Песни из Яндекс Музыки, VK Музыки, Spotify, Звука и других сервисов хранятся внутри их приложений в зашифрованном виде — их не может играть ни один другой плеер. Malachite играет обычные файлы: mp3, flac, m4a, ogg, opus, wav и другие."))
            }
            Text(tr("Песни из Telegram: откройте песню в чате → ⋮ → «Сохранить в музыку» (или «в загрузки») — тогда плеер её найдёт. То, что Telegram только скачал для прослушивания, может лежать в его закрытой папке, куда без root или Shizuku не попасть."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text(tr("Искать ещё раз")) }
        }
    }
}

fun verdictTitle(v: ImportFilter.Verdict) = when (v) {
    ImportFilter.Verdict.MUSIC -> tr("музыка")
    ImportFilter.Verdict.VOICE_MESSAGE -> tr("голосовые")
    ImportFilter.Verdict.RECORDING -> tr("записи")
    ImportFilter.Verdict.SYSTEM_SOUND -> tr("звуки системы")
    ImportFilter.Verdict.TOO_SHORT -> tr("слишком короткие")
    ImportFilter.Verdict.EXCLUDED_FOLDER -> tr("исключённые папки")
    ImportFilter.Verdict.NOT_AUDIO -> tr("не аудио")
    ImportFilter.Verdict.PODCAST -> tr("подкасты/книги")
    ImportFilter.Verdict.VIDEO -> tr("видео")
    ImportFilter.Verdict.MESSENGER -> tr("из мессенджеров / кэш")
}

@Composable
fun ImportScreen() {
    val nav = app.malachite.player.ui.LocalNavigator.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // after adding: to the songs (the screen of the search closes)
        ImportPanel(doneLabel = tr("К песням"), onFinished = {
            nav.back()
            nav.openTab(app.malachite.player.data.Tab.SONGS)
        })
    }
}

/** Shown when the main folder cannot be opened (moved phone, permission revoked, SD card removed…). */
@Composable
fun FolderProblemScreen() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val settings by g.settings.state.collectAsState()
    val granted = rememberStorageGranted()
    var path by remember { mutableStateOf(settings.mainFolder) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(granted) {
        if (granted) {
            val found = withContext(Dispatchers.IO) { Storage.locateMainFolder(context, settings.mainFolder) }
            if (found != null) g.openLibrary(found)
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(tr("Главная папка не найдена"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!granted) {
            Text(tr("У плеера нет разрешения читать файлы. Без него он не может найти главную папку."))
            StoragePermissionButton()
            return@Column
        }
        Text(tr("Плеер искал папку по сохранённому пути, по пути по умолчанию и во внутренней памяти/на SD-карте. " +
            "Если вы перенесли папку (например, распаковали ZIP с другого телефона), укажите путь к ней — полный или неполный."))
        OutlinedTextField(path, { path = it; message = null }, Modifier.fillMaxWidth(), label = { Text(tr("Путь к главной папке")) }, singleLine = true)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val f = Storage.resolveUserPath(path)
            val st = Storage.check(context, f.absolutePath)
            if (st != Storage.FolderStatus.OK) { message = "${f.absolutePath}: ${st.message}"; return@Button }
            if (!LibraryStore.isLibraryRoot(f)) {
                message = tr("В этой папке нет данных Malachite. Нажмите «Создать здесь новую главную папку», если хотите начать с неё.")
                return@Button
            }
            g.settings.restoreFrom(f)
            g.openLibrary(f)
        }) { Text(tr("Открыть эту папку")) }
        OutlinedButton(onClick = {
            val f = Storage.resolveUserPath(path.ifBlank { Storage.defaultMainFolder().absolutePath })
            if (!f.exists() && !f.mkdirs()) { message = tr("Не удалось создать ${f.absolutePath}"); return@OutlinedButton }
            val st = Storage.check(context, f.absolutePath)
            if (st != Storage.FolderStatus.OK) { message = st.message; return@OutlinedButton }
            g.openLibrary(f)
        }) { Text(tr("Создать здесь новую главную папку")) }
        TextButton(onClick = {
            val found = Storage.locateMainFolder(context, settings.mainFolder)
            if (found != null) g.openLibrary(found) else message = tr("Не нашлось папки с данными Malachite.")
        }) { Text(tr("Искать ещё раз")) }
    }
}
