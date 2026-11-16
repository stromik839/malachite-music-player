package app.malachite.player.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
 * – no flash of the settings' colours between two songs. [waiting]: the player has not said yet
 * which song it has (the app has just started): the colours shown last stand in (see [LastLook]).
 */
@Composable
fun rememberCoverScheme(track: TrackInfo?, shade: Shade, enabled: Boolean, waiting: Boolean = false): ColorScheme? {
    val key = if (enabled && track != null) coverKey(track.path, track.lastModified) else null
    val cover = rememberCoverState(if (key != null) track else null, 200)
    val ready = key?.let { CoverColors.cached(it, shade) }
    val none = key != null && (CoverColors.isKnownNone(key, shade) || (cover.settled && cover.bitmap == null))
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
        // what is known for this song is kept for the next start of the app
        if (key != null && (ready != null || pic != null || cover.settled)) LastLook.keepScheme(key, shade, worked.value)
    }
    return when {
        !enabled -> null
        key == null -> if (waiting) LastLook.scheme(shade) else null
        ready != null -> ready
        none -> null
        else -> worked.value ?: LastLook.scheme(shade)
    }
}

/**
 * The cover colours and the frosted glass shown last, kept on disk: when Android has closed the app
 * in the background, it starts again with them – it used to show the settings' green and no glass
 * for the second the player needs to say which song it has. Only the colours and the blurred 72 px
 * picture are kept, nothing that names the song.
 */
object LastLook {
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachite-last-look").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var loaded = false
    @Volatile private var schemeFor: String? = null
    @Volatile private var schemeShade: Shade? = null
    @Volatile private var scheme: ColorScheme? = null
    @Volatile private var frostFor: String? = null
    @Volatile private var frost: Bitmap? = null

    private fun dir() = app.malachite.player.MalachiteApp.graph.context.filesDir
    private fun coloursFile() = java.io.File(dir(), "last_cover_colours.txt")
    private fun frostFile() = java.io.File(dir(), "last_cover_frost.png")

    /** Two small files, read once. */
    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val lines = coloursFile().takeIf { it.isFile }?.readLines().orEmpty()
            if (lines.size >= 3 && lines[0] == "1") {
                schemeShade = Shade.entries.firstOrNull { it.name == lines[1] }
                scheme = if (lines[2] == "none") null else schemeOf(lines[2].split(' ').map { it.toLong(16).toInt() })
            }
        }
        frost = runCatching { frostFile().takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
    }

    /** The colours shown last, if they were for this [shade] (null: the settings' ones). */
    fun scheme(shade: Shade): ColorScheme? {
        load()
        return if (schemeShade == shade) scheme else null
    }

    fun frost(): Bitmap? {
        load()
        return frost
    }

    /** The colours of the song [cover] (null: it has none); written only when they changed. */
    fun keepScheme(cover: String, shade: Shade, colours: ColorScheme?) {
        load()
        if (cover == schemeFor && shade == schemeShade && colours === scheme) return
        schemeFor = cover
        schemeShade = shade
        scheme = colours
        val text = "1\n" + shade.name + "\n" + (colours?.let { argbs(it).joinToString(" ") { c -> Integer.toHexString(c) } } ?: "none")
        io.execute { runCatching { app.malachite.core.library.LibraryStore.writeAtomic(coloursFile(), text) } }
    }

    /** The frosted cover of the song [cover] (null: it has none); written only when it changed. */
    fun keepFrost(cover: String, picture: Bitmap?) {
        load()
        if (cover == frostFor && picture === frost) return
        frostFor = cover
        frost = picture
        io.execute {
            runCatching {
                val f = frostFile()
                if (picture == null) {
                    f.delete()
                } else {
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                }
            }
        }
    }

    private fun argbs(c: ColorScheme) = listOf(
        c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer, c.inversePrimary,
        c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer,
        c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer,
        c.background, c.onBackground, c.surface, c.onSurface, c.surfaceVariant, c.onSurfaceVariant, c.surfaceTint,
        c.inverseSurface, c.inverseOnSurface, c.error, c.onError, c.errorContainer, c.onErrorContainer,
        c.outline, c.outlineVariant, c.scrim, c.surfaceBright, c.surfaceDim, c.surfaceContainer,
        c.surfaceContainerHigh, c.surfaceContainerHighest, c.surfaceContainerLow, c.surfaceContainerLowest,
    ).map { it.toArgb() }

    private fun schemeOf(v: List<Int>): ColorScheme? {
        if (v.size != 36) return null
        fun c(i: Int) = Color(v[i])
        return darkColorScheme().copy(
            primary = c(0), onPrimary = c(1), primaryContainer = c(2), onPrimaryContainer = c(3), inversePrimary = c(4),
            secondary = c(5), onSecondary = c(6), secondaryContainer = c(7), onSecondaryContainer = c(8),
            tertiary = c(9), onTertiary = c(10), tertiaryContainer = c(11), onTertiaryContainer = c(12),
            background = c(13), onBackground = c(14), surface = c(15), onSurface = c(16),
            surfaceVariant = c(17), onSurfaceVariant = c(18), surfaceTint = c(19),
            inverseSurface = c(20), inverseOnSurface = c(21),
            error = c(22), onError = c(23), errorContainer = c(24), onErrorContainer = c(25),
            outline = c(26), outlineVariant = c(27), scrim = c(28),
            surfaceBright = c(29), surfaceDim = c(30), surfaceContainer = c(31),
            surfaceContainerHigh = c(32), surfaceContainerHighest = c(33), surfaceContainerLow = c(34), surfaceContainerLowest = c(35),
        )
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

/**
 * Whether something on the screen is being scrolled: the app's root hears every scroll of every
 * list (also a fling running on by itself after the finger left).
 */
object ScrollActivity {
    @Volatile private var last = 0L

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            last = android.os.SystemClock.uptimeMillis()
            return androidx.compose.ui.geometry.Offset.Zero
        }

        override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            last = android.os.SystemClock.uptimeMillis()
            return androidx.compose.ui.unit.Velocity.Zero
        }
    }

    /** Nothing has scrolled for [ms]. */
    fun quietFor(ms: Long) = android.os.SystemClock.uptimeMillis() - last >= ms
}

/**
 * [target] taken on only while nothing scrolls: a change of the theme's colours composes the whole
 * screen again (a theme reaches every element), which in the middle of a scroll was a jerk. The
 * colours change a moment after the list stops (the backgrounds then glide, see
 * [rememberGlidingColor]); the frosted cover behind changes at once, it costs nothing.
 */
@Composable
fun rememberSettledScheme(target: ColorScheme?): ColorScheme? {
    val shown = remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        val asked = android.os.SystemClock.uptimeMillis()
        while (!ScrollActivity.quietFor(SETTLE_MS)) kotlinx.coroutines.delay(SETTLE_MS / 3)
        if (shown.value !== target && app.malachite.player.data.FrameWatch.isOn) {
            app.malachite.player.data.AppLog.i("theme", "цвета темы сменены" + (if (target == null) " на цвета из настроек" else " на цвета обложки") +
                "; ждали, пока список остановится, ${android.os.SystemClock.uptimeMillis() - asked} мс")
        }
        shown.value = target
    }
    return shown.value
}

private const val SETTLE_MS = 300L
