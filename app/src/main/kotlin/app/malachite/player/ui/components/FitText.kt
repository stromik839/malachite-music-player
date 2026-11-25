package app.malachite.player.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that never breaks inside a word: the biggest size, from the size of [style] down to
 * [minSize], at which every word fits the width and the whole text takes at most [maxLines]
 * lines. «Гражданская оборона» next to a playlist's cover came out as «Гражда / нская / оборон / а».
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val base = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = remember(text, base, width, maxLines, minSize) { fitSize(measurer, text, base, width, maxLines, minSize) }
        Text(
            text, style = base.copy(fontSize = size), color = color, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis, textAlign = textAlign,
        )
    }
}

private fun fitSize(measurer: TextMeasurer, text: String, style: TextStyle, width: Int, maxLines: Int, minSize: TextUnit): TextUnit {
    val start = style.fontSize
    if (!start.isSp || width == Constraints.Infinity || width <= 0 || text.isBlank()) return start
    val words = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }
    val min = if (minSize.isSp) minSize.value else 12f
    var sp = start.value
    while (sp > min) {
        val st = style.copy(fontSize = sp.sp)
        val widest = words.maxOfOrNull { measurer.measure(it, st, maxLines = 1, softWrap = false).size.width } ?: 0
        if (widest <= width) {
            val lines = measurer.measure(text, st, maxLines = maxLines + 1, constraints = Constraints(maxWidth = width)).lineCount
            if (lines <= maxLines) return sp.sp
        }
        sp -= 1f
    }
    return min.sp
}
