package app.malachite.player.ui.components

import app.malachite.core.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.player.ui.LocalNavigator

/**
 * Several things chosen at once – songs, albums, artists or playlists – in the list that is open.
 * Each open list has its own: what is chosen in a playlist is not chosen in "Songs".
 */
@Stable
class Selection {
    var active by mutableStateOf(false)
        private set
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    val size get() = keys.size

    operator fun contains(key: String) = key in keys

    fun start(key: String? = null) {
        active = true
        if (key != null) keys = keys + key
    }

    fun toggle(key: String) { keys = if (key in keys) keys - key else keys + key }
    fun select(all: Collection<String>) { keys = all.toSet() }
    fun clear() { keys = emptySet() }
    fun close() { active = false; keys = emptySet() }

    /** Keeps only what is still in the list (after a deletion or a rename). */
    fun retain(present: Collection<String>) {
        if (keys.isEmpty()) return
        val p = present as? Set<String> ?: present.toHashSet()
        if (keys.any { it !in p }) keys = keys.filterTo(HashSet()) { it in p }
    }
}

/**
 * The choices of the open screens, kept in memory while the app runs: a look at another tab
 * does not lose them (they are not written into the saved state – thousands of chosen songs
 * would not fit there).
 */
object Selections {
    private val kept = HashMap<String, Selection>()

    fun of(key: String): Selection = synchronized(kept) { kept.getOrPut(key) { Selection() } }

    /** The screen [screenKey] was closed: its choices go. */
    fun forget(screenKey: String) = synchronized(kept) { kept.keys.removeAll { it.startsWith("$screenKey#") } }
}

/** The choice of the list being shown, for buttons in its header ("choose several"). */
val LocalListSelection = staticCompositionLocalOf<Selection?> { null }

/** The name of the open screen (see [app.malachite.player.ui.Navigator.currentKey]). */
val LocalScreenKey = staticCompositionLocalOf { "" }

/** The choice of the list on the open screen; [part] tells lists of one screen apart. */
@Composable
fun rememberSelection(part: String = "list"): Selection {
    val key = LocalScreenKey.current + "#" + part
    return remember(key) { Selections.of(key) }
}

/**
 * A quick way to choose: "added in the last hour", "without a cover"… [keys] are what it chooses;
 * [whenNone] says why nothing was chosen ("the last songs came yesterday at 23:31").
 */
data class Pick(val title: String, val whenNone: (() -> String)? = null, val keys: () -> Collection<String>)

/**
 * "Choose by name": the fields that can be searched (artist, album, title…) and, for each, the
 * names an item of the list has there (by its key).
 */
class ByName(val fields: List<Pair<String, (String) -> List<String>>>)

/**
 * "Choose by artist / album / year…": the values the items of the list have, each with the keys of
 * the items that have it. Shown as a list with counts (and a search when it is long): a tap adds all
 * of them to what is chosen – all the songs of one artist in two taps.
 */
data class GroupChoice(val title: String, val values: () -> List<Pair<String, List<String>>>)

/** The panel above a list while things are being chosen: how many, quick choices, what to do with them. */
@Composable
fun SelectionBar(
    sel: Selection,
    all: List<String>,
    picks: List<Pick> = emptyList(),
    byName: ByName? = null,
    actions: () -> List<MenuAction>,
    groups: List<GroupChoice> = emptyList(),
) {
    val nav = LocalNavigator.current
    BackHandler(enabled = sel.active && !nav.playerOpen && nav.dialog == null) { sel.close() }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf<GroupChoice?>(null) }
    if (naming && byName != null) ChooseByNameDialog(byName, all, sel) { naming = false }
    grouping?.let { g -> ChooseGroupDialog(g, sel) { grouping = null } }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sel.close() }) { Icon(Icons.Filled.Close, tr("Закончить выбор")) }
                Text(
                    tr("Выбрано: ${sel.size} из ${all.size}"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { menu = true }, enabled = sel.size > 0) { Icon(Icons.Filled.MoreVert, tr("Что сделать с выбранным")) }
                SelectionMenu(menu, { menu = false }, tr("Выбрано: ${sel.size}"), actions)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sel.size < all.size) AssistChip(onClick = { sel.select(all) }, label = { Text(tr("Выбрать все")) })
                if (sel.size > 0) AssistChip(onClick = { sel.clear() }, label = { Text(tr("Снять все")) })
                if (sel.size > 0 && sel.size < all.size) AssistChip(onClick = { sel.select(all.filter { it !in sel }) }, label = { Text(tr("Наоборот")) })
                groups.forEach { g ->
                    AssistChip(onClick = { grouping = g }, label = { Text(g.title + "…") }, leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) })
                }
                if (byName != null) AssistChip(
                    onClick = { naming = true }, label = { Text(tr("По имени…")) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                )
                picks.forEach { p ->
                    AssistChip(onClick = {
                        val chosen = p.keys()
                        sel.select(chosen)
                        if (chosen.isEmpty()) nav.toast(p.whenNone?.invoke() ?: tr("Таких здесь нет"))
                    }, label = { Text(p.title) })
                }
            }
        }
    }
}

