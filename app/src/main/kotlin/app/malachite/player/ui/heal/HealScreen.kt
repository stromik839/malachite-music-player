package app.malachite.player.ui.heal

import app.malachite.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.core.heal.HealKind
import app.malachite.core.heal.NameField
import app.malachite.core.text.MatchConfidence
import app.malachite.player.MalachiteApp
import app.malachite.player.heal.HealingEngine
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator

private data class KindInfo(val kind: HealKind, val icon: ImageVector, val title: String, val text: String)

private val KINDS = listOf(
    KindInfo(HealKind.JUNK, Icons.Filled.Block, tr("Не музыка в главной папке"), tr("Видео (клипы из Telegram, записи экрана), файлы-кэш мессенджеров вроде 4_5913724166165551837@kndved.ogg, голосовые и слишком короткие файлы. Убираются в корзину (можно вернуть) и больше не подхватываются при поиске.")),
    KindInfo(
        HealKind.SOUND_ID, Icons.Filled.Hearing, tr("Узнать песни по звуку"),
        tr("Для песен с непонятными названиями («115620301», «tmpeicn5cwv») или без исполнителя: плеер слушает начало песни, составляет её звуковой отпечаток и спрашивает базу AcoustID, что это за песня. Нужен бесплатный ключ — Настройки → «Узнавание песен по звуку». Отправляется только отпечаток и длина песни."),
    ),
    KindInfo(HealKind.FORMATS, Icons.Filled.SwapHoriz, tr("Форматы файлов"), tr("Находит файлы, у которых расширение не совпадает с настоящим форматом (mp3, переименованный в .wav и т.п.), и даёт им правильное расширение.")),
    KindInfo(HealKind.MISSING, Icons.Filled.Album, tr("Пустые альбомы и исполнители"), tr("Песни, у которых в самом файле нет альбома или исполнителя (из-за этого они попадают в «Без альбома»). Альбом берётся из тегов, прочитанных Android, из папки в главной папке или из папок, где песня лежала раньше («Boris_Kuzmin/Время выткало нить/…»), записывается в файл, и песня переезжает в папку своего альбома. Уверенные варианты отмечены, остальные — проверьте.")),
    KindInfo(HealKind.NAMES, Icons.Filled.Spellcheck, tr("Названия: исполнители, альбомы, песни"), tr("Объединяет «Всем смертям назло» и «Всемсмертямназло», кириллицу и латиницу, опечатки в букву, «𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁»-украшения, водяные знаки сайтов. Записывает исправления в файлы и раскладывает их по папкам.")),
    KindInfo(HealKind.FILENAMES, Icons.Filled.DriveFileRenameOutline, tr("Имена файлов"), tr("Файлы с непонятными именами (2_3049492992.mp3, AUD-20240101-WA0003.mp3, Track 01.mp3) получают название песни, записанное внутри файла. Папки исполнителя и альбома исправляются как обычно — «Названиями» и «Раскладкой». Если наоборот, внутри файла мусор, а имя файла нормальное, «Названия» предложат взять название из имени файла.")),
    KindInfo(HealKind.DUPLICATES, Icons.Filled.ContentCopy, tr("Клоны песен"), tr("Находит копии одной песни: с одинаковым звуком (100% клоны) и одинаковые песни в разном качестве. Лучшая копия остаётся, остальные — в корзину (можно вернуть).")),
    KindInfo(HealKind.COVERS, Icons.Filled.Image, tr("Обложки"), tr("Вшивает обложки в сами файлы: сначала берёт обложку из других песен того же альбома, потом ищет в интернете (iTunes, Deezer, MusicBrainz), пробуя перестановку исполнителя/альбома и транслит.")),
    KindInfo(
        HealKind.COMPILATIONS, Icons.Filled.LibraryMusic, tr("Сборники"),
        tr("Альбом разных исполнителей («A State of Trance», «Хиты 90-х») плеер показывает кусками — отдельным альбомом для каждого исполнителя. Исцеление даёт всем его песням общего исполнителя альбома «Разные исполнители», и альбом снова целый."),
    ),
    KindInfo(
        HealKind.BROKEN, Icons.Filled.BrokenImage, tr("Повреждённые файлы"),
        tr("Пустые и оборванные файлы, файлы без читаемого звука — на них плеер останавливается. Каждый файл открывается и проверяется по кусочку из начала и середины. Убираются в корзину (можно вернуть)."),
    ),
    KindInfo(
        HealKind.TITLE_NUMBERS, Icons.Filled.Tag, tr("Номера в названиях"),
        tr("«01 - Песня» → название «Песня», а номер переходит в номер трека. Числа, которые относятся к названию («7-я пятница», «99 Luftballons»), не трогаются."),
    ),
    KindInfo(
        HealKind.TRACK_NUMBERS, Icons.Filled.FormatListNumbered, tr("Номера треков"),
        tr("Альбомы, у песен которых нет номеров, играют в случайном порядке. Номера берутся из списка песен альбома в интернете (Deezer) — песни сопоставляются по названию."),
    ),
    KindInfo(
        HealKind.LETTER_CASE, Icons.Filled.TextFields, tr("Регистр букв"),
        tr("«НАЗВАНИЕ КАПСОМ» и «всё с маленькой буквы» → нормальный вид. Многие исполнители пишут имя так нарочно, поэтому ничего не отмечено — выберите сами."),
    ),
    KindInfo(
        HealKind.COVER_QUALITY, Icons.Filled.HighQuality, tr("Качество обложек"),
        tr("Слишком мелкие обложки (меньше 300 точек — размыты) заменяются найденными в интернете, если там есть больше. Слишком тяжёлые (больше 1 МБ — каждая песня альбома носит её целиком) уменьшаются до 1000 точек. " +
            "Одна и та же картинка на песнях разных альбомов (вся дискография с одной обложкой) заменяется своей обложкой каждого альбома. Обложки, которые вы выбрали сами, не трогаются."),
    ),
    KindInfo(
        HealKind.LOUDNESS, Icons.Filled.GraphicEq, tr("Громкость"),
        tr("Измеряет, насколько громко звучит каждая песня (как это делают стриминги), и плеер играет все песни одинаково громко. Файлы не меняются. Выключить — Настройки → «Воспроизведение и звук»."),
    ),
    KindInfo(HealKind.LAYOUT, Icons.Filled.DriveFileMove, tr("Раскладка по папкам"), tr("Перемещает файлы в «Главная папка / Исполнитель / Альбом / Песня».")),
    KindInfo(HealKind.LYRICS, Icons.Filled.Lyrics, tr("Тексты и синхронизация"), tr("Ищет тексты (по возможности синхронизированные) для песен без текста и сохраняет их в главной папке. Тексты, найденные раньше другим алфавитом (русская песня латиницей: «I dve tysyachi let voyna»), заменяются настоящими, если они нашлись; свои и синхронизированные вручную тексты не трогаются.")),
)

