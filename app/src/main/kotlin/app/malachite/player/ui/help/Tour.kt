package app.malachite.player.ui.help

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr

/** Where the parts the tour points at are on the screen right now. */
object TourTargets {
    val rects = mutableStateMapOf<String, Rect>()
}

/** Marks a part of the screen the tour can point at (the tab panel, the "?" button…). */
fun Modifier.tourTarget(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { TourTargets.rects.remove(key) } }
    onGloballyPositioned { TourTargets.rects[key] = it.boundsInRoot() }
}

/** One step of the tour: the first of [targets] that is on screen gets the spotlight. */
private class TourStep(val targets: List<String>, val title: String, val text: String, val textWithout: String? = null)

private fun steps() = listOf(
    TourStep(emptyList(), tr("Добро пожаловать в Malachite"), tr("Покажу, где что находится — это займёт полминуты. Нажмите «Далее» или «Пропустить».")),
    TourStep(
        listOf("nav"), tr("Вкладки"),
        tr("Здесь переключаются разделы: песни, альбомы, исполнители, плейлисты, исцеление, статистика. Какие вкладки показывать и где панель — Настройки → «Меню и вкладки»."),
    ),
    TourStep(
        listOf("tab_ALBUMS", "nav"), tr("Альбомы"),
        tr("Удерживайте плитку альбома — появится меню: закрепить, в избранное, переименовать, поменять обложку. Песни без альбома собраны в первой плитке."),
    ),
    TourStep(
        listOf("mini"), tr("Мини-плеер"),
        tr("Нажмите на него — откроется экран песни: текст, скорость, таймер сна и меню ⋮ (изменить теги, обложку, найти и синхронизировать текст)."),
        textWithout = tr("Когда заиграет песня, внизу появится мини-плеер. Нажмите на него — откроется экран песни: текст, скорость, таймер сна и меню ⋮."),
    ),
    TourStep(
        listOf("tab_HEAL", "nav"), tr("Исцеление"),
        tr("Наводит порядок: названия, альбомы, обложки, клоны, громкость. Сначала «Проверить» — плеер покажет, что изменит. Любое исцеление можно отменить."),
    ),
    TourStep(listOf("search"), tr("Поиск"), tr("Ищет песни, исполнителей и альбомы — можно писать и латиницей, и кириллицей.")),
    TourStep(listOf("settings"), tr("Настройки"), tr("Вид, тексты песен, звук, виджет, защита данных. Вверху настроек есть поиск: напишите, что нужно, — например, «обложка».")),
    TourStep(listOf("help"), tr("Если что-то непонятно"), tr("Здесь вопросы и ответы с кнопками, которые сразу ведут в нужное место. Отсюда же можно пройти это обучение ещё раз.")),
)

/**
 * The tour over the real screen: everything is dimmed except the button it talks about, and a
 * card next to it says what it does. Taps go only to the card while the tour is open; the back
 * button closes it.
 */
@Composable
fun TourOverlay(onDone: () -> Unit) {
    val steps = remember { steps() }
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val target = step.targets.firstNotNullOfOrNull { TourTargets.rects[it] }
    val ring = MaterialTheme.colorScheme.primary
    // the system back button (or gesture) always gets out of the tour
    androidx.activity.compose.BackHandler(onBack = onDone)
    Box(
        Modifier.fillMaxSize()
            // nothing under the tour can be tapped by mistake
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            target?.let { r ->
                val pad = 6.dp.toPx()
                val topLeft = Offset(r.left - pad, r.top - pad)
                val size = Size(r.width + pad * 2, r.height + pad * 2)
                val corner = CornerRadius(16.dp.toPx())
                drawRoundRect(Color.Transparent, topLeft, size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(ring, topLeft, size, corner, style = Stroke(3.dp.toPx()))
            }
        }
        TourCardLayout(target) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (target == null && step.textWithout != null) step.textWithout else step.text, style = MaterialTheme.typography.bodyMedium)
                    // where in the tour we are
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        steps.indices.forEach { i ->
                            Box(
                                Modifier.size(if (i == index) 9.dp else 6.dp).background(
                                    if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                                ),
                            )
                        }
                    }
                    // the buttons wrap onto a second line on a narrow card instead of being squeezed
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (index < steps.lastIndex) TextButton(onClick = onDone) { Text(tr("Пропустить"), maxLines = 1) }
                        if (index > 0) TextButton(onClick = { index-- }) { Text(tr("Назад"), maxLines = 1) }
                        Button(onClick = { if (index < steps.lastIndex) index++ else onDone() }) {
                            Text(if (index < steps.lastIndex) tr("Далее") else tr("Понятно"), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Puts the card where the spotlight is not, and always fully on the screen: under the target if
 * it fits there, else above it, else beside it (a tab panel along the side of the screen), else
 * in the middle. (The card used to be placed under a tall side panel, off the screen, and the
 * tour could not be left.)
 */
@Composable
private fun TourCardLayout(target: Rect?, card: @Composable () -> Unit) {
    val insets = WindowInsets.safeDrawing
    androidx.compose.ui.layout.Layout(card, Modifier.fillMaxSize()) { measurables, constraints ->
        val m = measurables.first()
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val margin = 16.dp.roundToPx()
        val gap = 14.dp.roundToPx()
        val top = insets.getTop(this) + margin
        val bottom = h - insets.getBottom(this) - margin
        val left = insets.getLeft(this, layoutDirection) + margin
        val right = w - insets.getRight(this, layoutDirection) - margin
        val fullW = minOf(right - left, 460.dp.roundToPx()).coerceAtLeast(1)
        val minSide = 220.dp.roundToPx()
        fun heightAt(width: Int) = m.maxIntrinsicHeight(width)
        var cw = fullW
        var x: Int
        var y: Int
        if (target == null) {
            val ch = heightAt(cw)
            x = (w - cw) / 2
            y = (h - ch) / 2
        } else {
            val t = target
            val ch = heightAt(fullW)
            val below = bottom - (t.bottom.toInt() + gap)
            val above = (t.top.toInt() - gap) - top
            val leftRoom = (t.left.toInt() - gap) - left
            val rightRoom = right - (t.right.toInt() + gap)
            fun centeredX() = (t.center.x.toInt() - cw / 2).coerceIn(left, (right - cw).coerceAtLeast(left))
            when {
                below >= ch -> { x = centeredX(); y = t.bottom.toInt() + gap }
                above >= ch -> { x = centeredX(); y = t.top.toInt() - gap - ch }
                maxOf(leftRoom, rightRoom) >= minSide -> {
                    val onLeft = leftRoom >= rightRoom
                    cw = minOf(if (onLeft) leftRoom else rightRoom, 460.dp.roundToPx())
                    val sh = heightAt(cw)
                    x = if (onLeft) t.left.toInt() - gap - cw else t.right.toInt() + gap
                    y = (t.center.y.toInt() - sh / 2).coerceIn(top, (bottom - sh).coerceAtLeast(top))
                }
                else -> { x = (w - cw) / 2; y = (h - ch) / 2 }
            }
        }
        val maxH = (bottom - top).coerceAtLeast(1)
        val p = m.measure(androidx.compose.ui.unit.Constraints(minWidth = cw, maxWidth = cw, maxHeight = maxH))
        // whatever happened above, the card stays inside the screen
        x = x.coerceIn(0, (w - p.width).coerceAtLeast(0))
        y = y.coerceIn(top.coerceAtMost(h - p.height).coerceAtLeast(0), (h - p.height).coerceAtLeast(0))
        layout(w, h) { p.place(x, y) }
    }
}
