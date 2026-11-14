package app.malachite.player.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A song being carried to another place of the list: where it came from, where it would land, how far it moved. */
@Stable
class ReorderState {
    var from by mutableIntStateOf(-1)
        internal set
    var target by mutableIntStateOf(-1)
        internal set
    /** How far the carried row is from its place (it follows the finger). */
    var offset by mutableFloatStateOf(0f)
        internal set
    /**
     * Where the top of the carried row is seen, in the list's own space (kept inside the list).
     * The row is drawn there above the list: its own place may scroll out of sight, and the list
     * no longer draws it then.
     */
    var top by mutableFloatStateOf(0f)
        internal set
    internal var height by mutableIntStateOf(0)

    val active get() = from >= 0

    /** How far the row at [index] steps aside to make room for the carried one. */
    fun shiftOf(index: Int): Float = when {
        !active || index == from -> 0f
        from < target && index in (from + 1)..target -> -height.toFloat()
        from > target && index in target until from -> height.toFloat()
        else -> 0f
    }

    internal fun reset() {
        from = -1
        target = -1
        offset = 0f
        top = 0f
    }
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/**
 * Hold a row and carry it up or down: it lifts and follows the finger, the others step aside, the
 * list scrolls by itself near its edges, and where the finger is lifted it lands ([onMove] with
 * the old and the new place). Held without moving, [onHold] is called instead (the row's menu).
 * [indexOf] tells the place of a list item by its key; -1 for items that do not move (a header).
 */
@Composable
fun Modifier.dragToReorder(
    state: ReorderState,
    list: LazyListState,
    enabled: Boolean,
    indexOf: (Any) -> Int,
    onMove: (from: Int, to: Int) -> Unit,
    onHold: (Int) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val indexNow by rememberUpdatedState(indexOf)
    val moveNow by rememberUpdatedState(onMove)
    val holdNow by rememberUpdatedState(onHold)
    if (!enabled) return this
    return this.pointerInput(state, list) {
        val edge = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // a tap or a scroll ends before the long press: left to the row and the list
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (early != null) return@awaitEachGesture
            val info = list.layoutInfo
            val y0 = down.position.y + info.viewportStartOffset
            val item = info.visibleItemsInfo.firstOrNull { y0 >= it.offset && y0 < it.offset + it.size } ?: return@awaitEachGesture
            val start = indexNow(item.key)
            if (start < 0) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // where the row was when it was taken, in the list's own space
            val top0 = (item.offset - info.viewportStartOffset).toFloat()
            fun seenTop(dy: Float) = (top0 + dy).coerceIn(0f, (size.height - item.size).toFloat().coerceAtLeast(0f))
            state.height = item.size
            state.top = top0
            state.from = start
            state.target = start
            state.offset = 0f
            var last = down.position
            var scrolled = 0f
            var moved = false
            fun follow() {
                state.offset = (last.y - down.position.y) + scrolled
                state.top = seenTop(last.y - down.position.y)
                // the middle of the carried row, where it is seen now
                val middle = state.top + list.layoutInfo.viewportStartOffset + item.size / 2f
                val under = list.layoutInfo.visibleItemsInfo.firstOrNull { middle >= it.offset && middle < it.offset + it.size }
                val i = under?.let { indexNow(it.key) } ?: -1
                if (i >= 0 && i != state.target) {
                    state.target = i
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            val scroller = scope.launch {
                while (true) {
                    val h = size.height.toFloat()
                    val speed = when {
                        last.y < edge -> -(edge - last.y) / edge * 24f
                        last.y > h - edge -> (last.y - (h - edge)) / edge * 24f
                        else -> 0f
                    }
                    if (moved && speed != 0f) {
                        scrolled += list.scrollBy(speed)
                        follow()
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    // the finger carries the row now: nothing is tapped, the list does not scroll by itself
                    c.consume()
                    if (!c.pressed) break
                    last = c.position
                    if (!moved && (last - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (moved) follow()
                }
            } finally {
                scroller.cancel()
            }
            val from = state.from
            val to = state.target
            if (moved && to >= 0 && to != from) moveNow(from, to)
            state.reset()
            if (!moved) holdNow(start)
        }
    }
}
