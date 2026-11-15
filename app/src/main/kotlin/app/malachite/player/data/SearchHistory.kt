package app.malachite.player.data

import app.malachite.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.player.MalachiteApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What was searched for lately, newest first: in the library and in the online section, each a
 * list of its own. Kept only in the app's own folder on this phone.
 */
object SearchHistory {
    enum class Kind(val mark: String) { LIBRARY("L"), ONLINE("O") }

    private const val MAX = 15

    /** A query typed further within this time replaces the one it grew from ("pix" → "pixies"). */
    private const val GROWS_MS = 60_000L

    private val lists = Kind.entries.associateWith { MutableStateFlow<List<String>>(emptyList()) }
    private var lastAdded = 0L
    @Volatile private var loaded = false

    private fun file() = File(MalachiteApp.graph.context.filesDir, "search_history.txt")

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.isFile) return
        val read = runCatching { f.readLines() }.getOrDefault(emptyList())
        for (k in Kind.entries) {
            lists.getValue(k).value = read.filter { it.startsWith(k.mark + "\t") }.map { it.substring(2) }.filter { it.isNotBlank() }.take(MAX)
        }
    }

    private fun save() {
        val text = Kind.entries.flatMap { k -> lists.getValue(k).value.map { k.mark + "\t" + it } }.joinToString("\n")
        runCatching { app.malachite.core.library.LibraryStore.writeAtomic(file(), text) }
    }

    fun recent(kind: Kind): StateFlow<List<String>> {
        load()
        return lists.getValue(kind)
    }

    @Synchronized
    fun add(kind: Kind, query: String) {
        load()
        val q = query.replace('\t', ' ').replace('\n', ' ').trim()
        if (q.length < 2) return
        val now = System.currentTimeMillis()
        var list = lists.getValue(kind).value.filterNot { it.equals(q, ignoreCase = true) }
        // typed on after a pause: the shorter beginning goes
        val first = list.firstOrNull()
        if (first != null && now - lastAdded < GROWS_MS && q.startsWith(first, ignoreCase = true)) list = list.drop(1)
        lastAdded = now
        lists.getValue(kind).value = (listOf(q) + list).take(MAX)
        save()
    }

    @Synchronized
    fun remove(kind: Kind, query: String) {
        load()
        lists.getValue(kind).value = lists.getValue(kind).value.filterNot { it == query }
        save()
    }

    @Synchronized
    fun clear(kind: Kind) {
        load()
        lists.getValue(kind).value = emptyList()
        save()
    }
}

/** The recent queries under an empty search field: a tap searches again, × forgets one. */
@Composable
fun RecentSearches(kind: SearchHistory.Kind, onPick: (String) -> Unit) {
    val recent by SearchHistory.recent(kind).collectAsState()
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Недавние запросы"), Modifier.weight(1f).padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { SearchHistory.clear(kind) }) { Text(tr("Очистить историю")) }
        }
        recent.forEach { q ->
            Row(Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { SearchHistory.remove(kind, q) }) { Icon(Icons.Filled.Close, tr("Убрать из истории")) }
            }
        }
    }
}
