package app.malachite.player.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import app.malachite.core.library.TrackInfo
import app.malachite.player.data.coverKey
import app.malachite.player.ui.components.rememberCoverState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * [target] reached gradually, in about half a second, instead of every colour jumping at once: the
 * next song's cover colours, another accent, the cover colours switched on. In a dozen steps rather
 * than every frame – a change of the colours redraws the whole screen, and a dozen redraws stay light
 * where thirty in a row would be felt on a weak phone.
 */
@Composable
fun animatedScheme(target: ColorScheme, animate: Boolean): ColorScheme {
    val shown = remember { mutableStateOf(target) }
    LaunchedEffect(target, animate) {
        val from = shown.value
        if (!animate || from === target) {
            shown.value = target
            return@LaunchedEffect
        }
        val steps = 12
        for (i in 1..steps) {
            delay(40)
            shown.value = lerpScheme(from, target, FastOutSlowInEasing.transform(i / steps.toFloat()))
        }
        shown.value = target
    }
    return if (animate) shown.value else target
}

/** Every colour of [a] moved the part [t] of the way to [b]. */
fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = a.copy(
    primary = lerp(a.primary, b.primary, t),
    onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    inversePrimary = lerp(a.inversePrimary, b.inversePrimary, t),
    secondary = lerp(a.secondary, b.secondary, t),
    onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t),
    onTertiary = lerp(a.onTertiary, b.onTertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t),
    onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t),
    onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    surfaceTint = lerp(a.surfaceTint, b.surfaceTint, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
    error = lerp(a.error, b.error, t),
    onError = lerp(a.onError, b.onError, t),
    errorContainer = lerp(a.errorContainer, b.errorContainer, t),
    onErrorContainer = lerp(a.onErrorContainer, b.onErrorContainer, t),
    outline = lerp(a.outline, b.outline, t),
    outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    scrim = lerp(a.scrim, b.scrim, t),
    surfaceBright = lerp(a.surfaceBright, b.surfaceBright, t),
    surfaceDim = lerp(a.surfaceDim, b.surfaceDim, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
)
