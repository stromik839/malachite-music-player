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
import app.malachite.player.data.VisualizerOrder
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
            val t = spread[i].let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
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
 * The music as a picture under the cover, its bands in the chosen [order]. [low] and [high] are
 * the colours of the two ends (the theme's), [tip] the bright tops and caps; [alpha] is how far it
 * has appeared (the bars also grow out of the floor with it). Nothing is computed while it is not
 * on the screen, and a still picture is not drawn again.
 */
@Composable
fun Visualizer(
    style: VisualizerStyle,
    order: VisualizerOrder,
    modifier: Modifier,
    low: Color,
    high: Color,
    tip: Color,
    alpha: () -> Float = { 1f },
) {
    val n = Spectrum.BANDS
    val motion = remember { Motion(n) }
    val frame = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Spectrum.startWatching()
        onDispose { Spectrum.stopWatching() }
    }
    LaunchedEffect(style, order) {
        // bands sorted by strength are smooth already; by frequency a band lifts its neighbours
        val width = when {
            order == VisualizerOrder.STRONG_CENTER || order == VisualizerOrder.STRONG_LEFT || order == VisualizerOrder.STRONG_RIGHT -> 0.15f
            style == VisualizerStyle.WAVE -> 0.7f
            else -> 0.42f
        }
        val heard = FloatArray(n)
        val sorted = FloatArray(n)
        val fromCentre = centreOutwards(n)
        var last = 0L
        var still = 0
        while (true) {
            var moved = false
            withFrameMillis { t ->
                // about 60 pictures a second at most: a 120 Hz screen would draw twice the work for nothing
                if (last != 0L && t - last < 15) { moved = true; return@withFrameMillis }
                val dt = if (last == 0L) 16f else (t - last).coerceIn(1, 100).toFloat()
                last = t
                val fresh = Spectrum.now(heard)
                if (fresh) arrange(heard, motion.target, order, sorted, fromCentre)
                moved = motion.step(fresh, dt, width)
                if (moved) frame.intValue++
            }
            // nothing playing: look for the music a few times a second instead of on every frame
            still = if (moved) 0 else still + 1
            if (still > 30) { delay(150); last = 0L }
        }
    }
    Spacer(
        modifier.drawWithCache {
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
                    val radius = CornerRadius(min(w / 2, 3.dp.toPx()))
                    val glow = 3.dp.toPx()
                    val glowRadius = CornerRadius(radius.x + glow)
                    // Few brushes shared by all bars, drawn layer after layer: the graphics chip gets a
                    // handful of draws per picture however many bars there are.
                    // the colours of the theme from the bass to the highs, across the whole width
                    val across = Brush.horizontalGradient(listOf(low, high), startX = 0f, endX = size.width)
                    // the tops of tall bars shine
                    val shine = Brush.verticalGradient(0f to tip.copy(alpha = 0.6f), 1f to tip.copy(alpha = 0f), startY = base - room, endY = base - room * 0.45f)
                    // the reflection fades out downwards
                    val mirror = Brush.verticalGradient(0f to low.copy(alpha = 0.3f), 1f to low.copy(alpha = 0f), startY = mirrorTop, endY = size.height)
                    val floor = Brush.horizontalGradient(listOf(low.copy(alpha = 0.25f), high.copy(alpha = 0.25f)), startX = 0f, endX = size.width)
                    onDrawBehind {
                        @Suppress("UNUSED_EXPRESSION") frame.intValue
                        val a = alpha()
                        if (a <= 0.01f) return@onDrawBehind
                        fun x(i: Int) = i * (w + gap)
                        // the bars rise out of the floor as the visualizer appears and sink back when it goes
                        fun h(i: Int) = max(minH, motion.level[i] * a * room)
                        drawRect(floor, Offset(0f, base), Size(size.width, 1.dp.toPx()), alpha = a)
                        for (i in 0 until n) {
                            val lv = motion.level[i]
                            if (lv > 0.15f) drawRoundRect(across, Offset(x(i) - glow, base - h(i) - glow), Size(w + 2 * glow, h(i) + 2 * glow), glowRadius, alpha = a * 0.18f * lv)
                        }
                        for (i in 0 until n) drawRoundRect(across, Offset(x(i), base - h(i)), Size(w, h(i)), radius, alpha = a)
                        for (i in 0 until n) {
                            if (motion.level[i] > 0.45f) drawRoundRect(shine, Offset(x(i), base - h(i)), Size(w, h(i)), radius, alpha = a)
                        }
                        for (i in 0 until n) {
                            val rh = min(size.height - mirrorTop, h(i) * 0.35f)
                            if (rh > 0.5f) drawRect(mirror, Offset(x(i), mirrorTop), Size(w, rh), alpha = a)
                        }
                        for (i in 0 until n) {
                            val p = motion.peak[i] * a
                            if (p > 0.03f) drawRect(tip, Offset(x(i), base - p * room - capGap - capH), Size(w, capH), alpha = a * 0.9f)
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
                            val lv = motion.level[i] * a
                            val lit = if (lv < 0.01f) 0 else ceil(lv * rows - 0.25f).toInt().coerceIn(1, rows)
                            for (r in 0 until lit) drawRect(heat, Offset(x, cellTop(r)), Size(w, cell), alpha = a)
                            val pr = (motion.peak[i] * a * rows).roundToInt() - 1
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
                            up[i + 1] = mid - motion.level[i] * a * amp
                            caps[i + 1] = mid - motion.peak[i] * a * amp
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

/** Positions from the middle outwards: middle, left of it, right of it, and so on. */
private fun centreOutwards(n: Int): IntArray {
    val out = IntArray(n)
    val c = n / 2
    var k = 0
    out[k++] = c
    var d = 1
    while (k < n) {
        if (c - d >= 0) out[k++] = c - d
        if (k < n && c + d < n) out[k++] = c + d
        d++
    }
    return out
}

/**
 * Puts the heard bands ([src], bass first) into the places of the picture ([dst]) in the chosen
 * [order]: by frequency (as heard, reversed, or mirrored with the bass in the middle) or by
 * strength at this moment (the loudest in the middle, on the left or on the right).
 */
private fun arrange(src: FloatArray, dst: FloatArray, order: VisualizerOrder, sorted: FloatArray, fromCentre: IntArray) {
    val n = src.size
    when (order) {
        VisualizerOrder.FREQUENCY -> src.copyInto(dst)
        VisualizerOrder.REVERSED -> for (i in 0 until n) dst[i] = src[n - 1 - i]
        VisualizerOrder.MIRROR -> {
            val c = (n - 1) / 2f
            for (i in 0 until n) dst[i] = src[(kotlin.math.abs(i - c) * 2).toInt().coerceIn(0, n - 1)]
        }
        else -> {
            src.copyInto(sorted)
            sorted.sort()
            // sorted[n - 1 - k] is the k-th strongest
            for (k in 0 until n) {
                val v = sorted[n - 1 - k]
                when (order) {
                    VisualizerOrder.STRONG_LEFT -> dst[k] = v
                    VisualizerOrder.STRONG_RIGHT -> dst[n - 1 - k] = v
                    else -> dst[fromCentre[k]] = v
                }
            }
        }
    }
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
