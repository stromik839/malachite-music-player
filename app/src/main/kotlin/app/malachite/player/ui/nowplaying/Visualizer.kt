package app.malachite.player.ui.nowplaying

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.malachite.player.data.VisualizerStyle
import app.malachite.player.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A bar falls from the very top in about 0.4 s, speeding up like a dropped thing (per ms²). */
private const val GRAVITY = 1.25e-5f

/** A cap stays at the highest point for a moment and then falls, slower than the bar. */
private const val PEAK_HOLD_MS = 450f
private const val PEAK_GRAVITY = 2.5e-6f

/**
 * How every band moves: up at once with the music, down with gravity; a cap marks where the band
 * was highest a moment ago. A loud band lifts its neighbours a little, so the picture flows like a
 * range of hills instead of flickering bar by bar.
 */
private class Motion(val n: Int) {
    val target = FloatArray(n)
    private val spread = FloatArray(n)
    val level = FloatArray(n)
    private val falling = FloatArray(n)
    val peak = FloatArray(n)
    private val hold = FloatArray(n)
    private val peakFalling = FloatArray(n)

    /** One frame [dt] ms long; [width]: how much a band lifts its neighbours (0…1). Returns whether anything moved. */
    fun step(fresh: Boolean, dt: Float, width: Float): Boolean {
        if (!fresh) target.fill(0f)
        for (i in 0 until n) {
            var v = target[i]
            var f = width
            var d = 1
            while (f > 0.03f && d < n) {
                val a = if (i - d >= 0) target[i - d] else 0f
                val b = if (i + d < n) target[i + d] else 0f
                v = max(v, max(a, b) * f)
                f *= width
                d++
            }
            spread[i] = v
        }
        var moving = false
        for (i in 0 until n) {
            val t = spread[i]
            var l = level[i]
            if (t >= l) {
                l += (t - l) * min(1f, dt / 18f)
                falling[i] = 0f
            } else {
                falling[i] += GRAVITY * dt
                l = max(t, l - falling[i] * dt)
                if (l == t) falling[i] = 0f
            }
            var p = peak[i]
            if (l >= p) {
                p = l
                hold[i] = PEAK_HOLD_MS
                peakFalling[i] = 0f
            } else if (hold[i] > 0f) {
                hold[i] -= dt
            } else {
                peakFalling[i] += PEAK_GRAVITY * dt
                p = max(l, p - peakFalling[i] * dt)
            }
            if (l != level[i] || p != peak[i]) moving = true
            level[i] = l
            peak[i] = p
        }
        return moving
    }
}

/**
 * The music as a picture under the cover: bass on the left, highs on the right. [low] and [high]
 * are the colours of the two ends (the theme's), [tip] the bright tops and caps. Nothing is
 * computed while it is not on the screen, and a still picture is not drawn again.
 */