/** Healing that was not finished when the app was closed or updated. */
@Composable
fun InterruptedHealCard(kinds: List<HealKind>) {
    val g = MalachiteApp.graph
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Исцеление не закончилось"), fontWeight = FontWeight.SemiBold)
            val names = kinds.distinct().joinToString { HealingEngine.title(it) }
            Text(
                tr("Плеер закрылся или обновился, пока шло исцеление ($names). Оно продолжится с того места, где остановилось."),
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { g.healing.resumeInterrupted() }) { Text(tr("Продолжить")) }
                TextButton(onClick = { g.healing.forgetInterrupted() }) { Text(tr("Не продолжать")) }
            }
        }
    }
}

@Composable
fun HealScreen() {
    val undoable by MalachiteApp.graph.healing.undoable.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { MalachiteApp.graph.healing.refreshUndo() }
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val settings by g.settings.state.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf(false) }
    if (choose) HealChoiceDialog(null) { choose = false }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoFixHigh, null); Spacer(Modifier.size(8.dp))
                        Text(tr("Исцеление библиотеки"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(tr("Каждое изменение делается на копии файла, проверяется (звук должен остаться байт-в-байт тем же) и только потом заменяет оригинал. " +
                        "Задачи выполняются строго по очереди — два исцеления никогда не пишут в один файл одновременно.") +
                        if (settings.keepBackups) tr(" Оригиналы сохраняются в .malachite/backup.") else "",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { choose = true }, enabled = st.running == null && st.queued.isEmpty()) {
                        Text(tr("Исцелить всё или выбрать, что именно…"))
                    }
                    Text(tr("Сомнительные случаи (возможные опечатки, неодинаковые клоны, неточные обложки) не трогаются — их можно разобрать ниже вручную."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // closed or updated in the middle of healing: it goes on from where it stopped
        if (st.interrupted.isNotEmpty()) item { InterruptedHealCard(st.interrupted) }
        if (st.running != null || st.queued.isNotEmpty()) item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Выполняется: ${st.running?.let { HealingEngine.title(it) } ?: "…"}"), fontWeight = FontWeight.SemiBold)
                    val p = st.progress
                    LinearProgressIndicator(progress = { if (p != null && p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                    p?.let { Text("${it.done} / ${it.total}: ${it.message}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (st.queued.isNotEmpty()) Text(tr("В очереди: ") + st.queued.joinToString { HealingEngine.title(it) }, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { g.healing.cancel() }) { Text(tr("Остановить после текущего файла")) }
                }
            }
        }
        items(KINDS) { k ->
            KindCard(k, open == k.kind.name, onToggle = { open = if (open == k.kind.name) null else k.kind.name })
        }
        if (st.coverReview.isNotEmpty()) item { CoverReview() }
        if (undoable.isNotEmpty()) item { UndoCard(undoable, busy = st.running != null || st.queued.isNotEmpty()) }
        if (st.reports.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Журнал"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    st.reports.take(6).forEach { r ->
                        Text(tr("${HealingEngine.title(r.kind)}: исправлено ${r.changed}, пропущено ${r.skipped}, ошибок ${r.failed}"),
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        r.log.take(40).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (r.log.size > 40) Text(tr("… и ещё ${r.log.size - 40}"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun KindCard(k: KindInfo, expanded: Boolean, onToggle: () -> Unit) {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val busy = st.analyzing == k.kind
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Icon(k.icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(k.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(k.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { g.healing.analyze(k.kind); if (!expanded) onToggle() }, enabled = st.analyzing == null) {
                    Text(if (busy) tr("Анализ…") else tr("Проверить"))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (expanded) when (k.kind) {
                HealKind.FORMATS -> FormatsPlan()
                HealKind.JUNK -> JunkPlan()
                HealKind.MISSING -> MissingPlan()
                HealKind.NAMES -> NamesPlan()
                HealKind.FILENAMES -> FilenamesPlan()
                HealKind.DUPLICATES -> DuplicatesPlan()
                HealKind.COVERS -> CoversPlan()
                HealKind.LAYOUT -> LayoutPlan()
                HealKind.LYRICS -> LyricsPlan()
                HealKind.COMPILATIONS -> CompilationsPlan()
                HealKind.BROKEN -> BrokenPlan()
                HealKind.COVER_QUALITY -> CoverQualityPlan()
                HealKind.TRACK_NUMBERS -> NumberingPlan()
                HealKind.TITLE_NUMBERS -> TitleNumbersPlan()
                HealKind.LETTER_CASE -> LetterCasePlan()
                HealKind.LOUDNESS -> LoudnessPlan()
                HealKind.SOUND_ID -> SoundIdPlan()
            }
            // the key was missing when this check was wanted: said here, no window
            if (k.kind == HealKind.SOUND_ID && st.soundNoKey && !app.malachite.player.data.SoundIdKey.present) SoundNoKeyNote()
        }
    }
}

@Composable
private fun SoundNoKeyNote() {
    val nav = LocalNavigator.current
    Text(tr("Эта проверка не получилась из-за отсутствия ключа в настройках."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { nav.push(app.malachite.player.ui.Screen.SettingsSoundId) }) { Text(tr("Открыть настройки ключа")) }
}

@Composable
private fun SoundIdPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    if (st.analyzing == HealKind.SOUND_ID) {
        st.progress?.takeIf { it.kind == HealKind.SOUND_ID }?.let { p ->
            Text("${p.done} / ${p.total}: ${p.message}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val plan = st.soundIds ?: return
    if (plan.isEmpty()) {
        if (!st.soundNoKey) AllGood(tr("Узнавать по звуку нечего (или сервис не знает этих песен)"))
        return
    }
    val selected = plan.count { it.selected }
    Text(tr("Узнано: ${plan.size}. Отмечены уверенные (совпадение от 85% и длина сходится)."))
    plan.take(300).forEachIndexed { i, m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(m.selected, { v -> g.healing.updateSoundIds(plan.toMutableList().also { it[i] = m.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text("«${m.oldTitle}»", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    "→ " + listOfNotNull(m.artist, m.title).joinToString(" — ") + (m.album?.let { " ($it)" } ?: "") + " · ${(m.score * 100).toInt()}%",
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Button(enabled = selected > 0, onClick = { g.healing.applySoundIds(plan) }) { Text(tr("Записать отмеченные ($selected)")) }
}

/**
 * The AcoustID key did not work during healing: one window says what is wrong. The rest of the
 * healing went on; only the recognition by sound was skipped.
 */
@Composable
fun SoundKeyProblemDialog() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val st by g.healing.state.collectAsState()
    val trouble = st.soundKeyProblem ?: return
    if (trouble == app.malachite.core.soundid.AcoustId.KeyTrouble.MISSING) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { g.healing.clearSoundKeyProblem() },
        title = { Text(tr("Ключ AcoustID не работает")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.malachite.player.ui.settings.keyTroubleText(trouble, st.soundKeyDetail))
                Text(tr("Узнавание по звуку пропущено, остальное исцеление продолжается как обычно."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { g.healing.clearSoundKeyProblem(); nav.push(app.malachite.player.ui.Screen.SettingsSoundId) }) { Text(tr("Открыть настройки ключа")) }
        },
        dismissButton = { TextButton(onClick = { g.healing.clearSoundKeyProblem() }) { Text(tr("Закрыть")) } },
    )
}

@Composable
private fun AllGood(text: String = tr("Проблем не найдено ✓")) = Text(text, color = MaterialTheme.colorScheme.primary)

@Composable
private fun FormatsPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.formats ?: return
    if (plan.isEmpty()) return AllGood()
    Text(tr("Файлов с неверным расширением: ${plan.size}"))
    plan.take(50).forEach { Text("• ${it.path} → .${it.real.extension}", style = MaterialTheme.typography.bodySmall) }
    Button(onClick = { g.healing.applyFormats(plan) }) { Text(tr("Исправить расширения (${plan.size})")) }
}

@Composable
private fun NamesPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.names ?: return
    if (plan.isEmpty()) return AllGood()
    var editing by remember { mutableStateOf<String?>(null) }
    val selected = plan.count { it.selected }
    Text(tr("Найдено групп: ${plan.size}. Отмечено: $selected. «Возможные» совпадения (опечатки) по умолчанию не отмечены."), style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { g.healing.updateNames(plan.map { it.copy(selected = true) }) }) { Text(tr("Отметить все")) }
        TextButton(onClick = { g.healing.updateNames(plan.map { it.copy(selected = it.confidence != MatchConfidence.POSSIBLE) }) }) { Text(tr("Только уверенные")) }
    }
    plan.take(300).forEach { fix -> androidx.compose.runtime.key(fix.id) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                Checkbox(fix.selected, { v -> g.healing.updateNames(plan.map { if (it.id == fix.id) it.copy(selected = v) else it }) })
                Column(Modifier.weight(1f)) {
                    val kind = when (fix.field) { NameField.ARTIST -> tr("Исполнитель"); NameField.ALBUM -> tr("Альбом"); NameField.TITLE -> tr("Название") }
                    val conf = when (fix.confidence) {
                        MatchConfidence.EXACT -> tr("точно"); MatchConfidence.STRONG -> tr("транслит"); MatchConfidence.POSSIBLE -> tr("возможно (проверьте)")
                    }
                    Text(tr("$kind • $conf • файлов: ${fix.tracks.size}") + if (fix.context.isNotBlank()) " • ${fix.context}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    fix.variants.forEach { v ->
                        Text("«${v.raw.ifEmpty { tr("(пусто)") }}» ×${v.count}", style = MaterialTheme.typography.bodySmall,
                            color = if (v.raw == fix.canonical) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    if (editing == fix.id) {
                        var value by remember { mutableStateOf(fix.canonical) }
                        OutlinedTextField(value, { value = it }, label = { Text(tr("Правильное написание")) }, singleLine = true)
                        Row {
                            TextButton(onClick = { g.healing.updateNames(plan.map { if (it.id == fix.id) it.copy(canonical = value.trim(), selected = true) else it }); editing = null }) { Text("OK") }
                            TextButton(onClick = { editing = null }) { Text(tr("Отмена")) }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("→ «${fix.canonical}»", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editing = fix.id }) { Text(tr("Изменить")) }
                        }
                        // quick pick of another existing spelling
                        fix.variants.filter { it.raw.isNotBlank() && it.raw != fix.canonical }.take(3).forEach { v ->
                            TextButton(onClick = { g.healing.updateNames(plan.map { if (it.id == fix.id) it.copy(canonical = v.raw) else it }) }) {
                                Text(tr("взять «${v.raw}»"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    } }
    if (plan.size > 300) Text(tr("Показаны первые 300 групп. Остальные применятся при выборе «Отметить все»."), style = MaterialTheme.typography.bodySmall)
    Button(enabled = selected > 0, onClick = { g.healing.applyNames(plan) }) { Text(tr("Исправить отмеченные ($selected)")) }
}

@Composable
private fun JunkPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.junk ?: return
    if (plan.isEmpty()) return AllGood(tr("В главной папке только музыка ✓"))
    val sel = plan.count { it.selected }
    Text(tr("Найдено: ${plan.size}. Отмечено: $sel (видео, кэш мессенджеров, голосовые). Короткие и музыка из мессенджеров с нормальными названиями — проверьте сами."),
        style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { g.healing.updateJunk(plan.map { it.copy(selected = true) }) }) { Text(tr("Отметить все")) }
        TextButton(onClick = { g.healing.updateJunk(plan.map { it.copy(selected = false) }) }) { Text(tr("Снять все")) }
    }
    plan.take(400).forEach { j ->
        androidx.compose.runtime.key(j.path) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(j.selected, { v -> g.healing.updateJunk(plan.map { if (it.path == j.path) it.copy(selected = v) else it }) })
                Column(Modifier.weight(1f)) {
                    Text(j.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(j.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (plan.size > 400) Text(tr("Показаны первые 400."), style = MaterialTheme.typography.bodySmall)
    Button(enabled = sel > 0, onClick = { g.healing.applyJunk(plan) }) { Text(tr("Убрать в корзину ($sel)")) }
}

@Composable
private fun MissingPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.missing ?: return
    val withoutAny = remember(plan) { g.healing.songsWithoutAlbumSuggestion().size }
    if (withoutAny > 0) {
        OutlinedButton(onClick = { g.healing.findAlbumsOnline() }, enabled = st.analyzing == null) {
            Text(tr("Найти альбомы в интернете ещё для $withoutAny песен"))
        }
        Text(tr("Deezer и iTunes по исполнителю и названию. Найденное не применяется само — отметьте то, что верно."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // albums and artists that are no names: a site, a placeholder, the song written again
    val odd by androidx.compose.runtime.produceState(emptyList<Pair<app.malachite.core.library.TrackInfo, String>>(), plan, st.analyzing) {
        value = if (st.analyzing == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.healing.oddTagSongs() } else emptyList()
    }
    if (odd.isNotEmpty()) {
        OutlinedButton(onClick = { g.healing.checkOddTagsOnline() }, enabled = st.analyzing == null) {
            Text(tr("Проверить странные альбомы и исполнителей (${odd.size})"))
        }
        Text(
            tr("Например «${odd.first().first.album.ifBlank { odd.first().first.artist }}» — ${odd.first().second}. Альбом ищется по песне и исполнителю, " +
                "исполнитель — по песне и альбому (Deezer, iTunes). Найденное не применяется само."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (plan.isEmpty()) return AllGood(tr("У всех песен в файлах есть альбом и исполнитель ✓"))
    val sel = plan.count { it.selected }
    Text(tr("Найдено: ${plan.size}. Уверенных: ${plan.count { it.confident }}. Отмечено: $sel."), style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { g.healing.updateMissing(plan.map { it.copy(selected = true) }) }) { Text(tr("Отметить все")) }
        TextButton(onClick = { g.healing.updateMissing(plan.map { it.copy(selected = it.confident) }) }) { Text(tr("Только уверенные")) }
    }
    plan.take(300).forEach { sug ->
        androidx.compose.runtime.key(sug.path) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(sug.selected, { v -> g.healing.updateMissing(plan.map { if (it.path == sug.path) it.copy(selected = v) else it }) })
                Column(Modifier.weight(1f)) {
                    Text(sug.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    sug.artist?.let { Text(tr("исполнитель → «$it»"), style = MaterialTheme.typography.bodySmall) }
                    sug.album?.let {
                        Text(if (it.isEmpty()) tr("альбом → стереть") else tr("альбом → «$it»"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    sug.year?.let { Text(tr("год → $it"), style = MaterialTheme.typography.bodySmall) }
                    Text(
                        (if (sug.confident) tr("уверенно") else tr("проверьте")) + " • " + sug.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sug.confident) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (plan.size > 300) Text(tr("Показаны первые 300."), style = MaterialTheme.typography.bodySmall)
    Button(enabled = sel > 0, onClick = { g.healing.applyMissing(plan) }) { Text(tr("Записать в файлы и разложить ($sel)")) }
}

@Composable
private fun FilenamesPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.filenames ?: return
    if (plan.isEmpty()) return AllGood(tr("Все файлы названы так же, как песни внутри них ✓"))
    Text(tr("Файлов с непонятным именем: ${plan.size}"))
    plan.take(60).forEach { r ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(r.oldName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${r.newName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (plan.size > 60) Text(tr("… и ещё ${plan.size - 60}"), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { g.healing.applyFilenames(plan) }) { Text(tr("Переименовать (${plan.size})")) }
}

@Composable
private fun DuplicatesPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.duplicates ?: return
    if (plan.isEmpty()) return AllGood(tr("Клонов не найдено ✓"))
    val sel = plan.count { it.selected }
    Text(tr("Групп: ${plan.size}. 100% клоны (одинаковый звук) отмечены сразу; одинаковые песни в разном качестве — проверьте сами."), style = MaterialTheme.typography.bodySmall)
    plan.take(200).forEach { d ->
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(d.selected, { v -> g.healing.updateDuplicates(plan.map { if (it.id == d.id) it.copy(selected = v) else it }) })
            Column(Modifier.weight(1f)) {
                Text(if (d.identicalAudio) tr("Одинаковый звук") else tr("Похоже на ту же песню"), style = MaterialTheme.typography.labelSmall,
                    color = if (d.identicalAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Text(tr("Оставить: ${d.keep}"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                d.remove.forEach { Text(tr("В корзину: $it"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Button(enabled = sel > 0, onClick = { g.healing.applyDuplicates(plan) }) { Text(tr("Убрать клоны ($sel групп)")) }
}

@Composable
private fun CoversPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.covers ?: return
    if (plan.isEmpty()) return AllGood(tr("У всех песен есть обложки ✓"))
    val donors = plan.count { it.donor != null }
    Text(tr("Альбомов/песен без обложки: ${plan.size} (из них $donors можно взять у соседних песен альбома без интернета)."), style = MaterialTheme.typography.bodySmall)
    plan.take(40).forEach { Text("• ${it.artist} — ${it.album.ifBlank { it.title }} (${it.tracks.size})", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { g.healing.applyCovers(plan) }) { Text(tr("Найти и вшить")) }
        if (donors > 0) OutlinedButton(onClick = { g.healing.applyCovers(plan.filter { it.donor != null }) }) { Text(tr("Только без интернета")) }
    }
}

@Composable
private fun CoverReview() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val st by g.healing.state.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Обложки для ручного выбора: ${st.coverReview.size}"), fontWeight = FontWeight.SemiBold)
            Text(tr("Для них не нашлось уверенного совпадения. Нажмите, чтобы посмотреть варианты."), style = MaterialTheme.typography.bodySmall)
            st.coverReview.take(60).forEach { job ->
                Text("• ${job.artist} — ${job.album.ifBlank { job.title }}", Modifier.clickable {
                    val tracks = job.tracks.mapNotNull { g.library.track(it) }
                    if (tracks.isNotEmpty()) nav.show(DialogRequest.FindCover(tracks))
                }.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun LayoutPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.layout ?: return
    if (plan.isEmpty()) return AllGood(tr("Все файлы лежат на своих местах ✓"))
    Text(tr("Файлов не на своём месте: ${plan.size}"))
    plan.take(40).forEach { Text("• ${it.from}\n   → ${it.to}", style = MaterialTheme.typography.bodySmall) }
    Button(onClick = { g.healing.applyLayout(plan) }) { Text(tr("Разложить (${plan.size})")) }
}

@Composable
private fun LyricsPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.lyrics ?: return
    if (plan.isEmpty()) return AllGood(tr("У всех песен есть тексты ✓"))
    val data = g.library.data.value
    val foreign = plan.count { data.user(it).lyricsId != null }
    Text(tr("Песен без текста: ${plan.size - foreign}. Поиск идёт по одной песне, это может занять время."))
    if (foreign > 0) Text(
        tr("С текстом другим алфавитом (латиницей вместо кириллицы и т. п.): $foreign — для них ищется настоящий текст."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { g.healing.applyLyrics(plan) }) { Text(tr("Найти тексты (${plan.size})")) }
}


/**
 * Taking back healing: the newest task can be undone (a newer one may build on an older one).
 * Files go back to their places and names, the originals of changed files come back, files sent
 * to the trash return; the healed versions go to the trash, so nothing is lost either way.
 */
@Composable
private fun UndoCard(records: List<app.malachite.player.heal.UndoRecord>, busy: Boolean) {
    val g = MalachiteApp.graph
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<app.malachite.player.heal.UndoRecord?>(null) }
    val format = androidx.compose.runtime.remember { java.text.SimpleDateFormat("d.MM, HH:mm", java.util.Locale.getDefault()) }
    fun title(r: app.malachite.player.heal.UndoRecord) =
        runCatching { HealingEngine.title(app.malachite.core.heal.HealKind.valueOf(r.kind)) }.getOrDefault(r.kind).replaceFirstChar { it.uppercase() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Отменить исцеление"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(tr("Можно отменить последнее исцеление, затем предыдущее. Исправленные версии файлов не удаляются, а уходят в корзину."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            records.take(5).forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title(r), fontWeight = FontWeight.Medium)
                        Text(tr("${format.format(java.util.Date(r.time))} • изменено файлов: ${r.changed}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i == 0) OutlinedButton(enabled = !busy, onClick = { confirm = r }) { Text(tr("Отменить")) }
                }
            }
        }
    }
    confirm?.let { r ->
        val originalsKept = r.backupDir?.let { java.io.File(it).isDirectory } == true
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("Отменить «${title(r)}»?")) },
            text = {
                Text(
                    tr("Файлы вернутся на прежние места и к прежним названиям, удалённые этим исцелением — из корзины.") + " " +
                        (if (originalsKept) tr("Изменённые теги и обложки вернутся к оригиналам.")
                        else tr("Копии оригиналов не сохранились (или были очищены) — теги и обложки останутся как сейчас.")) + " " +
                        tr("Если вы меняли эти файлы позже, эти изменения уйдут в корзину вместе с исправленными версиями."),
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; g.healing.undo(r) }) { Text(tr("Отменить исцеление")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Отмена")) } },
        )
    }
}

@Composable
private fun CompilationsPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.compilations ?: return
    if (plan.isEmpty()) return AllGood()
    val selected = plan.filter { it.selected }
    Text(tr("Сборников, разбитых на части: ${plan.size}. Отмечено: ${selected.size}. Остальные проверьте — может быть, это альбом одного исполнителя с гостями."))
    plan.take(100).forEachIndexed { i, c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(c.selected, { v -> g.healing.updateCompilations(plan.toMutableList().also { it[i] = c.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text(c.album, fontWeight = FontWeight.Medium)
                Text(
                    tr("${c.paths.size} песен • ${c.artists.size} исполнителей: ") + c.artists.take(4).joinToString(", ") + if (c.artists.size > 4) "…" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(enabled = selected.isNotEmpty(), onClick = { g.healing.applyCompilations(plan) }) { Text(tr("Собрать в целые альбомы (${selected.size})")) }
}

@Composable
private fun BrokenPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.broken ?: return
    if (plan.isEmpty()) return AllGood()
    val selected = plan.count { it.selected }
    Text(tr("Найдено: ${plan.size}. Отмечены точно повреждённые; остальные откройте и послушайте сами, прежде чем убирать."))
    plan.take(200).forEachIndexed { i, b ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(b.selected, { v -> g.healing.updateBroken(plan.toMutableList().also { it[i] = b.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text(b.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Button(enabled = selected > 0, onClick = { g.healing.applyBroken(plan) }) { Text(tr("Убрать в корзину ($selected)")) }
}

@Composable
private fun TitleNumbersPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.titleNumbers ?: return
    if (plan.isEmpty()) return AllGood()
    val selected = plan.count { it.selected }
    Text(tr("Найдено: ${plan.size}. Отмечено: $selected. Без галочки — где номер в названии не совпадает с номером в теге."))
    plan.take(200).forEachIndexed { i, f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(f.selected, { v -> g.healing.updateTitleNumbers(plan.toMutableList().also { it[i] = f.copy(selected = v) }) })
            Text("«${f.oldTitle}» → «${f.newTitle}»" + (f.track?.let { tr(", трек $it") } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(enabled = selected > 0, onClick = { g.healing.applyTitleNumbers(plan) }) { Text(tr("Исправить отмеченные ($selected)")) }
}

@Composable
private fun NumberingPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.numbering ?: return
    if (plan.isEmpty()) return AllGood(tr("Альбомов без номеров, для которых нашёлся список песен, нет ✓"))
    val selected = plan.filter { it.selected }
    Text(tr("Альбомов: ${plan.size}. Номера сверены с их списками песен по названиям."))
    plan.forEachIndexed { i, a ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(a.selected, { v -> g.healing.updateNumbering(plan.toMutableList().also { it[i] = a.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text("${a.artist} — ${a.album}", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.fixes.sortedBy { it.track }.take(6).joinToString(", ") { "${it.track}. ${it.title}" } + if (a.fixes.size > 6) "…" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Button(enabled = selected.isNotEmpty(), onClick = { g.healing.applyNumbering(plan) }) { Text(tr("Записать номера (${selected.sumOf { it.fixes.size }})")) }
}

@Composable
private fun LetterCasePlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.letterCase ?: return
    if (plan.isEmpty()) return AllGood()
    val selected = plan.filter { it.selected }
    Text(tr("Найдено: ${plan.size}. Отметьте то, что действительно нужно исправить."))
    plan.take(200).forEachIndexed { i, f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(f.selected, { v -> g.healing.updateLetterCase(plan.toMutableList().also { it[i] = f.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text("«${f.old}» → «${f.new}»", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (f.field) {
                        app.malachite.core.heal.CaseField.ARTIST -> tr("исполнитель")
                        app.malachite.core.heal.CaseField.ALBUM -> tr("альбом")
                        app.malachite.core.heal.CaseField.TITLE -> tr("название")
                    } + " • " + tr("файлов: ${f.paths.size}"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(enabled = selected.isNotEmpty(), onClick = { g.healing.applyLetterCase(plan) }) { Text(tr("Исправить отмеченные (${selected.size})")) }
}

@Composable
private fun CoverQualityPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val plan = st.coverIssues ?: return
    if (plan.isEmpty()) return AllGood()
    val selected = plan.filter { it.selected }
    val shared = plan.count { it.shared }
    Text(tr("Мелких: ${plan.count { !it.heavy && !it.shared }}, тяжёлых: ${plan.count { it.heavy }}. Мелкие заменяются, только если в интернете нашлась обложка больше."))
    if (shared > 0) Text(
        tr("Одна картинка на разные альбомы: $shared. Так бывает, когда вся дискография скачана с одной картинкой или сайт вшил свой логотип в каждый файл. Для каждого альбома ищется своя обложка — так же, как при ручной замене; если уверенной не нашлось, остаётся как есть. Обложки, выбранные вами вручную, не трогаются."),
        style = MaterialTheme.typography.bodySmall,
    )
    plan.take(200).forEachIndexed { i, c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(c.selected, { v -> g.healing.updateCoverIssues(plan.toMutableList().also { it[i] = c.copy(selected = v) }) })
            Column(Modifier.weight(1f)) {
                Text("${c.artist} — ${c.album.ifBlank { c.title }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${c.width}×${c.height}, %.1f МБ • ".format(c.bytes / 1_000_000.0) +
                        (when { c.heavy -> tr("тяжёлая — уменьшить"); c.shared -> tr("та же, что у альбомов: ${c.sharedBy} — найти свою"); else -> tr("мелкая — найти больше") }) +
                        " • " + tr("файлов: ${c.paths.size}"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(enabled = selected.isNotEmpty(), onClick = { g.healing.applyCoverQuality(plan) }) { Text(tr("Исправить отмеченные (${selected.size})")) }
}

@Composable
private fun LoudnessPlan() {
    val g = MalachiteApp.graph
    val st by g.healing.state.collectAsState()
    val settings by g.settings.state.collectAsState()
    val plan = st.loudness ?: return
    if (plan.isEmpty()) return AllGood(tr("Громкость всех песен измерена ✓"))
    Text(tr("Не измерено песен: ${plan.size}. Это долго (около секунды на песню), но делается один раз; можно свернуть плеер."))
    if (!settings.levelLoudness) Text(tr("Выравнивание громкости сейчас выключено в настройках воспроизведения."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Button(onClick = { g.healing.measureLoudness(plan) }) { Text(tr("Измерить (${plan.size})")) }
}
