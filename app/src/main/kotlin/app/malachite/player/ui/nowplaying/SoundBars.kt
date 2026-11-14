package app.malachite.player.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.malachite.player.playback.Spectrum
import kotlin.math.max
import kotlin.math.min

/**
 * Bars that move with the music: low sounds on the left, high on the right. They rise at once with
 * a beat and fall slowly, like the needles of a level meter. Nothing is computed while they are
 * not on the screen.
 */
@Composable
fun SoundBars(modifier: Modifier, low: Color, high: Color, alpha: () -> Float = { 1f }) {
    val levels = remember { FloatArray(Spectrum.BANDS) }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    var frame by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Spectrum.watching = true
        onDispose { Spectrum.watching = false }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { t ->
                val fresh = Spectrum.now(levels)
                val dt = if (last == 0L) 16f else (t - last).coerceIn(1, 100).toFloat()
                last = t
                var moving = false
                for (i in shown.indices) {
                    val target = if (fresh) levels[i] else 0f
                    val before = shown[i]
                    // up with the beat at once, down within about a quarter of a second
                    shown[i] = if (target > before) before + (target - before) * min(1f, dt / 20f)
                    else before - min(before - target, dt / 220f)
                    if (shown[i] != before) moving = true
                }
                // a still picture is not drawn again
                if (moving) frame++
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val n = shown.size
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val brush = Brush.verticalGradient(listOf(high, low), startY = 0f, endY = size.height)
        val a = alpha()
        if (a <= 0.01f) return@Canvas
        for (i in 0 until n) {
            val h = max(2.dp.toPx(), shown[i] * size.height)
            drawRoundRect(brush, topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(min(w / 2, 4.dp.toPx())), alpha = a)
        }
    }
}
