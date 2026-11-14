package app.malachite.player.ui.nowplaying

import app.malachite.core.i18n.tr
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.malachite.core.library.TrackInfo
import app.malachite.core.lyrics.LyricLine
import app.malachite.core.lyrics.Lyrics
import app.malachite.core.lyrics.LyricsCleaner
import app.malachite.player.MalachiteApp
import app.malachite.player.data.LyricsAlign
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.theme.fontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lyrics of the current song. Lyrics are searched online only when this panel is opened
 * (the user tapped "lyrics") or the "find" button is pressed – never in the background.
 */
@Composable
fun LyricsPanel(t: TrackInfo, speed: Float = 1f, pos: () -> Long) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val settings by g.settings.state.collectAsState()
    val data by g.library.data.collectAsState()
    val user = data.user(t.path)
    var lyrics by remember(t.path) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(t.path) { mutableStateOf(true) }
    var searching by remember(t.path) { mutableStateOf(false) }
    var notFound by remember(t.path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var searchJob by remember(t.path) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var offline by remember(t.path) { mutableStateOf(false) }
    fun search() {
        searching = true
        notFound = false
        searchJob = scope.launch {
            app.malachite.player.data.Net.whenOnline({ offline = it }) {}
            val best = withContext(Dispatchers.IO) {
                runCatching {
                    // synced lyrics first; plain text only when no synced version exists
                    g.lyricsFinder.best(
                        t.displayTitle, t.displayArtist, t.album, t.durationMs,
                        LyricsCleaner.Options(removeAllParentheses = settings.lyricsRemoveAllParentheses),
                    )
                }.getOrNull()
            }
            if (best != null && best.lyrics.lines.isNotEmpty()) {
                // a song from the internet has no file to keep lyrics with: shown, not stored
                if (!g.library.isOnline(t.path)) withContext(Dispatchers.IO) { g.library.saveLyrics(t, best.lyrics, best.source) }
                lyrics = best.lyrics
            } else notFound = true
            searching = false
        }
    }

    LaunchedEffect(t.path, user.lyricsId, user.lyricsOffsetMs) {
        loading = true
        lyrics = if (g.library.isOnline(t.path)) lyrics else withContext(Dispatchers.IO) { g.library.loadLyrics(t) }
        loading = false
        if (lyrics == null && settings.lyricsAutoSearch && !user.noLyrics && !searching) search()
    }

    val l = lyrics
    // placing the line by the grid: a song without synced lyrics shows a made-up text instead
    val demo = LyricsGuide.adjusting && (l == null || !l.synced || l.lines.isEmpty())
    when {
        demo -> {
            val family = remember(settings.lyricsFont, settings.lyricsFontFile) { fontFamily(settings.lyricsFont, settings.lyricsFontFile) }
            val demoLyrics = remember { LyricsGuide.demo() }
            LyricsLines(t, demoLyrics, speed, pos, settings, family, demo = true)
        }
        loading -> CircularProgressIndicator()
        searching -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(if (offline) tr("Нет интернета. Поиск начнётся сам, как только появится связь.") else tr("Ищу текст «${t.displayTitle}»…"), textAlign = TextAlign.Center)
            // opened by mistake or simply too slow: the search can be dropped at any moment
            TextButton(onClick = { searchJob?.cancel(); searching = false; offline = false }) { Text(tr("Отмена")) }
        }
        l == null || l.lines.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (notFound) tr("Текст не найден автоматически") else if (user.noLyrics) tr("Отмечено: без текста") else tr("Текста пока нет"),
                style = MaterialTheme.typography.titleMedium)
            Button(onClick = { search() }) { Text(tr("Найти текст")) }
            if (!g.library.isOnline(t.path)) {
                TextButton(onClick = { nav.show(DialogRequest.FindLyrics(t)) }) { Text(tr("Выбрать вариант / вставить свой")) }
                TextButton(onClick = { g.library.setNoLyrics(t.path, true) }) { Text(tr("В песне нет слов")) }
            }
        }
        else -> {
            val family = remember(settings.lyricsFont, settings.lyricsFontFile) { fontFamily(settings.lyricsFont, settings.lyricsFontFile) }
            LyricsLines(t, l, speed, pos, settings, family)
        }
    }
}

