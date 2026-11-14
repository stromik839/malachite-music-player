package app.malachite.player.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import app.malachite.core.i18n.tr
import app.malachite.core.lyrics.LyricLine
import app.malachite.core.lyrics.Lyrics

/**
 * The frame the lyrics are centred in, measured the way a person measures it on the screen:
 * from the lowest pixels of the album line under "Сейчас играет" down to the top of the shift
 * capsule, between the edges of the screen. Also whether the grid for placing the line is shown.
 */
object LyricsGuide {
    /** The album line of the player's header: its left, top, right and its baseline as bottom, in window pixels. */
    var headerText by mutableStateOf<Rect?>(null)

    /** The player shows the grid and the slider for placing the line being sung. */
    var adjusting by mutableStateOf(false)

    /**
     * Top and bottom of the frame inside the lyrics panel (pixels from the panel's top). The
     * album line counts only when it is right above the panel (not beside it, as in landscape).
     */
    fun frame(panel: Rect?, capsuleTop: Float, panelHeight: Float): Pair<Float, Float> {
        val header = headerText
        val top = if (panel != null && header != null && header.center.x in panel.left..panel.right && header.bottom <= panel.top + 1f) {
            header.bottom - panel.top
        } else 0f
        val bottom = if (capsuleTop.isNaN() || capsuleTop <= top) panelHeight else capsuleTop
        return top to bottom
    }

    /** A made-up song text for placing the line when the song playing has no synced lyrics. */
    fun demo(): Lyrics {
        val words = listOf(
            tr("Это пример текста для настройки"),
            tr("Строка, которая поётся, светится ярче"),
            tr("Поставьте её туда, где удобно читать"),
            tr("Сетка показывает рамку и её середину"),
            tr("Ползунок двигает строку выше или ниже"),
            tr("Когда всё готово, нажмите «Готово»"),
        )
        // every three seconds a new line, for long enough to cover any song
        val lines = (0 until 200).map { i -> LyricLine(i * 3000L, null, words[i % words.size]) }
        return Lyrics(lines, synced = true, source = "demo")
    }
}
