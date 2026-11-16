package app.malachite.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.malachite.core.library.TrackInfo
import app.malachite.player.data.LibraryRepository
import app.malachite.player.data.SettingsRepository
import app.malachite.player.ui.theme.shade
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log2

data class PlayerUiState(
    val connected: Boolean = false,
    val currentPath: String? = null,
    /** Meant to play (see [meantToPlay]): stays on while a seek loads, so buttons do not flicker. */
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<String> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val sleepAtMs: Long = 0,
    val sleepAfterTrack: Boolean = false,
)

/**
 * "Playing" as a person sees it: the player is meant to play. After a seek (or at the start of a
 * song) the player waits a moment for the sound to load; [Player.isPlaying] is false then, and a
 * button that followed it flipped to "play" and back on every seek. A tap on it in that moment
 * even pressed "play" instead of "pause". The same rule as the system notification's button.
 */
fun Player.meantToPlay(): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED &&
        playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

/** Play or pause by what the play button shows (see [meantToPlay]). */
fun Player.playPause() {
    if (meantToPlay()) { pause(); return }
    if (playbackState == Player.STATE_IDLE) prepare()
    if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
    play()
}

/** UI-side connection to [PlaybackService] through a MediaController. */
class PlayerConnection(
    private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(action: (MediaController) -> Unit) {
        // a tap acts at once: a post would wait behind the next frame, and the seek bar showed the
        // old place for that time
        val ready = controller
        if (ready != null && Looper.myLooper() == Looper.getMainLooper()) {
            action(ready)
            return
        }
        main.post {
            val c = controller
            if (c != null) action(c) else {
                pending += action
                connect()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            publish(queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
    }

    /** The queue as ids, rebuilt only when it changed (not on every seek or pause of a long queue). */
    private var queue: List<String> = emptyList()

    private fun publish(queueChanged: Boolean = true) {
        val c = controller ?: return
        if (queueChanged) queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val p = c.playbackParameters
        _state.value = _state.value.copy(
            connected = true,
            currentPath = c.currentMediaItem?.mediaId,
            isPlaying = c.meantToPlay(),
            durationMs = c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.mediaId?.let { library.track(it)?.durationMs } ?: 0),
            queue = queue,
            index = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = p.speed,
            pitchSemitones = (12 * log2(p.pitch.toDouble())).toFloat(),
        )
        checkSleepAfterTrack()
        prefetchCovers(c)
    }

    /**
     * The covers (with their colours and frosted glass) of the song playing and of the songs "next"
     * and "previous" lead to are made ready while it plays: the song screen then opens with its
     * cover and colours at once (asked again after a change of the queue or of shuffle; the same
     * songs are not loaded twice).
     */
    private fun prefetchCovers(c: MediaController) {
        val now = c.currentMediaItem?.mediaId ?: return
        val count = c.mediaItemCount
        val around = if (count < 2) emptyList() else {
            // the order "next" really follows (shuffle, repeat); at the ends the buttons wrap round
            val next = c.nextMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: 0
            val prev = c.previousMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: (count - 1)
            listOf(next, prev).distinct().filter { it in 0 until count }.map { c.getMediaItemAt(it).mediaId }.filter { it != now }
        }
        val s = settings.value
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        app.malachite.player.data.CoverPrefetch.around(listOf(now) + around, s.shade(dark), s.coverColorsInPlayer || s.coverColorsEverywhere)
    }

    /**
     * Current position in ms on the song's own (1x) timeline. Uses the player in the service
     * directly when possible: that is the exact render position, important for lyrics sync.
     */
    fun positionMs(): Long {
        val svc = PlaybackService.instance
        if (svc != null && Looper.myLooper() == Looper.getMainLooper()) return svc.player.currentPosition
        return controller?.currentPosition ?: 0
    }

    // ------------------------------------------------------------------ commands

    fun play(tracks: List<TrackInfo>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        // "Вперемешку" leaves out what the user does not like (unless nothing else is left)
        val data = library.data.value
        val list = if (shuffle) tracks.filterNot { data.disliked(it) }.ifEmpty { tracks } else tracks
        val items = list.map { PlaybackService.buildItem(it, null) }
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, if (shuffle) (0 until items.size).random() else start.coerceIn(0, items.size - 1), 0)
            c.prepare()
            c.play()
        }
    }

    fun playPaths(paths: List<String>, start: Int = 0, shuffle: Boolean = false) =
        play(paths.mapNotNull { library.track(it) }, start, shuffle)

    fun toggle() = withController { it.playPause() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { c -> if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0) }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(t: TrackInfo) = withController { c ->
        val item = PlaybackService.buildItem(t, null)
        if (c.mediaItemCount == 0) { c.setMediaItem(item); c.prepare(); c.play() } else c.addMediaItem(c.currentMediaItemIndex + 1, item)
    }

    /** The songs right after the one playing, in their order. */
    fun playNext(tracks: List<TrackInfo>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        val items = tracks.map { PlaybackService.buildItem(it, null) }
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() } else c.addMediaItems(c.currentMediaItemIndex + 1, items)
    }

    fun addToQueue(tracks: List<TrackInfo>) = withController { c ->
        val items = tracks.map { PlaybackService.buildItem(it, null) }
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare() } else c.addMediaItems(items)
    }

    fun removeFromQueue(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) = withController { c -> if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to) }

    /**
     * Speed and pitch are independent (like a DJ deck): speed changes tempo, pitch is in
     * semitones. With "linked" mode the pitch follows the speed (classic "nightcore" effect).
     */
    fun setSpeedPitch(speed: Float, semitones: Float, rememberForSong: Boolean = settings.value.rememberSpeedPerSong) {
        val s = speed.coerceIn(0.25f, 3f)
        val p = if (settings.value.linkPitchToSpeed) (12 * log2(s.toDouble())).toFloat() else semitones.coerceIn(-12f, 12f)
        withController { it.playbackParameters = PlaybackService.params(s, p) }
        val path = _state.value.currentPath
        if (rememberForSong && path != null) {
            val isDefault = s == 1f && p == 0f
            library.rememberSpeed(path, if (isDefault) null else s, if (isDefault) null else p)
        } else {
            settings.update { it.copy(speed = s, pitchSemitones = p) }
        }
    }

    /** Temporary speed (used by the lyrics sync editor) that is not remembered. */
    fun setTemporarySpeed(speed: Float) = withController { c ->
        c.playbackParameters = PlaybackParameters(speed, 1f)
    }

    fun restoreSpeed() {
        val path = _state.value.currentPath
        val s = settings.value
        val u = path?.let { library.data.value.user(it) }
        withController { it.playbackParameters = PlaybackService.params(u?.speed ?: s.speed, u?.pitchSemitones ?: s.pitchSemitones) }
    }

    fun setSkipSilence(on: Boolean) {
        settings.update { it.copy(skipSilence = on) }
        PlaybackService.instance?.player?.skipSilenceEnabled = on
    }

    // ------------------------------------------------------------------ sleep timer

    private val sleepRunnable = Runnable {
        controller?.pause()
        _state.value = _state.value.copy(sleepAtMs = 0, sleepAfterTrack = false)
    }

    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepAtMs = 0, sleepAfterTrack = false)
            return
        }
        _state.value = _state.value.copy(sleepAtMs = System.currentTimeMillis() + minutes * 60_000L, sleepAfterTrack = false)
        main.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    private var sleepTrack: String? = null

    fun sleepAfterCurrentTrack() {
        main.removeCallbacks(sleepRunnable)
        sleepTrack = _state.value.currentPath
        _state.value = _state.value.copy(sleepAtMs = 0, sleepAfterTrack = true)
    }

    private fun checkSleepAfterTrack() {
        val st = _state.value
        if (st.sleepAfterTrack && sleepTrack != null && st.currentPath != sleepTrack) {
            controller?.pause()
            sleepTrack = null
            _state.value = st.copy(sleepAfterTrack = false)
        }
    }

    /** After healing moved files, rebuild the queue with the new paths keeping position. */
    fun remapQueue(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        withController { c ->
            val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
            if (ids.none { it in moves }) return@withController
            // every song stays in the queue: one the library has not picked up at its new place
            // yet is still found there by its path (dropping it made the queue shrink, down to a
            // single song without a "next")
            val items: List<MediaItem> = ids.map { id ->
                val p = moves[id] ?: id
                PlaybackService.buildItem(library.track(p) ?: TrackInfo(path = p), null)
            }
            val pos = c.currentPosition
            val idx = c.currentMediaItemIndex
            val playing = c.meantToPlay()
            c.setMediaItems(items, idx.coerceIn(0, (items.size - 1).coerceAtLeast(0)), pos)
            c.prepare()
            if (playing) c.play()
        }
    }
}