/** The values of [group] to tap: each adds its items to what is chosen. */
@Composable
private fun ChooseGroupDialog(group: GroupChoice, sel: Selection, onDone: () -> Unit) {
    val nav = LocalNavigator.current
    val values = remember(group) { group.values() }
    var query by remember { mutableStateOf("") }
    val shown = remember(values, query) {
        if (query.isBlank()) values else values.filter { app.malachite.core.text.NameMatch.matches(query, it.first) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text(group.title) },
        text = {
            Column {
                if (values.size > 8) androidx.compose.material3.OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("Найти (можно латиницей)")) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                Text(
                    tr("Нажмите — все такие добавятся к выбранным"),
                    Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown.size) { i ->
                        val (name, keys) = shown[i]
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                sel.select(sel.keys + keys)
                                nav.toast(tr("Выбрано: «$name» — ${keys.size}"))
                                onDone()
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(keys.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDone) { Text(tr("Закрыть")) } },
    )
}

/** What can be done with the chosen things: from the panel, and from a long press on one of them. */
@Composable
fun SelectionMenu(expanded: Boolean, onDismiss: () -> Unit, title: String = tr("Выбранное"), actions: () -> List<MenuAction>) {
    ActionSheet(expanded, onDismiss, title, actions = actions)
}

/**
 * Choosing with the finger while choosing is on: hold an item and slide – every item between it and
 * the finger is chosen (or unchosen, when the held one already was), the list scrolls by itself
 * near its edges, and lifting the finger ends it. Several such strokes add up. Held without
 * sliding, the item is chosen and [onMenu] shows what to do with all chosen ones.
 */
@Composable
fun Modifier.dragToChoose(
    sel: Selection,
    order: List<String>,
    keyAt: (Offset) -> String?,
    scrollBy: suspend (Float) -> Unit,
    onMenu: (String) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val orderNow by rememberUpdatedState(order)
    val keyAtNow by rememberUpdatedState(keyAt)
    val scrollNow by rememberUpdatedState(scrollBy)
    val menuNow by rememberUpdatedState(onMenu)
    if (!sel.active) return this
    return this.pointerInput(sel) {
        val edge = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // a tap or a scroll ends before the long press: left to the item and the list
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (early != null) return@awaitEachGesture
            val startKey = keyAtNow(down.position) ?: return@awaitEachGesture
            val start = orderNow.indexOf(startKey)
            if (start < 0) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val base = sel.keys
            // the held item decides: an unchosen one starts choosing, a chosen one unchoosing
            val choosing = startKey !in base
            if (choosing) sel.select(base + startKey)
            var last = down.position
            var moved = false
            var lastKey = startKey
            fun follow(at: Offset) {
                val k = keyAtNow(at) ?: return
                val i = orderNow.indexOf(k)
                if (i < 0) return
                val range = orderNow.subList(minOf(start, i), maxOf(start, i) + 1)
                sel.select(if (choosing) base + range else base - range.toSet())
                if (k != lastKey) {
                    lastKey = k
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            // near the top or the bottom the list scrolls on, faster the closer the finger is to the edge
            val scroller = scope.launch {
                while (true) {
                    val h = size.height.toFloat()
                    val y = last.y
                    val speed = when {
                        y < edge -> -(edge - y) / edge * 28f
                        y > h - edge -> (y - (h - edge)) / edge * 28f
                        else -> 0f
                    }
                    if (moved && speed != 0f) {
                        scrollNow(speed)
                        follow(last)
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    // the finger belongs to the choosing now: the item is not tapped, the list does not scroll by itself
                    c.consume()
                    if (!c.pressed) break
                    last = c.position
                    if (!moved && (last - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (moved) follow(last)
                }
            } finally {
                scroller.cancel()
            }
            if (!moved) {
                if (startKey !in sel) sel.select(sel.keys + startKey)
                menuNow(startKey)
            }
        }
    }
}

/** The key of the list item under [p] (a position in the list), or null. */
fun androidx.compose.foundation.lazy.LazyListState.keyAt(p: Offset): String? {
    val info = layoutInfo
    val y = p.y.toInt() + info.viewportStartOffset
    return info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String
}

/** The key of the grid item under [p] (a position in the grid), or null. */
fun androidx.compose.foundation.lazy.grid.LazyGridState.keyAt(p: Offset): String? {
    val info = layoutInfo
    val y = p.y.toInt() + info.viewportStartOffset
    val row = info.visibleItemsInfo.filter { y >= it.offset.y && y < it.offset.y + it.size.height }
    val hit = row.firstOrNull { p.x >= it.offset.x && p.x < it.offset.x + it.size.width }
        ?: row.minByOrNull { kotlin.math.abs(it.offset.x + it.size.width / 2 - p.x) }
    return hit?.key as? String
}

/**
 * "Choose by name": a field (artist, album, title), a name typed in either alphabet ("kino" finds
 * "Кино"), names that fit offered to tap, then those items – or all but them.
 */
@Composable
private fun ChooseByNameDialog(by: ByName, all: List<String>, sel: Selection, onDismiss: () -> Unit) {
    var field by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var except by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(false) }
    val names = remember(field, all) { all.associateWith { k -> by.fields[field].second(k).filter { it.isNotBlank() } } }
    val counts = remember(names) { names.values.flatten().groupingBy { it }.eachCount() }
    var fitting by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(query, counts) {
        kotlinx.coroutines.delay(150)
        fitting = if (query.isBlank()) emptySet() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            counts.keys.filterTo(HashSet()) { app.malachite.core.text.NameMatch.matches(query, it) }
        }
    }
    val hits = remember(fitting, names) { all.filter { k -> names[k].orEmpty().any { it in fitting } }.toHashSet() }
    val result = if (query.isBlank()) emptyList() else if (except) all.filter { it !in hits } else all.filter { it in hits }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Выбрать по имени")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (by.fields.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    by.fields.forEachIndexed { i, f -> androidx.compose.material3.FilterChip(field == i, { field = i }, label = { Text(f.first) }) }
                }
                androidx.compose.material3.OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(by.fields[field].first) },
                    placeholder = { Text(tr("латиницей или кириллицей: kino = кино")) },
                )
                if (fitting.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fitting.sortedByDescending { counts[it] ?: 0 }.take(12).forEach { n ->
                            androidx.compose.material3.SuggestionChip(onClick = { query = n }, label = { Text("$n · ${counts[n] ?: 0}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(!except, { except = false }, label = { Text(tr("Только они")) })
                    androidx.compose.material3.FilterChip(except, { except = true }, label = { Text(tr("Все, кроме них")) })
                }
                if (sel.size > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(keep, { keep = it })
                    Text(tr("Оставить уже выбранные (${sel.size})"))
                }
                Text(if (query.isBlank()) tr("Введите имя") else tr("Подходит: ${result.size}"), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = result.isNotEmpty(), onClick = {
                sel.select(if (keep) sel.keys + result else result)
                onDismiss()
            }) { Text(tr("Выбрать (${result.size})")) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

/** How long ago "the last hour", "today" and "this week" begin. */
object Recent {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /** "13 нояб., 23:31 (11 ч назад)": when something happened, for messages. */
    fun describe(at: Long): String {
        val ago = System.currentTimeMillis() - at
        val stamp = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
        val rel = when {
            ago < HOUR -> tr("${maxOf(1, ago / 60_000)} мин назад")
            ago < 2 * DAY -> tr("${ago / HOUR} ч назад")
            else -> tr("${ago / DAY} дн. назад")
        }
        return "$stamp ($rel)"
    }

    fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
