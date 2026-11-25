package app.malachite.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.malachite.core.heal.HealKind
import app.malachite.core.i18n.tr
import app.malachite.core.library.TrackInfo
import app.malachite.player.MalachiteApp
import app.malachite.player.data.Stats
import app.malachite.player.heal.HealingEngine
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.components.CoverImage
import app.malachite.player.ui.settings.Group
import app.malachite.player.ui.settings.Hint
import app.malachite.player.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "1 ч 5 мин", "42 мин". */
private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) tr("${min / 60} ч ${min % 60} мин") else tr("$min мин")
}

/**
 * Statistics: how much and when music is listened to, what is played most, what healing has
 * fixed, how complete the library is. Everything is counted on the device and stays there.
 */
@Composable
fun StatsScreen() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val stats by Stats.state.collectAsState()
    val tracks by g.library.tracks.collectAsState()
    val albums by g.library.albums.collectAsState()
    val artists by g.library.artists.collectAsState()
    val data by g.library.data.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Stats.ensureLoaded() } }

    val today = LocalDate.now()
    val days = remember(stats, today) { (13 downTo 0).map { today.minusDays(it.toLong()) } }
    val dayValues = days.map { stats.dayMs[it.toString()] ?: 0L }
    val week = dayValues.takeLast(7).sum()
    val plays = remember(data) { data.tracks.values.sumOf { it.playCount } }

    SettingsPage {
        // ---- the headline numbers
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(tr("Сегодня"), duration(dayValues.last()), Modifier.weight(1f))
            Tile(tr("За 7 дней"), duration(week), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(tr("Всего"), duration(stats.totalMs), Modifier.weight(1f))
            Tile(tr("Прослушиваний"), plays.toString(), Modifier.weight(1f))
        }

        // ---- listening per day
        Group(tr("Время прослушивания, 14 дней")) {
            var picked by rememberSaveable { mutableStateOf<Int?>(null) }
            val p = picked
            Text(
                if (p != null) "${days[p].dayOfMonth}.%02d: ".format(days[p].monthValue) + duration(dayValues[p])
                else tr("В среднем ${duration(dayValues.sum() / 14)} в день"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Bars(dayValues, picked, { picked = it }, 120.dp)
            AxisLabels(listOf(0 to "${days.first().dayOfMonth}.%02d".format(days.first().monthValue), 13 to tr("сегодня")), 14)
        }

        // ---- hours of the day
        Group(tr("Когда вы слушаете")) {
            var picked by rememberSaveable { mutableStateOf<Int?>(null) }
            val hours = stats.hourMs.take(24).let { it + List(24 - it.size) { 0L } }
            val p = picked
            Text(
                if (p != null) "%02d:00–%02d:00: ".format(p, (p + 1) % 24) + duration(hours[p])
                else hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
                    ?.let { tr("Чаще всего — около %02d:00").format(it.index) } ?: tr("Пока нечего показать"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Bars(hours, picked, { picked = it }, 80.dp)
            AxisLabels(listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18", 23 to "23"), 24)
        }

        // ---- most played
        val topSongs = remember(tracks, data) {
            tracks.map { it to data.user(it.path).playCount }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        }
        val topAlbums = remember(albums, data) {
            albums.filter { !it.hasNoAlbum() }.map { a -> a to a.tracks.sumOf { data.user(it.path).playCount } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        }
        val topArtists = remember(artists, data) {
            artists.map { a -> a to a.tracks.sumOf { data.user(it.path).playCount } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        }
        Group(tr("Чаще всего: песни")) {
            if (topSongs.isEmpty()) Hint(tr("Послушайте что-нибудь — здесь появятся любимые песни."))
            val max = topSongs.maxOfOrNull { it.second } ?: 1
            topSongs.forEachIndexed { i, (t, n) ->
                Ranked(t, t.displayTitle, t.displayArtist, n, max) { g.player.play(topSongs.map { it.first }, i) }
            }
        }
        if (topAlbums.isNotEmpty()) Group(tr("Чаще всего: альбомы")) {
            val max = topAlbums.maxOf { it.second }
            topAlbums.forEach { (a, n) -> Ranked(a.cover, a.title, a.artist, n, max) { nav.push(Screen.AlbumDetail(a.key)) } }
        }
        if (topArtists.isNotEmpty()) Group(tr("Чаще всего: исполнители")) {
            val max = topArtists.maxOf { it.second }
            topArtists.forEach { (a, n) -> Ranked(a.albums.firstOrNull()?.cover, a.name, tr("${a.tracks.size} песен"), n, max) { nav.push(Screen.ArtistDetail(a.key)) } }
        }

        // ---- healing
        Group(tr("Исцеление за всё время")) {
            val total = stats.healed.values.sum()
            Text(tr("Исправлено: $total"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (total == 0) Hint(tr("Здесь будет видно, сколько исправило исцеление."))
            val max = stats.healed.values.maxOrNull() ?: 1
            stats.healed.entries.sortedByDescending { it.value }.forEach { (kind, n) ->
                val title = runCatching { HealingEngine.title(HealKind.valueOf(kind)) }.getOrDefault(kind).replaceFirstChar { it.uppercase() }
                Meter(title, n.toString(), n.toFloat() / max)
            }
        }

        // ---- the library
        Group(tr("Библиотека")) {
            val n = tracks.size
            Text(
                tr("Песен: $n • альбомов: ${albums.count { !it.hasNoAlbum() }} • исполнителей: ${artists.size}") + " • " + duration(tracks.sumOf { it.durationMs }),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (n > 0) {
                val covers = tracks.count { it.hasArtwork }
                val lyrics = tracks.count { data.user(it.path).lyricsId != null }
                val withAlbum = tracks.count { it.album.isNotBlank() }
                Meter(tr("С обложкой"), "$covers / $n", covers.toFloat() / n)
                Meter(tr("С текстом"), "$lyrics / $n", lyrics.toFloat() / n)
                Meter(tr("С альбомом"), "$withAlbum / $n", withAlbum.toFloat() / n)
            }
        }
        if (stats.since > 0) {
            val since = Instant.ofEpochMilli(stats.since).atZone(ZoneId.systemDefault()).toLocalDate()
            Hint(tr("Время прослушивания считается с ${since.dayOfMonth}.%02d.${since.year}. Статистика хранится только на этом устройстве, в главной папке.").format(since.monthValue))
        }
    }
}

/** A headline number. */
@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // smaller when it is long, never cut («36 ч 21» instead of «36 ч 21 мин»)
            app.malachite.player.ui.components.FitText(value, MaterialTheme.typography.headlineSmall, maxLines = 1, minSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * One series of columns on a baseline: thin, rounded only at the top, with a small gap between
 * them. A tap on a column (the whole height is the target) shows its value above the chart. Held
 * for a moment (as long as the tab panel wants), the finger slides over the columns: the one under
 * it is picked, the light glides from column to column and each step is felt.
 */
@Composable
private fun Bars(values: List<Long>, selected: Int?, onSelect: (Int?) -> Unit, height: Dp) {
    val settings by MalachiteApp.graph.settings.state.collectAsState()
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val primary = MaterialTheme.colorScheme.primary
    val dimmed = primary.copy(alpha = 0.4f)
    val band = primary.copy(alpha = 0.14f)
    val haptics = LocalHapticFeedback.current
    val n = values.size
    // where the light is: it glides to the picked column like the pill of the tab panel
    val at = remember { Animatable(selected?.toFloat() ?: Float.NaN) }
    val shown by animateFloatAsState(if (selected != null) 1f else 0f, tween(220), label = "barLight")
    val anim = if (settings.animations) settings.tabSlideAnim else app.malachite.player.data.TabSlideAnim.NONE
    val spec: AnimationSpec<Float> = when (anim) {
        app.malachite.player.data.TabSlideAnim.SPRING -> spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
        app.malachite.player.data.TabSlideAnim.SMOOTH -> tween(320, easing = FastOutSlowInEasing)
        app.malachite.player.data.TabSlideAnim.FAST -> tween(120, easing = FastOutSlowInEasing)
        app.malachite.player.data.TabSlideAnim.NONE -> tween(0)
    }
    LaunchedEffect(selected) {
        val to = selected?.toFloat() ?: return@LaunchedEffect
        if (at.value.isNaN()) at.snapTo(to) else at.animateTo(to, spec)
    }
    val pick by rememberUpdatedState(onSelect)
    val picked by rememberUpdatedState(selected)
    val hold = settings.tabSlideDelayMs.toLong().coerceIn(150L, 1000L)
    Column {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n, hold) {
                fun indexAt(x: Float) = (x / size.width * n).toInt().coerceIn(0, n - 1)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // released early: a tap; moved early: the page is being scrolled
                    val early: Boolean? = withTimeoutOrNull(hold) {
                        var result: Boolean? = null
                        while (result == null) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            result = when {
                                c == null -> false
                                !c.pressed -> true
                                (c.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                                else -> null
                            }
                        }
                        result
                    }
                    when (early) {
                        true -> {
                            val i = indexAt(down.position.x)
                            pick(if (picked == i) null else i)
                            return@awaitEachGesture
                        }
                        false -> return@awaitEachGesture
                        null -> Unit
                    }
                    // held: from now on the finger belongs to the chart (the page does not scroll)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var current = indexAt(down.position.x)
                    pick(current)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                        val i = indexAt(c.position.x)
                        if (i != current) {
                            current = i
                            pick(i)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            },
        ) {
            if (n == 0) return@Canvas
            val slot = size.width / n
            val gap = 1.dp.toPx()
            val corner = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            val light = at.value
            // the soft band behind the picked column moves with the light
            if (!light.isNaN() && shown > 0.01f) {
                drawRoundRect(band.copy(alpha = band.alpha * shown), androidx.compose.ui.geometry.Offset(light * slot, 0f),
                    androidx.compose.ui.geometry.Size(slot, size.height), corner)
            }
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = size.height * (v.toFloat() / max).coerceIn(0.03f, 1f)
                // lit by closeness to the light: a column it passes brightens and dims smoothly
                val near = if (light.isNaN()) 0f else (1f - kotlin.math.abs(i - light)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(primary, androidx.compose.ui.graphics.lerp(dimmed, primary, near), shown)
                val rect = androidx.compose.ui.geometry.Rect(i * slot + gap, size.height - h, (i + 1) * slot - gap, size.height)
                val path = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, topLeft = corner, topRight = corner))
                }
                drawPath(path, color)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * A few labels under the columns, each under its own column; the rest stay unlabelled. A label
 * may be wider than its column («13.11», «сегодня» were cut to «13.1», «сег»): the first one
 * starts at its column, the last one ends at it, the others are centred.
 */
@Composable
private fun AxisLabels(labels: List<Pair<Int, String>>, count: Int) {
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until count) {
            val align = when (i) {
                0 -> Alignment.Start
                count - 1 -> Alignment.End
                else -> Alignment.CenterHorizontally
            }
            Box(Modifier.weight(1f)) {
                labels.firstOrNull { it.first == i }?.let {
                    Text(
                        it.second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(align, unbounded = true),
                    )
                }
            }
        }
    }
}

/** A row of a top list: cover, names, the count, and a bar against the first place. */
@Composable
private fun Ranked(cover: TrackInfo?, title: String, subtitle: String, count: Int, max: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(cover, size = 44.dp, corner = 8.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count.toString(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { count.toFloat() / max }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A labelled bar, like the progress of healing. */
@Composable
private fun Meter(label: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
