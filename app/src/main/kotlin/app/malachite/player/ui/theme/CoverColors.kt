package app.malachite.player.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.malachite.core.library.TrackInfo
import app.malachite.player.data.coverKey
import app.malachite.player.ui.components.rememberCoverState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colours taken from a song's cover, kept per song (not per picture: the small cover and the big
 * one give the same colours, and a cover read again does not mean working them out again).
 */
object CoverColors {
    private val schemes = android.util.LruCache<String, ColorScheme>(32)
    /** Covers no colours could be taken from. */
    private val none = android.util.LruCache<String, Boolean>(64)

    private fun k(cover: String, shade: Shade) = "$cover|$shade"

    fun cached(cover: String, shade: Shade): ColorScheme? = schemes.get(k(cover, shade))

    fun isKnownNone(cover: String, shade: Shade) = none.get(k(cover, shade)) == true

    /** Works the colours out from [bitmap] (any size of the cover). Call off the main thread. */
    fun compute(cover: String, bitmap: Bitmap, shade: Shade): ColorScheme? {
        val key = k(cover, shade)
        schemes.get(key)?.let { return it }
        if (none.get(key) == true) return null
        val scheme = coverScheme(bitmap, shade)
        if (scheme == null) none.put(key, true) else schemes.put(key, scheme)
        return scheme
    }
}

/**
 * The colours of [track]'s cover, or null for the colours of the settings (switched off, no cover,
 * no colours in it). Never worked out on the main thread: what was made ready ahead (CoverPrefetch)
 * is taken at once; otherwise the colours come a moment later, and until then the previous ones stay
 * – no flash of the settings' colours between two songs.
 */
@Composable
fun rememberCoverScheme(track: TrackInfo?, shade: Shade, enabled: Boolean): ColorScheme? {
    val key = if (enabled && track != null) coverKey(track.path, track.lastModified) else null
    val cover = rememberCoverState(if (key != null) track else null, 200)
    val ready = key?.let { CoverColors.cached(it, shade) }
    val worked = remember { mutableStateOf<ColorScheme?>(null) }
    LaunchedEffect(key, shade, cover.bitmap, cover.settled) {
        val pic = cover.bitmap
        worked.value = when {
            key == null -> null
            ready != null -> ready
            pic != null -> withContext(Dispatchers.Default) { CoverColors.compute(key, pic, shade) }
            cover.settled -> null
            else -> worked.value
        }
    }
    return when {
        key == null -> null
        ready != null -> ready
        CoverColors.isKnownNone(key, shade) -> null
        else -> worked.value
    }
}

/**
 * [color] reached softly (about half a second) and meant to be read only while drawing (for
 * instance in `drawBehind`): the big surfaces – the window, the panels, the song screen – glide to
 * the next song's colours without anything being composed again. The colours of the theme itself
 * change at once, in one go: they used to be stepped a dozen times, and each step composed the
 * whole app again – a weak phone spent a tenth of a second on each, and everything stuttered.
 */
@Composable
fun rememberGlidingColor(color: Color, animate: Boolean): State<Color> {
    val glide = remember { Animatable(color) }
    LaunchedEffect(color, animate) {
        if (animate) glide.animateTo(color, tween(450, easing = FastOutSlowInEasing)) else glide.snapTo(color)
    }
    return glide.asState()
}
