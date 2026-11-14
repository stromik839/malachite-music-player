package app.malachite.player.ui.sync

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import app.malachite.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.malachite.core.lyrics.Lyrics
import app.malachite.core.lyrics.LyricsCleaner
import app.malachite.core.lyrics.SyncRecorder
import app.malachite.player.MalachiteApp
import app.malachite.player.data.SyncDraft
import app.malachite.player.data.SyncDrafts
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.components.formatTime
import app.malachite.player.ui.nowplaying.rememberPlayerPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manual lyrics syncing with one button: hold it while a line (or word) is sung, release when
 * it ends. The song can be slowed down or sped up; the stored times are always on the
 * normal 1x timeline (see [SyncRecorder]).
 */
/**
 * One line of the lyrics while they are synced, moving like the lyrics in the player: the size
 * and weight never change (the line being recorded used to jump to a bigger, bolder text at
 * every press); it grows and takes its colour smoothly, and in word mode the highlight glides
 * from word to word.
 */
@Composable
private fun SyncLine(
    line: String,
    cur: Boolean,
    done: Boolean,
    word: Int,
    family: androidx.compose.ui.text.font.FontFamily,
    bold: Boolean,
    animMs: Int,
) {
    val primary = MaterialTheme.colorScheme.primary
    val waiting = MaterialTheme.colorScheme.onSurface
    val passed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (cur) 1f else 0.78f,
        if (animMs == 0) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(animMs, easing = ease),
        label = "syncScale",
    )
    val color by androidx.compose.animation.animateColorAsState(
        when {
            cur -> primary
            done -> passed
            else -> waiting
        },
        if (animMs == 0) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(animMs, easing = ease),
        label = "syncColor",
    )
    // the word to mark now, as a smoothly moving place (-1: none)
    val at by androidx.compose.animation.core.animateFloatAsState(
        word.toFloat(),
        if (animMs == 0) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(minOf(animMs, 280), easing = ease),
        label = "syncWord",
    )
    val words = remember(line) { line.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    // word by word: the words already marked, the word to mark now, the words still to come
    val shown = if (cur && word >= 0) {
        buildAnnotatedString {
            words.forEachIndexed { w, text ->
                if (w > 0) append(' ')
                val d = w - at
                val c = when {
                    d <= -1f -> passed
                    d <= 0f -> androidx.compose.ui.graphics.lerp(passed, primary, 1f + d)
                    d < 1f -> androidx.compose.ui.graphics.lerp(primary, waiting, d)
                    else -> waiting
                }
                withStyle(SpanStyle(color = c)) { append(text) }
            }
        }
    } else AnnotatedString(line)
    Text(
        shown,
        Modifier.fillMaxWidth().padding(vertical = 3.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        textAlign = TextAlign.Center,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        fontFamily = family,
        color = color,
    )
}

@Composable
fun LyricsSyncScreen(path: String, resume: Boolean = false) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val settings by g.settings.state.collectAsState()
    val t = g.library.track(path) ?: run { Text(tr("Песня не найдена"), Modifier.padding(16.dp)); return }

    var stage by remember { mutableIntStateOf(0) } // 0 prepare, 1 record, 2 review
    var text by remember { mutableStateOf("") }
    var removeParens by remember { mutableStateOf(settings.lyricsRemoveAllParentheses) }
    var wordMode by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(0.75f) }
    var reaction by remember { mutableFloatStateOf(settings.syncReactionMs.toFloat()) }
    var recorder by remember { mutableStateOf<SyncRecorder?>(null) }
    var result by remember { mutableStateOf<Lyrics?>(null) }
    var offset by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    /** An unfinished recording of this song saved earlier (see [SyncDrafts]). */
    var draft by remember { mutableStateOf<SyncDraft?>(null) }

    /** Saves what was recorded so far, so that it can be continued – nothing is lost by leaving. */
    fun saveDraft(r: SyncRecorder) {
        if (r.cursor == 0) return
        val saved = r.save()
        val d = SyncDraft(
            path = t.path, title = t.displayTitle, artist = t.displayArtist, text = text, removeParens = removeParens,
            wordMode = r.mode == SyncRecorder.Mode.WORDS, speed = speed, reactionMs = reaction.toInt(),
            cursor = saved.cursor, total = r.total, starts = saved.starts, ends = saved.ends, updatedAt = System.currentTimeMillis(),
            lines = r.lines,
        )
        draft = d
        SyncDrafts.save(d)
    }

    /** Goes on with a saved recording: same text, same settings, the song a little before the last mark. */
    fun continueFrom(d: SyncDraft) {
        val lines = d.lines ?: LyricsCleaner.singableLines(d.text, LyricsCleaner.Options(d.removeParens))
        val r = SyncRecorder(lines, if (d.wordMode) SyncRecorder.Mode.WORDS else SyncRecorder.Mode.LINES, d.reactionMs.toLong())
        if (!r.restore(SyncRecorder.Saved(d.cursor, d.starts, d.ends))) {
            nav.toast(tr("Сохранённые отметки не подходят к этому тексту — начните заново"))
            return
        }
        text = d.text
        removeParens = d.removeParens
        wordMode = d.wordMode
        speed = d.speed
        reaction = d.reactionMs.toFloat()
        recorder = r
        if (g.player.state.value.currentPath != t.path) g.player.play(listOf(t))
        g.player.setTemporarySpeed(d.speed)
        g.player.seekTo((r.lastMarkMs - 2500).coerceAtLeast(0))
        stage = 1
    }

    LaunchedEffect(path) {
        val (existing, saved) = withContext(Dispatchers.IO) { g.library.loadLyrics(t) to SyncDrafts.load(t.path) }
        draft = saved
        if (saved != null && resume && stage == 0) continueFrom(saved)
        else if (existing != null && text.isEmpty()) text = saved?.text ?: existing.lines.joinToString("\n") { it.text }
        else if (saved != null && text.isEmpty()) text = saved.text
    }
    DisposableEffect(Unit) { onDispose { g.player.restoreSpeed() } }

    when (stage) {
        0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${t.displayArtist} — ${t.displayTitle}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            draft?.let { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("Есть незаконченная синхронизация"), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (d.wordMode) tr("Отмечено слов: ${d.cursor} из ${d.total}") else tr("Отмечено строк: ${d.cursor} из ${d.total}"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { continueFrom(d) }) { Text(tr("Продолжить")) }
                            TextButton(onClick = { SyncDrafts.delete(t.path); draft = null }) { Text(tr("Удалить")) }
                        }
                    }
                }
            }
            if (!resume) OutlinedButton(onClick = { nav.push(Screen.LyricsEdit(t.path)) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Исправить отдельные строки, не синхронизируя заново"))
            }
            Text(tr("1. Текст песни (по одной строке). Пометки [Припев], (Куплет), «Текст песни…» удаляются автоматически."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(220.dp), label = { Text(tr("Текст")) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(removeParens, { removeParens = it }); Spacer(Modifier.size(8.dp))
                Text(tr("Убирать любой текст в круглых скобках (подпевки)"), style = MaterialTheme.typography.bodyMedium)
            }
            Text(tr("2. Что отмечать нажатием"), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(!wordMode, { wordMode = false }, label = { Text(tr("Строки")) })
                FilterChip(wordMode, { wordMode = true }, label = { Text(tr("Каждое слово")) })
            }
            Text(tr("3. Скорость во время записи: %.2fx").format(speed), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.5f, 0.6f, 0.75f, 0.9f, 1f, 1.25f).forEach { v -> FilterChip(speed == v, { speed = v }, label = { Text("${v}x", maxLines = 1) }) }
            }
            Text(tr("Время всё равно сохраняется для обычной скорости 1x."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("4. Ваша задержка реакции: ${reaction.toInt()} мс"), style = MaterialTheme.typography.titleSmall)
            Slider(reaction, { reaction = it }, valueRange = 0f..400f)
            Text(tr("Если отметки получаются чуть позже слов — увеличьте. Пересчитывается с учётом выбранной скорости."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val lines = LyricsCleaner.singableLines(text, LyricsCleaner.Options(removeParens))
            Button(enabled = lines.isNotEmpty(), onClick = {
                g.settings.update { it.copy(syncReactionMs = reaction.toInt()) }
                recorder = SyncRecorder(lines, if (wordMode) SyncRecorder.Mode.WORDS else SyncRecorder.Mode.LINES, reaction.toLong())
                SyncDrafts.delete(t.path)
                draft = null
                if (g.player.state.value.currentPath != t.path) g.player.play(listOf(t)) else g.player.seekTo(0)
                g.player.setTemporarySpeed(speed)
                stage = 1
            }) { Text(tr("Начать (${lines.size} строк)")) }
        }
        1 -> {
            val r = recorder ?: return
            val pos = rememberPlayerPosition()
            val st by g.player.state.collectAsState()
            // re-checked after every press / release
            LaunchedEffect(tick) {
                if (r.isComplete) { saveDraft(r); result = r.result(); g.player.restoreSpeed(); stage = 2 }
            }
            // Saved every few seconds, when the screen is left and when the player goes to the
            // background: a closed app or an empty battery costs a few seconds at most.
            LaunchedEffect(r, settings.syncAutosaveSec) {
                while (true) {
                    delay(settings.syncAutosaveSec.coerceIn(3, 600) * 1000L)
                    saveDraft(r)
                }
            }
            DisposableEffect(r) { onDispose { saveDraft(r) } }
            // "♪" lines: started by themselves after the set silence
            LaunchedEffect(r, settings.syncPauseSec) {
                r.autoPauses = settings.syncPauseSec > 0
                if (!r.autoPauses) return@LaunchedEffect
                while (true) {
                    delay(150)
                    if (g.player.state.value.isPlaying && r.autoPause(g.player.positionMs(), speed, settings.syncPauseSec * 1000L)) tick++
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveDraft(r) }

            // Read here, after every press and release (tick), and handed to the list as plain
            // values: the list used to keep showing the line it was first drawn with.
            val marked = if (tick >= 0) minOf(r.cursor, r.total) else 0
            val currentLine = if (tick >= 0) r.currentLine else 0
            val currentWord = if (tick >= 0) r.currentWord else -1
            val listState = rememberLazyListState()
            val animMs = settings.lyricsAnim.ms
            val lyricFamily = remember(settings.lyricsFont, settings.lyricsFontFile) {
                app.malachite.player.ui.theme.fontFamily(settings.lyricsFont, settings.lyricsFontFile)
            }
            // the line being recorded glides to the upper third, the way the lyrics in the player move
            LaunchedEffect(currentLine) {
                if (r.lines.isEmpty()) return@LaunchedEffect
                val target = currentLine.coerceIn(0, r.lines.lastIndex)
                val anchor = listState.layoutInfo.viewportSize.height / 3f
                fun delta(): Float? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                    ?.let { it.offset - listState.layoutInfo.viewportStartOffset + it.size / 2f - anchor }
                val d = delta()
                if (d != null) {
                    if (animMs == 0) listState.scrollBy(d)
                    else listState.animateScrollBy(d, androidx.compose.animation.core.tween(animMs + 100, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                } else {
                    // far away (after "undo" over several lines): jump there, then put the line in place
                    listState.scrollToItem(target)
                    delta()?.let { listState.scrollBy(it) }
                }
            }
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${formatTime(pos)} • %.2fx • $marked/${r.total}".format(speed), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { if (r.total > 0) marked.toFloat() / r.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    itemsIndexed(r.lines) { i, line ->
                        SyncLine(
                            line = line,
                            cur = i == currentLine,
                            done = i < currentLine,
                            word = if (i == currentLine && r.mode == SyncRecorder.Mode.WORDS) currentWord else -1,
                            family = lyricFamily,
                            bold = settings.lyricsBold,
                            animMs = animMs,
                        )
                    }
                }
                if (r.mode == SyncRecorder.Mode.WORDS) {
                    Text(tr("Слово: «${r.currentText}»"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                // a "♪" under the cursor starts by itself after a while without a press
                if (r.autoPauses && !r.holding && r.currentText.let { SyncRecorder.isPauseText(it) }) {
                    Text(
                        tr("«♪» отметится сам через ${settings.syncPauseSec} с тишины — нажмите, когда снова запоют"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    syncButtonShape(settings.syncButtonWidthDp, settings.syncButtonHeightDp, settings.syncButtonCorner)
                        .background(if (r.holding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(r) {
                            detectTapGestures(onPress = {
                                r.press(g.player.positionMs(), speed); tick++
                                tryAwaitRelease()
                                r.release(g.player.positionMs(), speed); tick++
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (r.holding) tr("Отпустите,\nкогда закончится") else tr("Держите,\nпока поётся"),
                        textAlign = TextAlign.Center,
                        color = if (r.holding) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // the buttons go on a second line when the screen is narrow (they used to be squeezed into an empty sliver)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { g.player.seekTo(r.undo()); tick++ }) { Text(tr("Отменить"), maxLines = 1) }
                    OutlinedButton(onClick = { r.skip(); tick++ }) { Text(tr("Пропустить"), maxLines = 1) }
                    OutlinedButton(onClick = { g.player.toggle() }) { Text(if (st.isPlaying) tr("Пауза") else tr("Играть"), maxLines = 1) }
                    TextButton(onClick = { result = r.result(); g.player.restoreSpeed(); stage = 2 }) { Text(tr("Готово"), maxLines = 1) }
                }
            }
        }
        else -> {
            val l = result ?: return
            val shifted = l.withOffset(offset)
            val pos = rememberPlayerPosition()
            val active = shifted.activeLine(pos)
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(tr("Проверка: включите песню и посмотрите, совпадает ли текст"), style = MaterialTheme.typography.titleSmall)
                var typing by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center) {
                    TextButton(onClick = { offset -= 100 }) { Text(tr("−0.1 с")) }
                    // tap to type the shift
                    TextButton(onClick = { typing = true }) {
                        Text(
                            tr("сдвиг %+.1f с").format(offset / 1000f),
                            color = MaterialTheme.colorScheme.onSurface,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    }
                    TextButton(onClick = { offset += 100 }) { Text(tr("+0.1 с")) }
                    TextButton(onClick = { g.player.seekTo(0); g.player.toggle() }) { Text(tr("С начала")) }
                }
                if (typing) app.malachite.player.ui.nowplaying.OffsetDialog(offset, onSet = { offset = it }, onDismiss = { typing = false })
                Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                        itemsIndexed(shifted.lines) { i, line ->
                            Text(
                                "${formatTime(line.startMs + offset)}  ${line.text}",
                                Modifier.padding(vertical = 3.dp),
                                color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { stage = 0 }) { Text(tr("Заново")) }
                    var saving by remember { mutableStateOf(false) }
                    Button(enabled = !saving, onClick = {
                        saving = true
                        val final = l.withOffset(offset).applyOffset().copy(source = tr("синхронизировано вручную"))
                        g.library.saveLyricsInBackground(t, final, tr("синхронизировано вручную")) { ok ->
                            saving = false
                            if (!ok) { nav.toast(tr("Не удалось сохранить текст")); return@saveLyricsInBackground }
                            SyncDrafts.delete(t.path)
                            nav.toast(tr("Синхронизация сохранена"))
                            nav.back()
                        }
                    }) { Text(tr("Сохранить")) }
                }
            }
        }
    }
}

/** The size and the corners of the "hold while it is sung" button, as set in the settings. */
private fun syncButtonShape(widthDp: Int, heightDp: Int, corner: Int): Modifier =
    Modifier.width(if (widthDp >= 590) 2000.dp else widthDp.dp).height(heightDp.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = corner.coerceIn(0, 50)))

/** The button as it will look (in the settings). */
@Composable
fun SyncButtonPreview(widthDp: Int, heightDp: Int, corner: Int) {
    Box(
        syncButtonShape(widthDp, heightDp, corner).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(tr("Держите,\nпока поётся"), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    }
}