@Composable
fun Visualizer(style: VisualizerStyle, modifier: Modifier, low: Color, high: Color, tip: Color, alpha: () -> Float = { 1f }) {
    val n = Spectrum.BANDS
    val motion = remember { Motion(n) }
    val frame = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Spectrum.startWatching()
        onDispose { Spectrum.stopWatching() }
    }
    LaunchedEffect(style) {
        val width = if (style == VisualizerStyle.WAVE) 0.7f else 0.42f
        var last = 0L
        var still = 0
        while (true) {
            var moved = false
            withFrameMillis { t ->
                val dt = if (last == 0L) 16f else (t - last).coerceIn(1, 100).toFloat()
                last = t
                moved = motion.step(Spectrum.now(motion.target), dt, width)
                if (moved) frame.intValue++
            }
            // nothing playing: look for the music a few times a second instead of on every frame
            still = if (moved) 0 else still + 1
            if (still > 30) { delay(150); last = 0L }
        }
    }
    Spacer(
        modifier.drawWithCache {
            val colors = Array(n) { lerp(low, high, it / (n - 1f)) }
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            when (style) {
                VisualizerStyle.BARS -> {
                    val base = size.height * 0.76f
                    val capH = 2.dp.toPx()
                    val capGap = 2.dp.toPx()
                    val room = base - capH - capGap
                    val minH = 2.dp.toPx()
                    val mirrorTop = base + 2.dp.toPx()
                    val radius = min(w / 2, 3.dp.toPx())
                    val glowNear = 2.dp.toPx()
                    val glowFar = 4.5.dp.toPx()
                    // each bar brightens towards its top; its reflection fades out downwards
                    val bodies = Array(n) { Brush.verticalGradient(0f to lerp(colors[it], tip, 0.55f), 1f to colors[it], startY = base - room, endY = base) }
                    val mirrors = Array(n) { Brush.verticalGradient(0f to colors[it].copy(alpha = 0.32f), 1f to colors[it].copy(alpha = 0f), startY = mirrorTop, endY = size.height) }
                    val floor = Brush.horizontalGradient(colors.map { it.copy(alpha = 0.22f) })
                    onDrawBehind {
                        @Suppress("UNUSED_EXPRESSION") frame.intValue
                        val a = alpha()
                        if (a <= 0.01f) return@onDrawBehind
                        drawRect(floor, Offset(0f, base), Size(size.width, 1.dp.toPx()), alpha = a)
                        for (i in 0 until n) {
                            val x = i * (w + gap)
                            val lv = motion.level[i]
                            val h = max(minH, lv * room)
                            val top = base - h
                            if (lv > 0.12f) {
                                drawRoundRect(colors[i], Offset(x - glowFar, top - glowFar), Size(w + 2 * glowFar, h + 2 * glowFar), CornerRadius(radius + glowFar), alpha = a * 0.07f * lv)
                                drawRoundRect(colors[i], Offset(x - glowNear, top - glowNear), Size(w + 2 * glowNear, h + 2 * glowNear), CornerRadius(radius + glowNear), alpha = a * 0.13f * lv)
                            }
                            drawRoundRect(bodies[i], Offset(x, top), Size(w, h), CornerRadius(radius), alpha = a)
                            val rh = min(size.height - mirrorTop, h * 0.35f)
                            if (rh > 0.5f) drawRoundRect(mirrors[i], Offset(x, mirrorTop), Size(w, rh), CornerRadius(radius), alpha = a)
                            val p = motion.peak[i]
                            if (p > 0.03f) drawRoundRect(tip, Offset(x, base - p * room - capGap - capH), Size(w, capH), CornerRadius(capH / 2), alpha = a * 0.9f)
                        }
                    }
                }
                VisualizerStyle.LEDS -> {
                    val cell = 3.dp.toPx()
                    val cellGap = 2.dp.toPx()
                    val rows = max(4, ((size.height + cellGap) / (cell + cellGap)).toInt())
                    fun cellTop(r: Int) = size.height - (r + 1) * cell - r * cellGap
                    // the unlit cells, drawn faintly: the panel is seen even in silence
                    val ghost = Path().apply {
                        for (i in 0 until n) for (r in 0 until rows) {
                            val x = i * (w + gap)
                            addRect(Rect(x, cellTop(r), x + w, cellTop(r) + cell))
                        }
                    }
                    val warm = lerp(high, Color(0xFFFF5A4E), 0.75f)
                    val heat = Brush.verticalGradient(
                        0f to warm, 0.16f to warm, 0.2f to lerp(high, tip, 0.35f), 0.55f to low, 1f to low,
                        startY = cellTop(rows - 1), endY = size.height,
                    )
                    onDrawBehind {
                        @Suppress("UNUSED_EXPRESSION") frame.intValue
                        val a = alpha()
                        if (a <= 0.01f) return@onDrawBehind
                        drawPath(ghost, low, alpha = a * 0.09f)
                        for (i in 0 until n) {
                            val x = i * (w + gap)
                            val lv = motion.level[i]
                            val lit = if (lv < 0.01f) 0 else ceil(lv * rows - 0.25f).toInt().coerceIn(1, rows)
                            for (r in 0 until lit) drawRect(heat, Offset(x, cellTop(r)), Size(w, cell), alpha = a)
                            val pr = (motion.peak[i] * rows).roundToInt() - 1
                            if (pr >= lit && pr < rows) drawRect(tip, Offset(x, cellTop(pr)), Size(w, cell), alpha = a * 0.95f)
                        }
                    }
                }
                VisualizerStyle.WAVE -> {
                    val mid = size.height / 2
                    val amp = mid - 3.dp.toPx()
                    val k = n + 2
                    val xs = FloatArray(k) { if (it == 0) 0f else if (it == k - 1) size.width else (it - 0.5f) * size.width / n }
                    val rx = FloatArray(k) { xs[k - 1 - it] }
                    val up = FloatArray(k)
                    val down = FloatArray(k)
                    val caps = FloatArray(k)
                    val body = Path()
                    val crest = Path()
                    val trail = Path()
                    val fill = Brush.horizontalGradient(listOf(low.copy(alpha = 0.55f), high.copy(alpha = 0.55f)))
                    val edge = Brush.horizontalGradient(listOf(lerp(low, tip, 0.45f), lerp(high, tip, 0.45f)))
                    val thick = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val thin = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    onDrawBehind {
                        @Suppress("UNUSED_EXPRESSION") frame.intValue
                        val a = alpha()
                        if (a <= 0.01f) return@onDrawBehind
                        up[0] = mid; up[k - 1] = mid; caps[0] = mid; caps[k - 1] = mid
                        for (i in 0 until n) {
                            up[i + 1] = mid - motion.level[i] * amp
                            caps[i + 1] = mid - motion.peak[i] * amp
                        }
                        for (j in 0 until k) down[j] = 2 * mid - up[k - 1 - j]
                        body.reset()
                        body.smooth(xs, up, start = true)
                        body.smooth(rx, down, start = false)
                        body.close()
                        crest.reset()
                        crest.smooth(xs, up, start = true)
                        trail.reset()
                        trail.smooth(xs, caps, start = true)
                        drawLine(low.copy(alpha = 0.2f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx(), alpha = a)
                        drawPath(body, fill, alpha = a)
                        drawPath(trail, tip, alpha = a * 0.45f, style = thin)
                        drawPath(crest, edge, alpha = a * 0.95f, style = thick)
                    }
                }
            }
        },
    )
}

/**
 * A smooth line through the middles between neighbouring points, each point pulling it like a
 * magnet; it starts at the first point ([start]: a new figure) and ends at the last.
 */
private fun Path.smooth(x: FloatArray, y: FloatArray, start: Boolean) {
    val k = x.size
    if (start) moveTo(x[0], y[0]) else lineTo(x[0], y[0])
    var sx = (x[0] + x[1]) / 2
    var sy = (y[0] + y[1]) / 2
    lineTo(sx, sy)
    for (i in 1 until k - 1) {
        val ex = (x[i] + x[i + 1]) / 2
        val ey = (y[i] + y[i + 1]) / 2
        cubicTo(
            sx + 2f / 3f * (x[i] - sx), sy + 2f / 3f * (y[i] - sy),
            ex + 2f / 3f * (x[i] - ex), ey + 2f / 3f * (y[i] - ey),
            ex, ey,
        )
        sx = ex
        sy = ey
    }
    lineTo(x[k - 1], y[k - 1])
}
