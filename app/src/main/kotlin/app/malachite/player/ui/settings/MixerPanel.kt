package app.malachite.player.ui.settings

import app.malachite.core.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.malachite.player.MalachiteApp
import app.malachite.player.playback.Mixer
import app.malachite.player.playback.MixerKnobs
import app.malachite.player.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The DJ mixer on the Equalizer screen: a meter of the left and the right channel and the knobs of
 * a mixer's channel. A turned knob is heard at once; it is saved when the finger lets go.
 */
@Composable
fun MixerGroup() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    var knobs by remember { mutableStateOf(s.mixer) }
    // a reset or a change made elsewhere
    LaunchedEffect(s.mixer) { knobs = s.mixer }
    fun live(k: MixerKnobs) {
        knobs = k
        Mixer.knobs = k
    }
    fun save() {
        val k = knobs
        g.settings.update { it.copy(mixer = k) }
    }
    Group(tr("DJ-микшер")) {
        SwitchRow(
            tr("Включён"), s.mixerOn,
            tr("Ручки как у канала диджейского пульта. Когда все ручки посередине, звук не меняется ни на бит"),
        ) { v ->
            Mixer.on = v
            g.settings.update { it.copy(mixerOn = v) }
        }
        LevelMeter(Modifier.fillMaxWidth().height(30.dp))
        val on = s.mixerOn
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knob("TRIM", knobs.trim, dbText(Mixer.trimDb(knobs.trim)), { live(knobs.copy(trim = it)) }, ::save, enabled = on)
            Knob("HI", knobs.hi, bandText(knobs.hi), { live(knobs.copy(hi = it)) }, ::save, enabled = on, alarm = knobs.hi <= Mixer.KILL)
            Knob("MID", knobs.mid, bandText(knobs.mid), { live(knobs.copy(mid = it)) }, ::save, enabled = on, alarm = knobs.mid <= Mixer.KILL)
            Knob("LOW", knobs.low, bandText(knobs.low), { live(knobs.copy(low = it)) }, ::save, enabled = on, alarm = knobs.low <= Mixer.KILL)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knob("FILTER", knobs.filter, filterText(knobs.filter), { live(knobs.copy(filter = it)) }, ::save, enabled = on)
            Knob("BAL", knobs.balance, balanceText(knobs.balance), { live(knobs.copy(balance = it)) }, ::save, enabled = on)
        }
        Hint(tr("TRIM — громкость, HI — высокие, MID — средние, LOW — басы. Ведите по ручке пальцем вверх или вправо — больше, вниз или влево — меньше; двойное нажатие возвращает её в середину. До упора влево HI, MID и LOW убирают свою часть звука целиком (KILL). FILTER: влево остаются только басы, вправо — только высокие. BAL — левый и правый канал."))
        Hint(tr("Поворот ручки слышен через долю секунды: плеер готовит звук немного заранее. Красные огоньки индикатора — звук упирается в потолок, убавьте TRIM."))
        OutlinedButton(onClick = { live(MixerKnobs()); save() }, enabled = !knobs.centred) { Text(tr("Все ручки в середину")) }
    }
}

private fun dbText(db: Float) = tr("%+.1f дБ").format(db)

private fun bandText(v: Float) = if (v <= Mixer.KILL) "KILL" else dbText(Mixer.bandDb(v))

private fun filterText(v: Float): String {
    val hz = Mixer.filterHz(v) ?: return tr("выкл")
    val f = if (hz >= 1000f) tr("%.1f кГц").format(hz / 1000f) else tr("%.0f Гц").format(hz)
    return (if (v < 0f) "LPF " else "HPF ") + f
}

private fun balanceText(v: Float) = when {
    abs(v) < 0.005f -> tr("центр")
    v < 0f -> "L ${(-v * 100).roundToInt()}%"
    else -> "R ${(v * 100).roundToInt()}%"
}

/**
 * A round knob: -1 … 1 with the middle at the top, turned by sliding a finger up / right (more) or
 * down / left (less). It stops softly in the middle, a double tap brings it back there.
 */
