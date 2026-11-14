package app.malachite.player.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import kotlin.math.abs

/**
 * The colour of the icon on the home screen.
 *
 * Android has no way to repaint an app icon while the app is installed. What it does allow is
 * several launcher entries ("aliases") with different icons, of which one is switched on – so
 * the icon exists in the colours of the accent presets and the nearest one is chosen.
 *
 * Switching makes the launcher redraw the icon; some launchers also put it back at the end of
 * the home screen or drop the app from "recents". That is why it happens only when the app is
 * left, never while the user is looking at it.
 */
object AppIcon {
    private val ALIASES = mapOf(
        AccentPreset.MALACHITE to "IconMalachite",
        AccentPreset.RED to "IconRed",
        AccentPreset.ORANGE to "IconOrange",
        AccentPreset.YELLOW to "IconYellow",
        AccentPreset.GREEN to "IconGreen",
        AccentPreset.CYAN to "IconCyan",
        AccentPreset.BLUE to "IconBlue",
        AccentPreset.VIOLET to "IconViolet",
    )

    /** True where the phone has colours of its own to follow (taken from the wallpaper, Android 12+). */
    val systemColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** The preset whose icon is wanted with these settings. */
    fun wanted(context: Context, s: AppSettings): AccentPreset = when (s.iconColor) {
        IconColor.DEFAULT -> AccentPreset.MALACHITE
        IconColor.ACCENT -> if (s.accent == AccentPreset.CUSTOM) nearest(s.customAccent.toInt()) else s.accent
        IconColor.SYSTEM ->
            if (Build.VERSION.SDK_INT >= 31) nearest(context.getColor(android.R.color.system_accent1_500))
            else AccentPreset.MALACHITE
    }

    /** The preset closest in hue; greyish colours keep the usual icon. */
    fun nearest(color: Int): AccentPreset {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.15f) return AccentPreset.MALACHITE
        fun hue(p: AccentPreset) = FloatArray(3).also { Color.colorToHSV(p.argb.toInt(), it) }[0]
        fun distance(a: Float, b: Float) = abs(a - b).let { minOf(it, 360f - it) }
        return ALIASES.keys.minByOrNull { distance(hue(it), hsv[0]) } ?: AccentPreset.MALACHITE
    }

    /** Switches the wanted icon on and the others off. Does nothing when it is already so. */
    fun apply(context: Context, s: AppSettings) {
        runCatching {
            val pm = context.packageManager
            val want = ALIASES.getValue(wanted(context, s))
            fun component(alias: String) = ComponentName(context.packageName, "app.malachite.player.$alias")
            fun isOn(alias: String) = when (pm.getComponentEnabledSetting(component(alias))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> alias == "IconMalachite" // as declared in the manifest
            }
            if (isOn(want) && ALIASES.values.none { it != want && isOn(it) }) return
            // the new one first: there is never a moment without an icon
            pm.setComponentEnabledSetting(component(want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (alias in ALIASES.values) {
                if (alias != want && isOn(alias)) {
                    pm.setComponentEnabledSetting(component(alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
            AppLog.i("icon", "Значок: $want")
        }.onFailure { AppLog.e("icon", "Значок не сменился", it) }
    }
}
