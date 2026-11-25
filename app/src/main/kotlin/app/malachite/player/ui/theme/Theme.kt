package app.malachite.player.ui.theme

import app.malachite.core.i18n.tr
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.malachite.player.data.AccentPreset
import app.malachite.player.data.AppSettings
import app.malachite.player.data.FontChoice
import app.malachite.player.data.ThemeMode
import java.io.File

enum class Shade { DARK, AMOLED, LIGHT }

internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val arr = floatArrayOf(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    return Color(ColorUtils.HSLToColor(arr))
}

private fun hsl(h: Float, s: Float, l: Float): Color = hslColor(h, s, l)

private fun toHsl(c: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }

/**
 * Builds a complete Material colour scheme from one accent colour.
 * Dark: dark background with accent-tinted surfaces; AMOLED: pure black; Light: soft white.
 */
fun schemeFromSeed(seed: Color, shade: Shade, background: Color? = null, surface: Color? = null, colourful: Float = 1f): ColorScheme {
    // every tint scaled by how colourful the source is: a black-and-white cover gives greys
    val k = colourful.coerceIn(0f, 1f)
    fun hsl(h: Float, s: Float, l: Float) = app.malachite.player.ui.theme.hslColor(h, s * k, l)
    val (h, s0, _) = toHsl(seed).let { Triple(it[0], it[1], it[2]) }
    val s = s0.coerceIn(0.15f, 0.85f)
    // A black-and-white cover gives a black-and-white theme, not a grey one: its accent is nearly
    // white on a dark background (nearly black on a light one), as far as the cover has no colour.
    // A grey accent looked dull, and the sung words of the lyrics were as grey as the waiting ones.
    val grey = 1f - k
    return when (shade) {
        Shade.LIGHT -> {
            val bg = background ?: hsl(h, 0.25f, 0.97f)
            lightColorScheme(
                primary = hsl(h, s, 0.34f - 0.22f * grey), onPrimary = Color.White,
                primaryContainer = hsl(h, s * 0.6f, 0.86f), onPrimaryContainer = hsl(h, 0.6f, 0.12f),
                secondary = hsl(h, s * 0.4f, 0.38f - 0.20f * grey), onSecondary = Color.White,
                secondaryContainer = hsl(h, s * 0.35f, 0.89f), onSecondaryContainer = hsl(h, 0.4f, 0.14f),
                tertiary = hsl(h + 40, 0.4f, 0.38f - 0.20f * grey), onTertiary = Color.White,
                tertiaryContainer = hsl(h + 40, 0.35f, 0.88f), onTertiaryContainer = hsl(h + 40, 0.4f, 0.14f),
                background = bg, onBackground = hsl(h, 0.12f, 0.10f),
                surface = surface ?: bg, onSurface = hsl(h, 0.12f, 0.10f),
                surfaceVariant = hsl(h, 0.18f, 0.90f), onSurfaceVariant = hsl(h, 0.10f, 0.32f),
                surfaceContainerLowest = Color.White, surfaceContainerLow = hsl(h, 0.22f, 0.95f),
                surfaceContainer = hsl(h, 0.2f, 0.93f), surfaceContainerHigh = hsl(h, 0.18f, 0.91f),
                surfaceContainerHighest = hsl(h, 0.16f, 0.88f),
                outline = hsl(h, 0.10f, 0.50f), outlineVariant = hsl(h, 0.14f, 0.80f),
            )
        }
        Shade.DARK, Shade.AMOLED -> {
            val amoled = shade == Shade.AMOLED
            val bg = background ?: if (amoled) Color.Black else hsl(h, 0.22f, 0.055f)
            fun cont(l: Float) = if (amoled) hsl(h, 0.12f, l * 0.75f) else hsl(h, 0.20f, l)
            darkColorScheme(
                primary = hsl(h, minOf(s, 0.62f), 0.55f + 0.37f * grey), onPrimary = hsl(h, 0.5f, 0.09f),
                primaryContainer = hsl(h, s * 0.75f, 0.20f), onPrimaryContainer = hsl(h, 0.5f, 0.86f),
                secondary = hsl(h, s * 0.45f, 0.62f + 0.18f * grey), onSecondary = hsl(h, 0.4f, 0.10f),
                secondaryContainer = hsl(h, s * 0.40f, 0.17f), onSecondaryContainer = hsl(h, 0.3f, 0.86f),
                tertiary = hsl(h + 40, 0.42f, 0.64f + 0.18f * grey), onTertiary = hsl(h + 40, 0.4f, 0.10f),
                tertiaryContainer = hsl(h + 40, 0.3f, 0.19f), onTertiaryContainer = hsl(h + 40, 0.3f, 0.86f),
                background = bg, onBackground = hsl(h, 0.10f, 0.91f),
                surface = surface ?: bg, onSurface = hsl(h, 0.10f, 0.91f),
                surfaceVariant = cont(0.15f), onSurfaceVariant = hsl(h, 0.12f, 0.72f),
                surfaceContainerLowest = if (amoled) Color.Black else hsl(h, 0.22f, 0.04f),
                surfaceContainerLow = cont(0.075f), surfaceContainer = cont(0.095f),
                surfaceContainerHigh = cont(0.12f), surfaceContainerHighest = cont(0.15f),
                outline = hsl(h, 0.16f, 0.40f), outlineVariant = hsl(h, 0.18f, 0.22f),
            )
        }
    }
}