@Composable
fun Knob(
    label: String,
    value: Float,
    text: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    alarm: Boolean = false,
    diameter: Dp = 60.dp,
) {
    val haptic = LocalHapticFeedback.current
    val now by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val accent = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val body = MaterialTheme.colorScheme.surfaceVariant
    val pointer = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(modifier.width(diameter + 16.dp).alpha(if (enabled) 1f else 0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        Canvas(
            Modifier.size(diameter)
                .semantics {
                    contentDescription = "$label: $text"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -1f..1f)
                    if (enabled) setProgress { v -> change(v.coerceIn(-1f, 1f)); done(); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        change(0f)
                        done()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    })
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var raw = 0f
                    detectDragGestures(
                        onDragStart = { raw = now },
                        onDragEnd = { done() },
                        onDragCancel = { done() },
                    ) { c, drag ->
                        c.consume()
                        val before = now
                        raw = (raw + (drag.x - drag.y) / 220.dp.toPx()).coerceIn(-1f, 1f)
                        // a soft stop in the middle
                        val v = if (abs(raw) < 0.05f) 0f else raw
                        if (v != before) {
                            if ((v == 0f) != (before == 0f) || (v <= Mixer.KILL) != (before <= Mixer.KILL)) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            change(v)
                        }
                    }
                },
        ) {
            val stroke = 4.dp.toPx()
            val r = size.minDimension / 2 - stroke
            val c = center
            val arcTopLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(2 * r, 2 * r)
            // the travel: three quarters of a circle, from the lower left over the top to the lower right
            drawArc(track, 135f, 270f, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val sweep = 135f * value
            if (abs(sweep) > 0.5f) {
                drawArc(accent, if (sweep > 0f) 270f else 270f + sweep, abs(sweep), false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // the knob itself, lit from the upper left, with its pointer
            val br = r - stroke * 1.7f
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(body, Color.White, 0.16f), body, lerp(body, Color.Black, 0.3f)),
                    center = c - Offset(br * 0.35f, br * 0.35f), radius = br * 1.7f,
                ),
                br, c,
            )
            drawCircle(pointer.copy(alpha = 0.12f), br, c, style = Stroke(1.dp.toPx()))
            val angle = Math.toRadians(270.0 + 135.0 * value)
            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(pointer, c + dir * (br * 0.3f), c + dir * (br * 0.85f), 3.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(
            text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The level of the left and the right channel as lit segments, like on a mixing desk: the body is
 * the loudness, the single segment above it the highest peak of the last moment. The top segments
 * are red: there the sound touches the ceiling.
 */
@Composable
fun LevelMeter(modifier: Modifier) {
    val read = remember { FloatArray(4) }
    val shown = remember { FloatArray(2) }
    val peak = remember { FloatArray(2) }
    val hold = remember { FloatArray(2) }
    val frame = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Spectrum.startWatching()
        onDispose { Spectrum.stopWatching() }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        var still = 0
        while (true) {
            var moved = false
            withFrameMillis { t ->
                val dt = if (last == 0L) 16f else (t - last).coerceIn(1, 100).toFloat()
                last = t
                if (!Spectrum.meter(read)) read.fill(0f)
                for (ch in 0 until 2) {
                    val before = shown[ch]
                    // up at once, down within about two thirds of a second
                    shown[ch] = if (read[ch] > before) read[ch] else max(read[ch], before - dt / 650f)
                    val p = peak[ch]
                    if (read[2 + ch] >= p) {
                        peak[ch] = read[2 + ch]
                        hold[ch] = 900f
                    } else if (hold[ch] > 0f) {
                        hold[ch] -= dt
                    } else {
                        peak[ch] = max(shown[ch], p - dt / 900f)
                    }
                    if (shown[ch] != before || peak[ch] != p) moved = true
                }
                if (moved) frame.intValue++
            }
            still = if (moved) 0 else still + 1
            if (still > 30) { delay(150); last = 0L }
        }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val low = MaterialTheme.colorScheme.primary
    val mid = MaterialTheme.colorScheme.tertiary
    val top = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.intValue
        val labelW = 14.dp.toPx()
        val gap = 2.dp.toPx()
        val rowGap = 4.dp.toPx()
        val rowH = (size.height - rowGap) / 2
        val cells = 32
        val cellW = (size.width - labelW - gap * (cells - 1)) / cells
        for (ch in 0 until 2) {
            val y = ch * (rowH + rowGap)
            val label = measurer.measure(if (ch == 0) "L" else "R", labelStyle)
            drawText(label, topLeft = Offset(0f, y + (rowH - label.size.height) / 2))
            val lit = (shown[ch] * cells).roundToInt()
            val peakCell = min(cells - 1, (peak[ch] * cells).roundToInt() - 1)
            for (i in 0 until cells) {
                val at = (i + 0.5f) / cells
                // -12 dB and -3 dB of the scale (-48 … 0 dB)
                val color = if (at > 0.9375f) top else if (at > 0.75f) mid else low
                val on = i < lit || i == peakCell && peakCell >= lit
                drawRect(color, Offset(labelW + i * (cellW + gap), y), Size(cellW, rowH), alpha = if (on) 1f else 0.12f)
            }
        }
    }
}