@Composable
private fun LyricsLines(
    t: TrackInfo,
    l: Lyrics,
    speed: Float,
    position: () -> Long,
    s: app.malachite.player.data.AppSettings,
    family: androidx.compose.ui.text.font.FontFamily,
    /** A made-up text shown only for placing the line: nothing in it is saved. */
    demo: Boolean = false,
) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val pos by rememberUpdatedState(position)
    val data by g.library.data.collectAsState()
    val marks = data.user(t.path).markedLines.toSet()
    // each line knows which repeat of its words it is: only the chosen one gets the mark
    val markKeys = remember(l) { app.malachite.core.lyrics.lineMarkKeys(l.lines.map { it.text }) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val fontSize = s.lyricsFontSize
    val textAlign = when (s.lyricsAlign) {
        LyricsAlign.CENTER -> TextAlign.Center
        LyricsAlign.START -> TextAlign.Start
        LyricsAlign.END -> TextAlign.End
    }
    if (!l.synced) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                l.lines.forEach {
                    Text(it.text, Modifier.fillMaxWidth().padding(vertical = 3.dp), fontSize = (fontSize * 0.8f).sp, textAlign = textAlign, fontFamily = family)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { nav.push(Screen.LyricsSync(t.path)) }) { Text(tr("Текст без времени — синхронизировать")) }
            }
        }
        return
    }
    // The move to the next line starts a little before the line itself, so the line is already
    // in place and highlighted when it is sung (the animation is not sped up, it starts earlier).
    val leadMs = (s.lyricsAnim.ms * 0.6f * speed.coerceIn(0.25f, 3f)).toLong()
    // Derived states: this list is recomposed when the line changes, not on every frame.
    val active by remember(l, leadMs) { derivedStateOf { l.activeLineAhead(pos(), leadMs) } }
    val inGap by remember(l, leadMs) {
        derivedStateOf { val p = pos(); l.activeLineAhead(p, leadMs) == l.activeLine(p) && l.isInGap(p) }
    }
    val state = rememberLazyListState()
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { lastUserScroll = if (dragged || lastUserScroll != 0L) System.currentTimeMillis() else 0L }
    var panel by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var capsuleTop by remember { mutableFloatStateOf(Float.NaN) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { panel = it.boundsInRoot() }) {
        val density = LocalDensity.current
        val heightPx = constraints.maxHeight.toFloat()
        // a narrow panel (landscape, split screen): the capsule gets an icon instead of words
        val narrowPanel = maxWidth < 340.dp
        // The line being sung sits in the middle of the frame people see the lyrics in: from the
        // album line under "Сейчас играет" down to the top of the shift capsule (the capsule
        // covers the bottom of the panel, so the panel's own middle was too low). The setting
        // moves it higher or lower from there.
        val (frameTop, frameBottom) = LyricsGuide.frame(panel, capsuleTop, heightPx)
        val shift = s.lyricsAnchorShift.coerceIn(-0.4f, 0.4f)
        val anchorPx = (frameTop + (frameBottom - frameTop) * (0.5f + shift)).coerceIn(0f, heightPx)
        // half of an ordinary line, so the first and the last line can reach the anchor too
        val halfLinePx = with(density) { (fontSize * 0.65f + 8f).dp.toPx() }
        LaunchedEffect(active, anchorPx) {
            if (active < 0) return@LaunchedEffect
            // the user scrolled by hand: do not pull the text away for a few seconds
            if (dragged || System.currentTimeMillis() - lastUserScroll < 3500) return@LaunchedEffect
            // (A scroll still running from the previous line is simply continued from where it is.
            // It used to make this line skip its scroll, and the next one jumped two lines at once.)
            fun delta(): Float? = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
                ?.let { it.offset - state.layoutInfo.viewportStartOffset + it.size / 2f - anchorPx }
            val d = delta()
            if (d != null) {
                if (s.lyricsAnim.ms == 0) state.scrollBy(d)
                else state.animateScrollBy(d, tween(durationMillis = s.lyricsAnim.ms + 100, easing = FastOutSlowInEasing))
            } else {
                // far away (after seeking): jump there, then put the line exactly on its place
                state.scrollToItem(active)
                delta()?.let { state.scrollBy(it) }
            }
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                // soft fade at the top and bottom edges instead of a hard cut
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = state,
            // equal room above and below so the first and last lines can also reach the centre
            contentPadding = with(density) {
                PaddingValues(
                    top = (anchorPx - halfLinePx).coerceAtLeast(0f).toDp(),
                    bottom = (heightPx - anchorPx - halfLinePx).coerceAtLeast(0f).toDp(),
                )
            },
        ) {
            itemsIndexed(l.lines) { i, line ->
                val next = l.lines.getOrNull(i + 1)?.startMs
                val isActive = i == active && !inGap
                // only the line being coloured (word by word, or swept letter by letter) follows the position on every frame
                val sweeping = s.lyricsWordFill == app.malachite.player.data.WordFill.SWEEP
                val wordPos: (() -> Long)? = if (isActive && (line.words.isNotEmpty() || sweeping)) ({ pos() - l.offsetMs }) else null
                LyricRow(
                    line, next, isActive, i < active || (i == active && inGap), wordPos, s, textAlign, family,
                    switchAtMs = l.switchMs(i, leadMs),
                    distance = if (active >= 0) kotlin.math.abs(i - active) else 0,
                    marked = app.malachite.core.lyrics.isLineMarked(markKeys.getOrElse(i) { "" }, marks),
                    onLongClick = {
                        // a favourite line: press and hold to mark it, again to take the mark away
                        if (!demo && !g.library.isOnline(t.path)) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            val now = g.library.toggleLineMark(t.path, markKeys.getOrElse(i) { "" })
                            app.malachite.player.widget.WidgetUpdater.requestUpdate(MalachiteApp.instance)
                            nav.toast(if (now) tr("Строка отмечена как любимая") else tr("Отметка снята"))
                        }
                    },
                ) {
                    g.player.seekTo(line.startMs + l.offsetMs)
                }
            }
        }
        // the grid for placing the line: the frame, its diagonals, its middle and where the line goes
        if (LyricsGuide.adjusting) {
            val gridColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            val anchorColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val stroke = 2.dp.toPx()
                val mid = (frameTop + frameBottom) / 2f
                drawRect(gridColor, androidx.compose.ui.geometry.Offset(stroke / 2, frameTop), androidx.compose.ui.geometry.Size(w - stroke, frameBottom - frameTop), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, frameTop), androidx.compose.ui.geometry.Offset(w, frameBottom), stroke)
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(w, frameTop), androidx.compose.ui.geometry.Offset(0f, frameBottom), stroke)
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), stroke)
                if (kotlin.math.abs(anchorPx - mid) > 1f) {
                    drawLine(
                        anchorColor, androidx.compose.ui.geometry.Offset(0f, anchorPx), androidx.compose.ui.geometry.Offset(w, anchorPx), stroke,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                    )
                }
            }
        }
        // fine-tuning of the timing, in its own capsule so it never merges with the text
        // (its top is the bottom of the frame the line being sung is centred in)
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).onGloballyPositioned { capsuleTop = it.positionInParent().y },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            // the shift itself can be tapped and typed in (tapping ±0.1 s thirty times is no fun)
            var typing by remember { mutableStateOf(false) }
            val tight = PaddingValues(horizontal = 8.dp)
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { g.library.setLyricsOffset(t, l.offsetMs - 100) }, enabled = !demo, contentPadding = tight) { Text(tr("−0.1 с")) }
                TextButton(onClick = { typing = true }, enabled = !demo, contentPadding = tight) {
                    Text(
                        if (l.offsetMs == 0L) tr("сдвиг 0") else tr("сдвиг %+.1f с").format(l.offsetMs / 1000f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                }
                TextButton(onClick = { g.library.setLyricsOffset(t, l.offsetMs + 100) }, enabled = !demo, contentPadding = tight) { Text(tr("+0.1 с")) }
                // a narrow panel (landscape, split screen) gets an icon instead of the words, so no
                // button of the capsule is squeezed into an empty sliver
                if (narrowPanel) {
                    androidx.compose.material3.IconButton(onClick = { nav.playerOpen = false; nav.push(Screen.SettingsLyrics) }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.TextFields, tr("Вид текста"))
                    }
                } else {
                    TextButton(onClick = { nav.playerOpen = false; nav.push(Screen.SettingsLyrics) }, contentPadding = tight) { Text(tr("Вид текста"), maxLines = 1, softWrap = false) }
                }
            }
            if (typing) OffsetDialog(l.offsetMs, onSet = { g.library.setLyricsOffset(t, it) }, onDismiss = { typing = false })
        }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    nextStartMs: Long?,
    isActive: Boolean,
    passed: Boolean,
    position: (() -> Long)?,
    s: app.malachite.player.data.AppSettings,
    align: TextAlign,
    family: androidx.compose.ui.text.font.FontFamily,
    marked: Boolean = false,
    switchAtMs: Long? = null,
    /** How many lines away from the one being sung (for "blurs"). */
    distance: Int = 0,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val fontSize = s.lyricsFontSize
    val animMs = s.lyricsAnim.ms
    // Size and weight never change (that made the lines jump); the active line is scaled and
    // brightened smoothly instead.
    val pass = s.lyricsPass
    val scale by animateFloatAsState(
        // only "smaller and dimmer" changes the size; the other ways keep it
        if (isActive || !s.lyricsScaleActive || pass != app.malachite.player.data.LyricsPass.SHRINK) 1f else 0.9f,
        if (animMs == 0) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = if (animMs < 400) Spring.StiffnessMediumLow else Spring.StiffnessLow),
        label = "lyricScale",
    )
    val dimAlpha = s.lyricsInactiveAlpha.coerceIn(0.15f, 0.9f)
    val alpha by animateFloatAsState(
        when {
            isActive -> 1f
            passed -> when (pass) {
                app.malachite.player.data.LyricsPass.SLIDE -> dimAlpha
                app.malachite.player.data.LyricsPass.FADE -> dimAlpha * 0.35f
                else -> dimAlpha * 0.8f
            }
            else -> dimAlpha
        },
        if (animMs == 0) snap() else tween(durationMillis = (animMs * 0.75f).toInt(), easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )
    // "blurs": the further from the line being sung, the softer (Android 12+; nothing on older ones).
    // The radius is read only where the line is drawn: reading it here re-composed every line on
    // every frame of the change (half the frame rate on a 90 Hz phone).
    val blur = animateFloatAsState(
        if (pass == app.malachite.player.data.LyricsPass.BLUR && !isActive) minOf(distance, 4) * 1.4f else 0f,
        if (animMs == 0) snap() else tween(durationMillis = animMs, easing = FastOutSlowInEasing),
        label = "lyricBlur",
    )
    val base = s.lyricsActiveColor?.let { Color(it.toInt()) }?.takeIf { isActive }
        ?: MaterialTheme.colorScheme.onSurface
    val sung = s.lyricsSungColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    // The line being sung word by word: its words take their colour while they are sung. The
    // text is laid out once; on every frame only the colours are painted again (a new text with
    // new colours on every frame meant laying the line out 60 times a second, which a modest
    // phone felt).
    // "letter by letter": the colour runs through the whole line at an even pace (words timed or not)
    val sweep = isActive && position != null && s.lyricsWordFill == app.malachite.player.data.WordFill.SWEEP
    val fillWords = isActive && line.words.isNotEmpty() && position != null && !sweep
    val joined = remember(line) { line.words.joinToString(" ") { it.text } }
    val wordRanges = remember(line) {
        var at = 0
        line.words.map { w -> (at until at + w.text.length).also { at += w.text.length + 1 } }
    }
    val text = if (fillWords || (sweep && line.words.isNotEmpty())) joined else line.text
    val dim = base.copy(alpha = 0.55f)
    // a favourite line gets its mark under (or behind) the words, drawn line by line of the text
    val markColor = s.lineMarkColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // where each word is, once per layout (a word broken over two rows gets a box on each)
    val wordBoxes = remember(layout, fillWords) {
        val lay = layout
        if (!fillWords || lay == null || lay.layoutInput.text.text != joined) emptyList()
        else wordRanges.map { r ->
            if (r.isEmpty()) emptyList()
            else {
                val first = lay.getLineForOffset(r.first)
                val last = lay.getLineForOffset(r.last)
                (first..last).map { n ->
                    val from = maxOf(r.first, lay.getLineStart(n))
                    val to = minOf(r.last + 1, lay.getLineEnd(n))
                    lay.getPathForRange(from, to).getBounds()
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 8.dp, horizontal = 6.dp)) {
        Text(
            text,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                if (Build.VERSION.SDK_INT >= 31) {
                    renderEffect = LineBlur.effect(blur.value.dp.toPx())
                    clip = false
                }
                transformOrigin = TransformOrigin(
                    when (align) {
                        TextAlign.Center -> 0.5f
                        TextAlign.End -> 1f
                        else -> 0f
                    },
                    0.5f,
                )
            }.drawBehind {
                val lay = layout
                if (marked && lay != null) drawLineMark(lay, s.lineMarkStyle, markColor, s.lineMarkThickness.dp.toPx())
            }.drawWithContent {
                val lay = layout
                val read = position
                if (sweep && lay != null && read != null && lay.layoutInput.text.text == text) {
                    drawContent()
                    val shown = lay.layoutInput.text.text
                    val reach = app.malachite.core.lyrics.LineSweep.reach(shown, line, read(), nextStartMs, switchAtMs)
                    sweepLine(lay, reach, sung, fontSize.sp.toPx() * 0.9f, fontSize.sp.toPx() * 0.08f)
                    return@drawWithContent
                }
                if (!fillWords || lay == null || read == null || wordBoxes.size != line.words.size) {
                    drawContent()
                    return@drawWithContent
                }
                // read here, in drawing: a new position repaints the line, nothing is laid out again
                val pos = read()
                val reach = fontSize.sp.toPx() * 0.08f
                line.words.indices.forEach { i ->
                    val p = app.malachite.player.data.wordFillProgress(line, i, pos, nextStartMs, s.lyricsWordFill, switchAtMs)
                    val color = lerp(dim, sung, p)
                    wordBoxes[i].forEach { b ->
                        clipRect(b.left - reach, b.top, b.right + reach, b.bottom) { drawText(lay, color = color) }
                    }
                }
            },
            color = if (fillWords || sweep) dim else base,
            fontSize = fontSize.sp,
            fontWeight = if (s.lyricsBold) FontWeight.Bold else FontWeight.Medium,
            textAlign = align,
            fontFamily = family,
            lineHeight = (fontSize * 1.3f).sp,
        )
    }
}

/**
 * "Letter by letter, with a soft edge" ([app.malachite.core.lyrics.LineSweep]): the rows before
 * the edge take the sung colour, the row with the edge takes it up to the edge, fading out over
 * about one letter. A right-to-left row is coloured from the right. The dim text is already drawn.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.sweepLine(
    lay: androidx.compose.ui.text.TextLayoutResult,
    reach: Float,
    sung: Color,
    soft: Float,
    overhang: Float,
) {
    val n = lay.layoutInput.text.length
    if (n == 0 || reach <= 0f) return
    val clear = sung.copy(alpha = 0f)
    val edgeChar = reach.toInt().coerceAtMost(n - 1)
    val frac = (reach - reach.toInt()).coerceIn(0f, 1f)
    for (row in 0 until lay.lineCount) {
        val from = lay.getLineStart(row)
        val to = lay.getLineEnd(row)
        if (from > edgeChar && reach < n) break
        val left = lay.getLineLeft(row) - overhang
        val right = lay.getLineRight(row) + overhang
        clipRect(minOf(left, right), lay.getLineTop(row), maxOf(left, right), lay.getLineBottom(row)) {
            if (reach >= n || edgeChar >= to) {
                drawText(lay, color = sung)
            } else {
                val box = lay.getBoundingBox(edgeChar)
                val rtl = lay.getBidiRunDirection(edgeChar) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                // the middle of the soft edge, moving through the letter the edge is in
                val x = if (rtl) box.right - box.width * frac else box.left + box.width * frac
                val brush = if (rtl) {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to clear, 1f to sung, startX = x - soft / 2, endX = x + soft / 2)
                } else {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to sung, 1f to clear, startX = x - soft / 2, endX = x + soft / 2)
                }
                drawText(lay, brush = brush)
            }
        }
    }
}

/** The mark of a favourite line: a line under every row of the text, a marker behind it, or both. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineMark(
    layout: androidx.compose.ui.text.TextLayoutResult,
    style: app.malachite.player.data.LineMarkStyle,
    color: Color,
    thickness: Float,
) {
    val pad = 4.dp.toPx()
    for (n in 0 until layout.lineCount) {
        val left = layout.getLineLeft(n)
        val right = layout.getLineRight(n)
        if (right <= left) continue
        val top = layout.getLineTop(n)
        val bottom = layout.getLineBottom(n)
        val baseline = layout.getLineBaseline(n)
        if (style != app.malachite.player.data.LineMarkStyle.UNDERLINE) {
            val h = bottom - top
            drawRoundRect(
                color.copy(alpha = 0.28f),
                topLeft = androidx.compose.ui.geometry.Offset(left - pad, top + h * 0.12f),
                size = androidx.compose.ui.geometry.Size(right - left + pad * 2, h * 0.8f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad, pad),
            )
        }
        if (style != app.malachite.player.data.LineMarkStyle.MARKER) {
            val y = baseline + thickness * 1.2f
            drawRoundRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset(left, y),
                size = androidx.compose.ui.geometry.Size(right - left, thickness),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thickness / 2, thickness / 2),
            )
        }
    }
}

/**
 * Blur effects by whole pixels, made once: the change from one line to the next asks for a few
 * dozen radii, not a new effect for every line on every frame.
 */
private object LineBlur {
    private val made = HashMap<Int, androidx.compose.ui.graphics.RenderEffect>()

    fun effect(px: Float): androidx.compose.ui.graphics.RenderEffect? {
        val r = Math.round(px)
        if (r < 1) return null
        return made.getOrPut(r) { androidx.compose.ui.graphics.BlurEffect(r.toFloat(), r.toFloat(), androidx.compose.ui.graphics.TileMode.Decal) }
    }
}