fun AppSettings.accentColor(): Color =
    if (accent == AccentPreset.CUSTOM) Color(customAccent.toInt()) else Color(accent.argb.toInt())

fun AppSettings.shade(systemDark: Boolean): Shade = when (themeMode) {
    ThemeMode.DARK -> Shade.DARK
    ThemeMode.AMOLED -> Shade.AMOLED
    ThemeMode.LIGHT -> Shade.LIGHT
    ThemeMode.SYSTEM -> if (systemDark) Shade.DARK else Shade.LIGHT
}

fun AppSettings.menuScheme(systemDark: Boolean): ColorScheme = schemeFromSeed(
    accentColor(), shade(systemDark),
    customBackground?.let { Color(it.toInt()) },
    customSurface?.let { Color(it.toInt()) },
)

/** Colours taken from the album cover (kept per song by [CoverColors]). */
fun coverScheme(bitmap: Bitmap, shade: Shade): ColorScheme? {
    val p = runCatching { Palette.from(bitmap).maximumColorCount(16).generate() }.getOrNull() ?: return null
    val seedInt = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: return null
    val bgSwatch = p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
    // A black-and-white cover (a pencil drawing on white paper, a grey photo) used to give a light
    // brown or light red theme: the faint tone of the paper or the noise of the picture was taken
    // for its colour and then made as strong as a real one. Tints follow how colourful it really is.
    val k = colourfulness(bitmap)
    return when (shade) {
        Shade.LIGHT -> {
            val bg = bgSwatch?.let { toHsl(Color(it.rgb)) }?.let { hsl(it[0], it[1] * 0.4f * k, 0.94f) }
            schemeFromSeed(Color(seedInt), Shade.LIGHT, bg, colourful = k)
        }
        else -> {
            val bg = if (shade == Shade.AMOLED) Color.Black
            else bgSwatch?.let { toHsl(Color(it.rgb)) }?.let { hsl(it[0], it[1] * 0.7f * k, minOf(it[2], 0.13f)) }
            schemeFromSeed(Color(seedInt), shade, bg, colourful = k)
        }
    }
}

/**
 * How colourful a picture is, 0 (black and white) … 1: the share of its pixels that really carry a
 * colour (very dark and very light ones say nothing about it). A few colourful pixels in a grey
 * picture give a faint tint, not a strong one.
 */
internal fun colourfulness(bitmap: Bitmap): Float = runCatching {
    val small = if (bitmap.width > 64 || bitmap.height > 64) Bitmap.createScaledBitmap(bitmap, 48, 48, true) else bitmap
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    var counted = 0
    var colourful = 0
    for (c in px) {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val light = (max + min) / 2
        if (light < 20 || light > 240) continue
        counted++
        if (max - min > COLOURFUL_CHROMA) colourful++
    }
    if (counted < px.size / 20) return@runCatching 0f
    val share = colourful.toFloat() / counted
    ((share - GREY_SHARE) / (FULL_SHARE - GREY_SHARE)).coerceIn(0f, 1f)
}.getOrDefault(1f)

/** A pixel whose channels differ by more than this (of 255) has a colour of its own. */
private const val COLOURFUL_CHROMA = 38
/** Below this share of colourful pixels a picture counts as black and white … */
private const val GREY_SHARE = 0.04f
/** … and from this share on it is fully colourful. */
private const val FULL_SHARE = 0.22f

