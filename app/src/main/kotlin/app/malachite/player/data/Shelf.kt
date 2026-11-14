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

/** Albums and playlists the user pinned to the top or marked as favourites. */
@Serializable
data class ShelfData(
    val pinnedAlbums: Set<String> = emptySet(),
    val favoriteAlbums: Set<String> = emptySet(),
    val pinnedPlaylists: Set<String> = emptySet(),
)

/**
 * Kept in the main folder (`.malachite/shelf.json`) like favourites of songs are: it survives
 * a reinstall and moves to another phone together with the music.
 */
object Shelf {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ShelfData())
    val state: StateFlow<ShelfData> = _state.asStateFlow()

    @Volatile private var loadedFrom: String? = null

    private fun file(): File? = MalachiteApp.graph.library.root?.let { File(it, ".malachite/shelf.json") }

    /** Reads the file once per main folder. Cheap to call from every screen that needs it. */
    fun ensureLoaded() {
        val f = file() ?: return
        if (loadedFrom == f.path) return
        loadedFrom = f.path
        scope.launch {
            val data = try {
                if (f.isFile) json.decodeFromString(ShelfData.serializer(), app.malachite.core.fs.Sealed.readText(f)) else ShelfData()
            } catch (e: app.malachite.core.fs.Sealed.Locked) {
                loadedFrom = null // protected and locked: read again later, never written over
                return@launch
            } catch (e: Exception) {
                ShelfData()
            }
            _state.value = data
        }
    }

    fun update(change: (ShelfData) -> ShelfData) {
        val data = change(_state.value)
        _state.value = data
        scope.launch {
            runCatching {
                val f = file() ?: return@launch
                if (loadedFrom != f.path) return@launch
                app.malachite.core.library.LibraryStore.writeAtomicBytes(
                    f, app.malachite.core.fs.Sealed.encodeForWrite(json.encodeToString(ShelfData.serializer(), data)),
                )
            }
        }
    }

    private fun Set<String>.toggled(key: String) = if (key in this) this - key else this + key

    fun togglePinnedAlbum(key: String) = update { it.copy(pinnedAlbums = it.pinnedAlbums.toggled(key)) }
    fun toggleFavoriteAlbum(key: String) = update { it.copy(favoriteAlbums = it.favoriteAlbums.toggled(key)) }
    fun togglePinnedPlaylist(name: String) = update { it.copy(pinnedPlaylists = it.pinnedPlaylists.toggled(name)) }

    /** A renamed album or playlist keeps its pin and its heart. */
    fun albumRenamed(oldKey: String, newKey: String) = update {
        it.copy(
            pinnedAlbums = if (oldKey in it.pinnedAlbums) it.pinnedAlbums - oldKey + newKey else it.pinnedAlbums,
            favoriteAlbums = if (oldKey in it.favoriteAlbums) it.favoriteAlbums - oldKey + newKey else it.favoriteAlbums,
        )
    }

    fun playlistRenamed(old: String, new: String) = update {
        it.copy(pinnedPlaylists = if (old in it.pinnedPlaylists) it.pinnedPlaylists - old + new else it.pinnedPlaylists)
    }
}
