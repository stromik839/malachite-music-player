package app.malachite.player.data

import app.malachite.player.MalachiteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * An unfinished manual lyric sync of one song: the text, the way it was being recorded and
 * every mark made so far. With it the recording continues from the same word after the
 * screen was left, the app was closed or the phone ran out of battery.
 */
@Serializable
data class SyncDraft(
    val path: String,
    val title: String,
    val artist: String,
    val text: String,
    val removeParens: Boolean,
    val wordMode: Boolean,
    val speed: Float,
    val reactionMs: Int,
    /** Lines or words (what one press marks) done so far, and how many there are. */
    val cursor: Int,
    val total: Int,
    val starts: List<Long>,
    val ends: List<Long>,
    val updatedAt: Long,
    /** The exact lines being recorded; when missing they are made from [text] again. */
    val lines: List<String>? = null,
) {
    val fraction: Float get() = if (total > 0) (cursor.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Unfinished syncs live in the main folder (`.malachite/sync-drafts`, one file per song), so
 * they survive a reinstall and travel with the folder. There can be any number of them.
 */
object SyncDrafts {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _all = MutableStateFlow<List<SyncDraft>>(emptyList())
    val all: StateFlow<List<SyncDraft>> = _all.asStateFlow()

    private fun dir(): File? = MalachiteApp.graph.library.root?.let { File(it, ".malachite/sync-drafts") }

    private fun fileOf(path: String): File? = dir()?.let { d ->
        val hash = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        File(d, "$hash.json")
    }

    /** Reads the drafts from the main folder. Call off the main thread. */
    fun refresh() {
        val list = synchronized(lock) {
            dir()?.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { json.decodeFromString(SyncDraft.serializer(), app.malachite.core.fs.Sealed.readText(f)) }.getOrNull()
            }
        }
        _all.value = list.sortedByDescending { it.updatedAt }
    }

    /** The draft of this song, if there is one. Call off the main thread. */
    fun load(path: String): SyncDraft? = synchronized(lock) {
        fileOf(path)?.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(SyncDraft.serializer(), app.malachite.core.fs.Sealed.readText(f)) }.getOrNull() }
    }

    private fun write(draft: SyncDraft) {
        synchronized(lock) {
            val f = fileOf(draft.path) ?: return
            runCatching {
                f.parentFile?.mkdirs()
                // through a temporary file: a draft is never left half-written
                app.malachite.core.library.LibraryStore.writeAtomicBytes(
                    f, app.malachite.core.fs.Sealed.encodeForWrite(json.encodeToString(SyncDraft.serializer(), draft)),
                )
            }.onFailure { AppLog.e("sync", "Не сохранилась незаконченная синхронизация", it) }
        }
        _all.value = (_all.value.filter { it.path != draft.path } + draft).sortedByDescending { it.updatedAt }
    }

    /** Saves right away (call off the main thread) – for a draft that is opened at once after. */
    fun saveNow(draft: SyncDraft) = write(draft)

    /** Saves in the background; safe to call when a screen is being closed. */
    fun save(draft: SyncDraft) { scope.launch { write(draft) } }

    fun delete(path: String) {
        _all.value = _all.value.filter { it.path != path }
        scope.launch { synchronized(lock) { runCatching { fileOf(path)?.delete() } } }
    }
}
