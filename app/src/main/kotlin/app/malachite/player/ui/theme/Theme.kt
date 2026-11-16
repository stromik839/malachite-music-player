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

private fun hsl(h: Float, s: Float, l: Float): Color {
    val arr = floatArrayOf(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    return Color(ColorUtils.HSLToColor(arr))
}

private fun toHsl(c: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }

/**
 * Builds a complete Material colour scheme from one accent colour.
 * Dark: dark background with accent-tinted surfaces; AMOLED: pure black; Light: soft white.
 */
fun schemeFromSeed(seed: Color, shade: Shade, background: Color? = null, surface: Color? = null): ColorScheme {
    val (h, s0, _) = toHsl(seed).let { Triple(it[0], it[1], it[2]) }
    val s = s0.coerceIn(0.15f, 0.85f)
    return when (shade) {
        Shade.LIGHT -> {
            val bg = background ?: hsl(h, 0.25f, 0.97f)
            lightColorScheme(
                primary = hsl(h, s, 0.34f), onPrimary = Color.White,
                primaryContainer = hsl(h, s * 0.6f, 0.86f), onPrimaryContainer = hsl(h, 0.6f, 0.12f),
                secondary = hsl(h, s * 0.4f, 0.38f), onSecondary = Color.White,
                secondaryContainer = hsl(h, s * 0.35f, 0.89f), onSecondaryContainer = hsl(h, 0.4f, 0.14f),
                tertiary = hsl(h + 40, 0.4f, 0.38f), onTertiary = Color.White,
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
                primary = hsl(h, minOf(s, 0.62f), 0.55f), onPrimary = hsl(h, 0.5f, 0.09f),
                primaryContainer = hsl(h, s * 0.75f, 0.20f), onPrimaryContainer = hsl(h, 0.5f, 0.86f),
                secondary = hsl(h, s * 0.45f, 0.62f), onSecondary = hsl(h, 0.4f, 0.10f),
                secondaryContainer = hsl(h, s * 0.40f, 0.17f), onSecondaryContainer = hsl(h, 0.3f, 0.86f),
                tertiary = hsl(h + 40, 0.42f, 0.64f), onTertiary = hsl(h + 40, 0.4f, 0.10f),
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
    return when (shade) {
        Shade.LIGHT -> {
            val bg = bgSwatch?.let { toHsl(Color(it.rgb)) }?.let { hsl(it[0], it[1] * 0.4f, 0.94f) }
            schemeFromSeed(Color(seedInt), Shade.LIGHT, bg)
        }
        else -> {
            val bg = if (shade == Shade.AMOLED) Color.Black
            else bgSwatch?.let { toHsl(Color(it.rgb)) }?.let { hsl(it[0], it[1] * 0.7f, minOf(it[2], 0.13f)) }
            schemeFromSeed(Color(seedInt), shade, bg)
        }
    }
}

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
    // a new set of colours (the next song's cover, another accent) comes in softly
    val colors = animatedScheme(scheme ?: menu, settings.animations)
    val typography = remember(settings.menuFont, settings.menuFontFile, settings.fontScale) {
        Typography().withFont(fontFamily(settings.menuFont, settings.menuFontFile), settings.fontScale.coerceIn(0.8f, 1.4f))
    }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
