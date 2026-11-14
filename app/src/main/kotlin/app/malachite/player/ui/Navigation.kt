package app.malachite.player.ui

import app.malachite.core.i18n.tr
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.malachite.core.library.TrackInfo
import app.malachite.player.data.Tab

/** Screens of the app (a tiny hand-made navigator – no route strings to get wrong). */
sealed interface Screen {
    data class TabRoot(val tab: Tab) : Screen
    data class ArtistDetail(val key: String) : Screen
    data class AlbumDetail(val key: String) : Screen
    data object NoAlbum : Screen
    data class PlaylistDetail(val name: String) : Screen
    data class SmartPlaylist(val kind: SmartKind) : Screen
    data class Folder(val relative: String) : Screen
    data class LyricsSync(val path: String, val resume: Boolean = false) : Screen
    data object Processes : Screen
    data object Help : Screen
    data class LyricsEdit(val path: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object SettingsAppearance : Screen
    data object SettingsMenu : Screen
    data object SettingsLibrary : Screen
    data object SettingsLyrics : Screen
    data object SettingsPlayback : Screen
    data object SettingsWidgets : Screen
    data object SettingsBackup : Screen
    data object Equalizer : Screen
    data object SettingsAdvanced : Screen
    data object SettingsDiagnostics : Screen
    data object SettingsSoundId : Screen
    data object About : Screen
    data object Terms : Screen
    data object Support : Screen
    data class Online(val query: String = "") : Screen
    data object ImportScreen : Screen
}

enum class SmartKind(val title: String) {
    FAVORITES(tr("Избранное")), MOST_PLAYED(tr("Часто слушаю")), RECENTLY_ADDED(tr("Недавно добавленные")), RECENTLY_PLAYED(tr("Недавно играли")),
}

/** Dialogs that can be opened from anywhere (track menus, player, etc.). */
sealed interface DialogRequest {
    data class AddToPlaylist(val paths: List<String>) : DialogRequest
    data class EditTags(val track: TrackInfo) : DialogRequest
    data class FindCover(val tracks: List<TrackInfo>) : DialogRequest
    data class FindLyrics(val track: TrackInfo) : DialogRequest
    data class Delete(val track: TrackInfo) : DialogRequest
    data class Info(val track: TrackInfo) : DialogRequest
    /** Several chosen songs at once. */
    data class DeleteMany(val tracks: List<TrackInfo>) : DialogRequest
    data class InfoMany(val tracks: List<TrackInfo>) : DialogRequest
    data class EditTagsMany(val tracks: List<TrackInfo>) : DialogRequest
    data class HealSelected(val paths: List<String>) : DialogRequest
}

/**
 * Each tab keeps its own way in: a playlist opened in "Playlists" is still open (and scrolled
 * where it was) after a look at "Songs". A tap on the tab that is already shown goes back to its
 * start, like in most apps.
 */
@Stable
class Navigator(firstTab: Tab) {
    // made for every tab at once: a list made while the screen is only being read (for the
    // detailed log, say) is not allowed to change and closed the app
    private val stacks: Map<Tab, androidx.compose.runtime.snapshots.SnapshotStateList<Screen>> =
        Tab.entries.associateWith { mutableStateListOf<Screen>(Screen.TabRoot(it)) }
    var tab by mutableStateOf(firstTab)
        private set
    var playerOpen by mutableStateOf(false)
    var dialog by mutableStateOf<DialogRequest?>(null)
    var snackbar by mutableStateOf<String?>(null)

    /** Told about screens that were closed, so their kept state (scroll position) is let go. */
    var onForget: ((String) -> Unit)? = null

    /** The screens of the tab being shown, its start first. */
    val stack: List<Screen> get() = stackOf(tab)

    private fun stackOf(t: Tab) = stacks.getValue(t)

    val current: Screen get() = stack.last()
    val currentTab: Tab get() = tab

    /** A name for the screen at the top of the stack, unique within all tabs (for its kept state). */
    val currentKey: String get() = keyOf(tab, stack.size - 1, current)

    private fun keyOf(t: Tab, depth: Int, s: Screen) = "${t.name}/$depth/$s"

    fun push(s: Screen) {
        playerOpen = false
        val st = stackOf(tab)
        if (st.lastOrNull() != s) st.add(s)
    }

    fun openTab(t: Tab) {
        playerOpen = false
        if (t == tab) {
            // the tab that is open: back to its start
            val st = stackOf(t)
            while (st.size > 1) pop(t, st)
        } else {
            tab = t
        }
    }

    private fun pop(t: Tab, st: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>) {
        val gone = st.removeAt(st.size - 1)
        onForget?.invoke(keyOf(t, st.size, gone))
    }

    /** Returns false if there is nothing to go back to (the activity should finish). */
    fun back(): Boolean {
        if (dialog != null) { dialog = null; return true }
        if (playerOpen) { playerOpen = false; return true }
        val st = stackOf(tab)
        if (st.size > 1) { pop(tab, st); return true }
        return false
    }

    fun show(d: DialogRequest) { dialog = d }
    fun toast(text: String) { snackbar = text }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