/** How the line being sung is coloured: the sung part, and how strong the part still waiting is (0…1). */
data class LyricFill(val sung: Color, val waitingAlpha: Float)

/**
 * The sung part of a lyric line has to stand out from the part still waiting ([text] at
 * [WAITING_ALPHA] over [background]). A grey accent made both the same grey: then the sung part is
 * white on a dark background (nearly black on a light one) and the waiting part a little dimmer.
 * A colour of its own (blue on grey) stands out by its hue and is kept.
 */
fun lyricFill(sung: Color, text: Color, background: Color): LyricFill {
    val (c, a) = lyricFillArgb(sung.toArgb(), text.toArgb(), background.toArgb())
    return LyricFill(Color(c), a / 255f)
}

/** [lyricFill] for the widget: the sung colour and the alpha (0…255) of the waiting text. */
fun lyricFillArgb(sung: Int, text: Int, background: Int): Pair<Int, Int> {
    val bg = ColorUtils.setAlphaComponent(background, 255)
    val waiting = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(text, WAITING_ALPHA_INT), bg)
    val shown = ColorUtils.compositeColors(sung, bg)
    val a = DoubleArray(3).also { ColorUtils.colorToLAB(shown, it) }
    val b = DoubleArray(3).also { ColorUtils.colorToLAB(waiting, it) }
    if (ColorUtils.distanceEuclidean(a, b) >= ENOUGH_DIFFERENCE) return sung to WAITING_ALPHA_INT
    val dark = ColorUtils.calculateLuminance(bg) < 0.4
    return (if (dark) android.graphics.Color.WHITE else 0xFF121212.toInt()) to DIMMER_WAITING_ALPHA_INT
}

/** How strong the words still to be sung are, next to the sung ones. */
const val WAITING_ALPHA = 0.55f
private const val WAITING_ALPHA_INT = 140
private const val DIMMER_WAITING_ALPHA_INT = 115
/** Below this distance in Lab (a grey next to a grey of about the same lightness) the eye hardly tells them apart. */
private const val ENOUGH_DIFFERENCE = 22.0

/**
 * What is wrong with a font file for this app, or null if it is fine. A font without Cyrillic
 * letters shows Russian text in the system font and Latin text in its own: the two have
 * different heights, so lines look uneven – the user should know before blaming the layout.
 */
fun fontProblem(file: File): String? {
    val tf = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return tr("Файл не читается как шрифт")
    val paint = android.graphics.Paint().apply { typeface = tf }
    val cyr = "ЖЩЫЯжщыяё".all { paint.hasGlyph(it.toString()) } // i18n:off – letters to test a font with
    val lat = "AQgy".all { paint.hasGlyph(it.toString()) }
    return when {
        !cyr && !lat -> tr("В шрифте нет ни русских, ни латинских букв — текст останется системным шрифтом")
        !cyr -> tr("В шрифте нет русских букв: русский текст будет системным шрифтом, а латиница — выбранным, строки могут выглядеть неровно")
        else -> null
    }
}

fun fontFamily(choice: FontChoice, file: String?): FontFamily = when (choice) {
    FontChoice.SYSTEM -> FontFamily.Default
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
    FontChoice.CUSTOM -> runCatching {
        val f = File(file ?: "")
        if (f.isFile) FontFamily(Typeface.createFromFile(f)) else FontFamily.Default
    }.getOrDefault(FontFamily.Default)
}

private fun Typography.withFont(family: FontFamily, scale: Float): Typography {
    // Every font is centred in its line box and gets no extra padding of its own. Fonts with
    // unusual metrics used to push text up or down next to icons and inside buttons.
    fun TextStyle.f() = copy(
        fontFamily = family, fontSize = fontSize * scale, lineHeight = lineHeight * scale,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
    )
    return Typography(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun MalachiteTheme(settings: AppSettings, scheme: ColorScheme? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val menu = remember(settings.themeMode, settings.accent, settings.customAccent, settings.customBackground, settings.customSurface, dark) {
        settings.menuScheme(dark)
    }
    // Changed at once: every change of these composes the whole app again, so never step by step.
    // The big surfaces glide to new colours while drawing instead (see rememberGlidingColor).
    val colors = scheme ?: menu
    val typography = remember(settings.menuFont, settings.menuFontFile, settings.fontScale) {
        Typography().withFont(fontFamily(settings.menuFont, settings.menuFontFile), settings.fontScale.coerceIn(0.8f, 1.4f))
    }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
