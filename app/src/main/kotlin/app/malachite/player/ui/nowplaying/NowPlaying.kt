package app.malachite.player.ui.nowplaying

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import app.malachite.core.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.malachite.core.library.TrackInfo
import app.malachite.core.text.TextFold
import app.malachite.player.MalachiteApp
import app.malachite.player.playback.PlayerUiState
import app.malachite.player.ui.DialogRequest
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.components.formatTime
import app.malachite.player.ui.theme.MalachiteTheme
import app.malachite.player.ui.theme.rememberCoverScheme
import app.malachite.player.ui.components.FrostLayer
import app.malachite.player.ui.components.rememberCoverState
import app.malachite.player.ui.theme.shade

/**
 * Position of the player, refreshed every frame while visible (smooth lyrics and slider).
 * Returned as a state: only the composable that actually reads it is redrawn every frame,
 * not the whole screen.
 */
@Composable
fun rememberPlayerPositionState(): androidx.compose.runtime.LongState {
    val g = MalachiteApp.graph
    val pos = remember { mutableLongStateOf(g.player.positionMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { pos.longValue = g.player.positionMs() }
        }
    }
    return pos
}

@Composable
fun rememberPlayerPosition(): Long = rememberPlayerPositionState().longValue

@Composable
fun NowPlayingScreen() {
    val g = MalachiteApp.graph
    val settings by g.settings.state.collectAsState()
    val st by g.player.state.collectAsState()
    val t = st.currentPath?.let { g.library.track(it) } ?: return
    val dark = isSystemInDarkTheme()
    // the colours were worked out ahead (CoverPrefetch), never here while the screen slides in:
    // that work, the cover read only now and the blur together held the opening up for a fifth of
    // a second, and the screen showed "no cover" in the settings' colours in the meantime
    val scheme = rememberCoverScheme(t, settings.shade(dark), settings.coverColorsInPlayer || settings.coverColorsEverywhere)
    MalachiteTheme(settings, scheme) {
        // Surface: text and icons take the readable colour of this (cover) theme, never default black.
        androidx.compose.material3.Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
        Box(Modifier.fillMaxSize().clickable(enabled = false) {}) {
            if (settings.coverColorsInPlayer) FrostLayer(t, 0.25f, settings.animations)
            NowPlayingContent(t, st)
        }
        }
    }
}

