package app.malachite.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.malachite.player.data.NavPosition
import app.malachite.player.data.PartStyle
import app.malachite.player.data.TabSlideAnim
import app.malachite.player.data.Tab
import app.malachite.player.ui.help.tourTarget
import kotlinx.coroutines.launch

/**
 * The tab panel. A tap opens a tab, as always. Holding the finger on the panel for a moment
 * ([slideDelayMs]) lifts the pill: it follows the finger along the panel, the tab under it lights
 * up, and when the finger is lifted the pill glides to the nearest tab ([slideAnim]) and that tab
 * opens. The pill also glides when a tab is simply tapped.
 */
@Composable
fun TabBar(
    tabs: List<Tab>,
    selected: Tab?,
    position: NavPosition,
    style: PartStyle,
    labels: Boolean,
    cover: Bitmap?,
    slide: Boolean,
    slideDelayMs: Int,
    slideAnim: TabSlideAnim,
    onSelect: (Tab) -> Unit,
) {
    if (tabs.isEmpty()) return
    val scale = style.scale.coerceIn(0.7f, 1.6f)
    val vertical = position == NavPosition.LEFT || position == NavPosition.RIGHT
    val bg = MaterialTheme.colorScheme.surfaceContainer
    val pillColor = MaterialTheme.colorScheme.secondaryContainer
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // where each tab's icon is (window coordinates) and where the panel is
    val icons = remember { mutableStateMapOf<Int, Rect>() }
    var panelAt by remember { mutableStateOf(Offset.Zero) }
    fun center(i: Int): Float? = icons[i]?.let { r -> if (vertical) r.center.y - panelAt.y else r.center.x - panelAt.x }

    val selectedIndex = tabs.indexOf(selected)
    var dragging by remember { mutableStateOf(false) }
    var hovered by remember { mutableIntStateOf(-1) }
    val pill = remember { Animatable(Float.NaN) }
    val lift by animateFloatAsState(if (dragging) 1.12f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pillLift")
    val spec: AnimationSpec<Float> = when (slideAnim) {
        TabSlideAnim.SPRING -> spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
        TabSlideAnim.SMOOTH -> tween(380, easing = FastOutSlowInEasing)
        TabSlideAnim.FAST -> tween(140, easing = FastOutSlowInEasing)
        TabSlideAnim.NONE -> tween(0)
    }
    val currentSpec by rememberUpdatedState(spec)

    // the pill goes to the open tab (at once the first time, gliding afterwards)
    val target = center(selectedIndex)
    LaunchedEffect(selectedIndex, target, dragging) {
        if (dragging || target == null) return@LaunchedEffect
        if (pill.value.isNaN()) pill.snapTo(target) else pill.animateTo(target, currentSpec)
    }

    fun nearest(at: Float): Int = tabs.indices.minByOrNull { i -> center(i)?.let { kotlin.math.abs(it - at) } ?: Float.MAX_VALUE } ?: -1

    val onSelectNow by rememberUpdatedState(onSelect)
    val gesture = if (!slide) Modifier else Modifier.pointerInput(tabs, slideDelayMs, vertical) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // a tap or a scroll ends before the delay: it is left to the tabs and the panel
            val early = withTimeoutOrNull(slideDelayMs.toLong()) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (early != null) return@awaitEachGesture
            dragging = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            fun along(p: Offset) = if (vertical) p.y else p.x
            hovered = nearest(along(down.position))
            scope.launch { pill.snapTo(along(down.position)) }
            var last = down.position
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                // from here on the finger belongs to the pill: no tab is tapped, nothing scrolls
                c.consume()
                if (!c.pressed) break
                last = c.position
                scope.launch { pill.snapTo(along(last)) }
                val now = nearest(along(last))
                if (now != hovered && now >= 0) {
                    hovered = now
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            val chosen = nearest(along(last))
            dragging = false
            hovered = -1
            if (chosen >= 0) {
                center(chosen)?.let { c -> scope.launch { pill.animateTo(c, currentSpec) } }
                if (tabs[chosen] != selected) onSelectNow(tabs[chosen])
            }
        }
    }

    // the pill is drawn behind the icons: the tab it is under shows through it
    val pillDraw = Modifier.drawBehind {
        val at = pill.value
        val any = icons[if (hovered >= 0) hovered else selectedIndex] ?: icons.values.firstOrNull() ?: return@drawBehind
        if (at.isNaN() || (selectedIndex < 0 && !dragging)) return@drawBehind
        val w = any.width * lift
        val h = any.height * lift
        val topLeft = if (vertical) Offset(any.center.x - panelAt.x - w / 2, at - h / 2) else Offset(at - w / 2, any.center.y - panelAt.y - h / 2)
        drawRoundRect(pillColor, topLeft, Size(w, h), CornerRadius(h / 2, h / 2))
    }
    val panelPlace = Modifier.onGloballyPositioned { panelAt = it.positionInWindow() }

    val item: @Composable (Int, Tab, Modifier) -> Unit = { i, tab, m ->
        val lit = if (dragging) i == hovered else tab == selected
        Column(
            m.tourTarget("tab_${tab.name}").clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) }.padding(vertical = 6.dp * scale, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .onGloballyPositioned { c -> Rect(c.positionInWindow(), c.size.toSize()).let { r -> if (icons[i] != r) icons[i] = r } }
                    .padding(horizontal = 14.dp * scale, vertical = 4.dp * scale),
            ) {
                Icon(
                    tab.icon(), tab.title,
                    Modifier.size(24.dp * scale),
                    tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (labels) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (vertical) {
        PartBackground(style, bg, cover, Modifier.fillMaxHeight().width(84.dp * scale).tourTarget("nav")) {
            // scrolls when the window is too low for all tabs (a phone on its side)
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).then(panelPlace).then(pillDraw).then(gesture).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                tabs.forEachIndexed { i, t -> item(i, t, Modifier.fillMaxWidth()) }
            }
        }
    } else {
        PartBackground(style, bg, cover, Modifier.fillMaxWidth().tourTarget("nav")) {
            Row(Modifier.fillMaxWidth().then(panelPlace).then(pillDraw).then(gesture).padding(horizontal = 4.dp, vertical = 2.dp)) {
                tabs.forEachIndexed { i, t -> item(i, t, Modifier.weight(1f)) }
            }
        }
    }
}