@Composable
private fun NowPlayingContent(t: TrackInfo, st: PlayerUiState) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val data by g.library.data.collectAsState()
    val fav = data.user(t.path).favorite
    var showLyrics by remember { mutableStateOf(false) }
    var speedSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val pos = rememberPlayerPositionState()
    /** Not null while a song from the online section is playing (it is not a file of the library). */
    val online = g.library.onlineItem(t.path)

    // The parts of the screen; how they are placed depends on the shape of the window.
    // placing the line by the grid shows the lyrics; leaving the player ends it
    LaunchedEffect(LyricsGuide.adjusting) { if (LyricsGuide.adjusting) showLyrics = true }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { LyricsGuide.adjusting = false } }

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.playerOpen = false }) { Icon(Icons.Filled.KeyboardArrowDown, tr("Свернуть"), Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Сейчас играет"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // its lowest pixels (the baseline) are the top of the frame the lyrics are centred in
                var albumBaseline by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                Text(t.album.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    onTextLayout = { albumBaseline = it.lastBaseline },
                    modifier = Modifier.clickable { nav.push(Screen.AlbumDetail(TextFold.strictKey(t.groupArtist) + "|" + TextFold.strictKey(t.album))) }
                        .onGloballyPositioned { c ->
                            val p = c.positionInRoot()
                            LyricsGuide.headerText = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + albumBaseline)
                        })
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Ещё")) }
                DropdownMenu(menu, { menu = false }) {
                    if (online != null) {
                        // A song from the internet is not a file of the library yet: no playlists,
                        // tags or cover editing until it is downloaded.
                        if (online.downloadUrl != null) {
                            DropdownMenuItem(text = { Text(tr("Скачать в главную папку")) }, onClick = {
                                menu = false
                                app.malachite.player.ui.screens.downloadOnline(online) { nav.toast(it) }
                            })
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (online.preview) tr("Это отрывок 30 секунд") else tr("Автор не открыл скачивание")) },
                                onClick = { menu = false }, enabled = false,
                            )
                        }
                    } else {
                        DropdownMenuItem(text = { Text(tr("Найти текст")) }, onClick = { menu = false; nav.show(DialogRequest.FindLyrics(t)) })
                        DropdownMenuItem(text = { Text(tr("Синхронизировать текст вручную")) }, onClick = { menu = false; nav.push(Screen.LyricsSync(t.path)) })
                        DropdownMenuItem(text = { Text(tr("Исправить строки текста")) }, onClick = { menu = false; nav.push(Screen.LyricsEdit(t.path)) })
                        DropdownMenuItem(text = { Text(if (t.hasArtwork) tr("Заменить обложку") else tr("Найти обложку")) }, onClick = { menu = false; nav.show(DialogRequest.FindCover(listOf(t))) })
                        DropdownMenuItem(text = { Text(tr("Изменить теги")) }, onClick = { menu = false; nav.show(DialogRequest.EditTags(t)) })
                        DropdownMenuItem(text = { Text(tr("В плейлист…")) }, onClick = { menu = false; nav.show(DialogRequest.AddToPlaylist(listOf(t.path))) })
                        DropdownMenuItem(text = { Text(tr("Отметить лучший момент здесь (${formatTime(pos.longValue)})")) }, onClick = {
                            menu = false
                            g.library.setBestMoment(t.path, pos.longValue)
                            nav.toast(tr("Лучший момент отмечен чертой на полосе перемотки: задержите ползунок у неё на пару секунд — он встанет точно на неё"))
                        })
                        if (data.user(t.path).bestMomentMs != null) {
                            DropdownMenuItem(text = { Text(tr("Убрать отметку лучшего момента")) }, onClick = { menu = false; g.library.setBestMoment(t.path, null) })
                        }
                        val disliked = data.user(t.path).disliked
                        DropdownMenuItem(text = { Text(if (disliked) tr("Снова нравится") else tr("Не нравится")) }, onClick = {
                            menu = false
                            g.library.setDisliked(listOf(t.path), !disliked)
                            nav.toast(if (disliked) tr("Песня снова играет вперемешку") else tr("Не нравится: песня не играет вперемешку и не попадает в умные плейлисты"))
                        })
                    }
                    DropdownMenuItem(text = { Text(tr("Эквалайзер и микшер")) }, onClick = { menu = false; nav.push(Screen.Equalizer) })
                    DropdownMenuItem(text = { Text(tr("Таймер сна")) }, onClick = { menu = false; sleepDialog = true })
                    if (online == null) {
                        DropdownMenuItem(text = { Text(tr("К исполнителю")) }, onClick = { menu = false; nav.push(Screen.ArtistDetail(TextFold.strictKey(t.groupArtist))) })
                        DropdownMenuItem(text = { Text(tr("Сведения о файле")) }, onClick = { menu = false; nav.show(DialogRequest.Info(t)) })
                    }
                }
            }
        }
    }
    val stage: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (showLyrics) {
                LyricsPanel(t, st.speed) { pos.longValue }
            } else {
                CoverArea(t) { showLyrics = true }
            }
        }
    }
    val settings by g.settings.state.collectAsState()
    // The visualizer under the cover (with the lyrics the screen is theirs). Its place is kept
    // while the music is paused too, so the cover does not jump when it starts; the bars fade.
    val barsShown by androidx.compose.animation.core.animateFloatAsState(
        if (st.isPlaying) 1f else 0f,
        androidx.compose.animation.core.tween(settings.visualizerFadeMs.coerceIn(0, 5000), easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "bars",
    )
    val bars: @Composable () -> Unit = {
        if (settings.visualizer && !showLyrics) Visualizer(
            settings.visualizerStyle,
            settings.visualizerOrder,
            Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp),
            low = MaterialTheme.colorScheme.primary,
            high = MaterialTheme.colorScheme.tertiary,
            tip = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface, 0.7f),
            alpha = { barsShown },
        )
    }
    val title: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.displayArtist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { nav.push(Screen.ArtistDetail(TextFold.strictKey(t.groupArtist))) })
            }
            if (online == null) IconButton(onClick = { g.library.toggleFavorite(t.path) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, tr("Избранное"), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    // the best moment: marked by the user, or the chorus found in the synced lyrics
    val marked = data.user(t.path).bestMomentMs
    val chorus by androidx.compose.runtime.produceState<Long?>(null, t.path, data.user(t.path).lyricsId, data.user(t.path).lyricsOffsetMs) {
        value = if (online != null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { g.library.loadLyrics(t)?.let { app.malachite.core.lyrics.BestMoment.guess(it) } }.getOrNull()
        }
    }
    val seek: @Composable () -> Unit = {
        SeekBar({ pos.longValue }, st.durationMs, best = marked ?: chorus) { g.player.seekTo(it) }
    }
    val transport: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { g.player.setShuffle(!st.shuffle) }) {
                Icon(Icons.Filled.Shuffle, tr("Перемешать"), tint = if (st.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { g.player.previous() }) { Icon(Icons.Filled.SkipPrevious, tr("Назад"), Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = { g.player.toggle() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.isPlaying) tr("Пауза") else tr("Играть"), Modifier.size(40.dp))
            }
            IconButton(onClick = { g.player.next() }) { Icon(Icons.Filled.SkipNext, tr("Дальше"), Modifier.size(36.dp)) }
            IconButton(onClick = { g.player.cycleRepeat() }) {
                Icon(
                    if (st.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, tr("Повтор"),
                    tint = if (st.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val bottom: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { speedSheet = true }) {
                Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text("%.2fx".format(st.speed) + if (kotlin.math.abs(st.pitchSemitones) > 0.01f) " • %+.1f".format(st.pitchSemitones) else "")
            }
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(Icons.Filled.Lyrics, tr("Текст"), tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { sleepDialog = true }) {
                Icon(Icons.Filled.Bedtime, tr("Таймер сна"), tint = if (st.sleepAtMs > 0 || st.sleepAfterTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { queueSheet = true }) { Icon(Icons.Filled.QueueMusic, tr("Очередь")) }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Landscape phones, tablets on their side, laptop windows: the cover (or the lyrics) on the
        // left, everything else on the right. Otherwise the usual column, not wider than is comfortable.
        val wide = maxWidth > maxHeight && maxWidth >= 560.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { stage() }
                Spacer(Modifier.width(18.dp))
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    header()
                    bars()
                    title()
                    seek()
                    transport()
                    bottom()
                }
            }
        } else {
            Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().padding(horizontal = 18.dp)) {
                header()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { stage() }
                bars()
                title()
                seek()
                transport()
                bottom()
            }
        }
        // the slider lies over the buttons, so the lyrics keep exactly the place they normally have
        if (LyricsGuide.adjusting) LyricsPositionPanel(Modifier.align(Alignment.BottomCenter))
    }

    if (speedSheet) SpeedPitchSheet(st) { speedSheet = false }
    if (queueSheet) QueueSheet(st) { queueSheet = false }
    if (sleepDialog) SleepDialog(st) { sleepDialog = false }
}

/** What the cover place shows while the file is still read, and when it is known there is no cover. */
private object CoverReading
private object CoverMissing

@Composable
private fun CoverArea(t: TrackInfo, onLyrics: () -> Unit) {
    val nav = LocalNavigator.current
    val animate = MalachiteApp.graph.settings.state.collectAsState().value.animations
    val cover = rememberCoverState(t, 900)
    // The picture (for a moment a smaller copy while the big one is read), a quiet empty square
    // while the file is still read, and "no cover" only once it is known there is none – it used to
    // flash up on every opening before the picture came.
    val look: Any = cover.bitmap ?: if (cover.settled) CoverMissing else CoverReading
    // the biggest square that fits: also when the place is wider than it is tall
    Box(Modifier.aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(
            look,
            animationSpec = if (animate) androidx.compose.animation.core.tween(260) else androidx.compose.animation.core.snap(),
            label = "cover",
        ) { shown ->
            when (shown) {
                is android.graphics.Bitmap -> Image(
                    shown.asImageBitmap(), tr("Обложка"),
                    Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).clickable(onClick = onLyrics),
                    contentScale = ContentScale.Crop,
                )
                CoverMissing -> {
                    // No cover in the file: offer to find one and embed it into the file itself.
                    Card(
                        Modifier.fillMaxSize(), shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.MusicNote, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(10.dp))
                            Text(tr("В файле нет обложки"), style = MaterialTheme.typography.titleMedium)
                            Text(tr("Найти обложку?"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { nav.show(DialogRequest.FindCover(listOf(t))) }) {
                                Icon(Icons.Filled.ImageSearch, null); Spacer(Modifier.width(6.dp)); Text(tr("Найти и вшить в файл"))
                            }
                            TextButton(onClick = onLyrics) { Text(tr("Показать текст")) }
                        }
                    }
                }
                else -> Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
private fun SeekBar(position: () -> Long, duration: Long, best: Long? = null, onSeek: (Long) -> Unit) {
    // where a finger holds the bar (0..1), or -1
    var held by remember { mutableFloatStateOf(-1f) }
    // Where the bar was let go: shown until the player has got there (or a moment has passed).
    // The player needs a little time to start from the new place, and the bar must not jump back
    // to the old one meanwhile.
    var target by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(target) {
        if (target < 0) return@LaunchedEffect
        val until = android.os.SystemClock.uptimeMillis() + 1500
        while (android.os.SystemClock.uptimeMillis() < until && kotlin.math.abs(position() - target) > 700) {
            withFrameMillis { }
        }
        target = -1L
    }
    val pos = when {
        held >= 0f -> (held * duration).toLong()
        target >= 0 -> target
        else -> position()
    }
    val frac = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column {
        SeekTrack(
            fraction = frac,
            enabled = duration > 0,
            onMove = { held = it },
            onDone = { f ->
                val to = (f * duration).toLong()
                target = to
                held = -1f
                onSeek(to)
            },
            onCancel = { held = -1f },
            best = if (best != null && duration > 0) (best.toFloat() / duration).coerceIn(0f, 1f) else -1f,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(pos), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The seek bar itself, drawn like the standard one. The standard slider sometimes ended a tap
 * without reporting the new place, and the song then jumped to where the previous seek had gone
 * ("it jumps back"). Here every tap and every drag takes the place under the finger itself, so
 * nothing from an earlier touch can be used. Seen by TalkBack (and by the device checks) as a
 * seek bar that can be set.
 */
@Composable
private fun SeekTrack(fraction: Float, enabled: Boolean, onMove: (Float) -> Unit, onDone: (Float) -> Unit, onCancel: () -> Unit, best: Float = -1f) {
    val active = MaterialTheme.colorScheme.primary
    // the best moment: the accent mixed with the text colour – of the theme, yet unlike the progress
    val mark = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface, 0.6f)
    val bestNow by androidx.compose.runtime.rememberUpdatedState(best)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val inactive = MaterialTheme.colorScheme.secondaryContainer
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    val cancel by androidx.compose.runtime.rememberUpdatedState(onCancel)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { SEEK_THUMB_W.toPx() / 2 }
    val near = with(density) { 28.dp.toPx() }
    val jitter = with(density) { 4.dp.toPx() }
    // the place under the finger: the track runs from half a thumb to half a thumb from the edges
    fun at(x: Float, width: Int): Float {
        val track = (width - 2 * inset).coerceAtLeast(1f)
        return ((x - inset) / track).coerceIn(0f, 1f)
    }
    // The best moment catches the thumb only when the finger stays still near it for a while: a
    // place close to the chorus but not on it can be chosen as before.
    val drag = remember { SeekDrag() }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        while (holding) {
            kotlinx.coroutines.delay(100)
            val b = bestNow
            val still = android.os.SystemClock.uptimeMillis() - drag.stillSince
            if (!drag.caught && b in 0f..1f && kotlin.math.abs(drag.at - b) * drag.track <= near && still >= BEST_HOLD_MS) {
                drag.caught = true
                drag.at = b
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                move(b)
            }
        }
    }
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                setProgress { v ->
                    if (enabled) done(v.coerceIn(0f, 1f))
                    enabled
                }
            }
            .progressSemantics(fraction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { done(at(it.x, size.width)) })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        drag.track = (size.width - 2 * inset).coerceAtLeast(1f)
                        drag.at = at(it.x, size.width)
                        drag.stillX = it.x
                        drag.stillSince = android.os.SystemClock.uptimeMillis()
                        drag.caught = false
                        holding = true
                        move(drag.at)
                    },
                    onDragEnd = { holding = false; done(drag.at) },
                    onDragCancel = { holding = false; cancel() },
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x
                    // the finger moved (more than a tremble): the wait starts again, a caught moment lets go
                    if (kotlin.math.abs(x - drag.stillX) > jitter) {
                        drag.stillX = x
                        drag.stillSince = android.os.SystemClock.uptimeMillis()
                        drag.caught = false
                    }
                    if (!drag.caught) {
                        drag.at = at(x, size.width)
                        move(drag.at)
                    }
                }
            },
    ) {
        val trackH = 16.dp.toPx()
        val thumbW = SEEK_THUMB_W.toPx()
        val thumbH = 44.dp.toPx()
        val gap = 6.dp.toPx()
        val cy = size.height / 2
        val x = inset + (size.width - 2 * inset) * fraction
        val outer = androidx.compose.ui.geometry.CornerRadius(trackH / 2)
        val inner = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        fun part(from: Float, to: Float, leftR: androidx.compose.ui.geometry.CornerRadius, rightR: androidx.compose.ui.geometry.CornerRadius, color: androidx.compose.ui.graphics.Color) {
            if (to - from < 1f) return
            val r = androidx.compose.ui.geometry.RoundRect(
                left = from, top = cy - trackH / 2, right = to, bottom = cy + trackH / 2,
                topLeftCornerRadius = leftR, topRightCornerRadius = rightR,
                bottomRightCornerRadius = rightR, bottomLeftCornerRadius = leftR,
            )
            drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(r) }, color)
        }
        part(0f, x - thumbW / 2 - gap, outer, inner, active)
        val restFrom = x + thumbW / 2 + gap
        part(restFrom, size.width, inner, outer, inactive)
        // the small dot at the end of the track, as on the standard bar
        if (size.width - restFrom > trackH) drawCircle(active, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width - trackH / 2, cy))
        // the best moment: a slim notch across the track; the thumb jumps onto it when held still near it
        if (best in 0f..1f && kotlin.math.abs(best - fraction) * (size.width - 2 * inset) > thumbW / 2 + gap) {
            val bx = inset + (size.width - 2 * inset) * best
            val w = 3.dp.toPx()
            val h = trackH + 12.dp.toPx()
            drawRoundRect(
                mark,
                topLeft = androidx.compose.ui.geometry.Offset(bx - w / 2, cy - h / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
        drawRoundRect(
            active,
            topLeft = androidx.compose.ui.geometry.Offset(x - thumbW / 2, cy - thumbH / 2),
            size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2),
        )
    }
}

private val SEEK_THUMB_W = 4.dp

/** How long the finger has to stay still near the best moment before the thumb jumps onto it. */
private const val BEST_HOLD_MS = 1800L

/** Where a drag on the seek bar is, and whether the finger rests near the best moment. */
private class SeekDrag {
    var at = 0f
    var track = 1f
    var stillX = 0f
    var stillSince = 0L
    var caught = false
}

@Composable
fun SpeedPitchSheet(st: PlayerUiState, onDismiss: () -> Unit) {
    val g = MalachiteApp.graph
    val settings by g.settings.state.collectAsState()
    var speed by remember { mutableFloatStateOf(st.speed) }
    var pitch by remember { mutableFloatStateOf(st.pitchSemitones) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Скорость: %.2fx").format(speed), style = MaterialTheme.typography.titleMedium)
            Slider(speed, { speed = (Math.round(it * 20) / 20f); g.player.setSpeedPitch(speed, pitch) }, valueRange = 0.25f..3f)
            // Wrapping rows of equal chips: nothing gets squeezed on narrow screens.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                    FilterChip(
                        selected = speed == v,
                        onClick = { speed = v; g.player.setSpeedPitch(speed, pitch) },
                        label = { Text(speedLabel(v), Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) },
                    )
                }
            }
            HorizontalDivider()
            Text(
                if (settings.linkPitchToSpeed) tr("Питч следует за скоростью") else tr("Питч: %+.1f полутона").format(pitch),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                pitch, { pitch = (Math.round(it * 2) / 2f); g.player.setSpeedPitch(speed, pitch) },
                valueRange = -12f..12f, enabled = !settings.linkPitchToSpeed,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f).forEach { v ->
                    FilterChip(
                        selected = pitch == v, enabled = !settings.linkPitchToSpeed,
                        onClick = { pitch = v; g.player.setSpeedPitch(speed, pitch) },
                        label = {
                            Text(if (v > 0) "+${v.toInt()}" else "${v.toInt()}", Modifier.width(26.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.linkPitchToSpeed, { v -> g.settings.update { it.copy(linkPitchToSpeed = v) }; g.player.setSpeedPitch(speed, pitch) })
                Spacer(Modifier.width(10.dp))
                Text(tr("Связать питч со скоростью (эффект «nightcore» / «slowed»)"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.rememberSpeedPerSong, { v -> g.settings.update { it.copy(rememberSpeedPerSong = v) } })
                Spacer(Modifier.width(10.dp))
                Text(tr("Запоминать скорость и питч для каждой песни"))
            }
            OutlinedButton(onClick = { speed = 1f; pitch = 0f; g.player.setSpeedPitch(1f, 0f) }) { Text(tr("Сбросить (1x, без питча)")) }
        }
    }
}

private fun speedLabel(v: Float): String = (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "x"

@Composable
fun QueueSheet(st: PlayerUiState, onDismiss: () -> Unit) {
    val g = MalachiteApp.graph
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (st.index - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(tr("Очередь • ${st.queue.size}"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().height(480.dp), state = state) {
            itemsIndexed(st.queue) { i, path ->
                val t = g.library.track(path)
                val current = i == st.index
                Row(
                    Modifier.fillMaxWidth().clickable { g.player.skipTo(i) }
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.displayTitle ?: path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        Text(t?.displayArtist.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { g.player.moveInQueue(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, tr("Выше")) }
                    IconButton(onClick = { g.player.moveInQueue(i, i + 1) }, enabled = i < st.queue.size - 1) { Icon(Icons.Filled.ArrowDownward, tr("Ниже")) }
                    IconButton(onClick = { g.player.removeFromQueue(i) }) { Icon(Icons.Filled.Close, tr("Убрать")) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SleepDialog(st: PlayerUiState, onDismiss: () -> Unit) {
    val g = MalachiteApp.graph
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Таймер сна")) },
        text = {
            Column {
                if (st.sleepAtMs > 0) {
                    val left = ((st.sleepAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    Text(tr("Остановка через ~$left мин"), color = MaterialTheme.colorScheme.primary)
                }
                if (st.sleepAfterTrack) Text(tr("Остановка после текущей песни"), color = MaterialTheme.colorScheme.primary)
                listOf(10, 15, 30, 45, 60, 90, 120).forEach { m ->
                    TextButton(onClick = { g.player.setSleepTimer(m); onDismiss() }) { Text(tr("Через $m минут")) }
                }
                TextButton(onClick = { g.player.sleepAfterCurrentTrack(); onDismiss() }) { Text(tr("После этой песни")) }
            }
        },
        confirmButton = { TextButton(onClick = { g.player.setSleepTimer(0); onDismiss() }) { Text(tr("Выключить таймер")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть")) } },
    )
}

/** The slider for placing the line being sung, shown over the player's buttons together with the grid. */
@Composable
private fun LyricsPositionPanel(modifier: Modifier) {
    val g = MalachiteApp.graph
    val settings by g.settings.state.collectAsState()
    val shift = settings.lyricsAnchorShift
    androidx.compose.material3.Card(
        modifier.fillMaxWidth().padding(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Положение строки"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Рамка — от названия альбома вверху до «таблетки» сдвига внизу. Строка, которая поётся, встаёт на пунктир (или на середину рамки)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val pct = kotlin.math.round(shift * 100).toInt()
            Text(
                when {
                    pct == 0 -> tr("Ровно посередине")
                    pct < 0 -> tr("Выше середины на ${-pct}%")
                    else -> tr("Ниже середины на ${pct}%")
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Выше"), style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = shift,
                    onValueChange = { v -> g.settings.update { it.copy(lyricsAnchorShift = (kotlin.math.round(v * 100) / 100f)) } },
                    valueRange = -0.4f..0.4f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(tr("Ниже"), style = MaterialTheme.typography.labelMedium)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { g.settings.update { it.copy(lyricsAnchorShift = 0f) } }) { Text(tr("Посередине")) }
                androidx.compose.material3.Button(onClick = { LyricsGuide.adjusting = false }) { Text(tr("Готово")) }
            }
        }
    }
}
