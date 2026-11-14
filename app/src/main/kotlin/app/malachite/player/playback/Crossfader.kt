package app.malachite.player.playback

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Плавный переход": the last seconds of a song fade out while the next one fades in.
 *
 * The main player goes on to the next song as usual – the notification, the queue, the lyrics and
 * the statistics follow it. A second player, never heard on its own, has the end of the old song
 * ready a few seconds before; it starts silently just before the fade (so that its sound is there
 * in time), takes over the old song at the moment the main player moves on, and fades it away.
 * Anything the user does in between (a pause, a seek, another song) ends the fade at once.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val handler: Handler,
    /** How long the fade is (ms of real time); 0: off. */
    private val fadeMs: () -> Int,
    /** Where the main player goes after this song; [C.INDEX_UNSET]: nowhere (the end, "stop after this song"). */
    private val nextIndex: () -> Int,
    /** The volume the playing song should have (equal loudness), before the fade is applied. */
    private val levelVolume: () -> Float,
) {
    private var tail: ExoPlayer? = null
    /** The song (and its length) the tail player is ready for. */
    private var readyFor: String? = null
    private var started = false
    private var fading = false
    private var fadeStart = 0L
    private var fadeLength = 1L
    private var tailFrom = 1f
    /** A song whose end could not be made ready in time: it ends as usual. */
    private var skippedFor: String? = null

    /** True while this class itself moves the main player on: that is not the user's doing. */
    var switching = false
        private set

    /** The share (0…1) of its volume the main player has now: it rises during a fade. */
    fun mainFactor(): Float = if (!fading) 1f else sin(progress() * PI / 2).toFloat()

    private fun progress(): Float = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeLength).coerceIn(0f, 1f)

    private val tick = object : Runnable {
        override fun run() {
            val next = step()
            if (next > 0) handler.postDelayed(this, next)
        }
    }

    /** Called when the main player starts or stops playing, and when the setting changes. */
    fun wake() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** The user did something (paused, sought, chose a song): the old song goes silent, the new one plays fully. */
    fun interrupt() {
        if (fading) finishFade() else dropTail()
    }

    fun release() {
        handler.removeCallbacks(tick)
        fading = false
        runCatching { tail?.release() }
        tail = null
        readyFor = null
    }

    /** One look at the main player; returns when to look again (ms), 0: not until woken. */
    private fun step(): Long {
        if (fading) return fadeStep()
        val ms = fadeMs()
        if (ms <= 0) { release(); return 0 }
        if (!main.playWhenReady) { dropTail(); return 0 }
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return 1000
        val item = main.currentMediaItem ?: return 1000
        val next = nextIndex()
        if (next == C.INDEX_UNSET || !isFile(item)) { dropTail(); return 1000 }
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration <= 0) return 500
        val speed = main.playbackParameters.speed.coerceAtLeast(0.1f)
        // the fade in song time; a short song is not faded over more than its third
        val fadeSong = min((ms * speed).toLong(), duration / 3)
        if (fadeSong < 500) return 1000
        val pos = main.currentPosition
        val at = duration - fadeSong
        val key = item.mediaId + "|" + duration + "|" + at
        if (key == skippedFor) return 1000
        val ahead = ((at - pos) / speed).toLong()
        if (ahead > PREPARE_AHEAD_MS) { if (readyFor != null && readyFor != key) dropTail(); return min(ahead - PREPARE_AHEAD_MS + 50, 2000L) }
        // already past the start of the fade (a seek near the end, a slow card): this song ends as usual
        if (readyFor != key && ahead < -LATE_MS) { skippedFor = key; return 1000 }
        if (readyFor != key) prepareTail(item, at, key)
        val t = tail ?: return 200
        if (t.playbackState != Player.STATE_READY) {
            if (ahead > -LATE_MS) return 30
            dropTail()
            skippedFor = key
            return 1000
        }
        // the tail's own sound needs a moment to come out: it starts silently a little earlier
        if (!started && ahead <= START_EARLY_MS) {
            t.volume = 0f
            t.playbackParameters = main.playbackParameters
            t.play()
            started = true
        }
        if (started && pos >= at) {
            beginFade(next, (fadeSong / speed).toLong())
            return FADE_STEP_MS
        }
        return if (ahead > 300) 100 else 10
    }

    private fun prepareTail(item: MediaItem, at: Long, key: String) {
        val t = tail ?: ExoPlayer.Builder(context)
            // the main player has the audio focus; the tail only finishes what it was playing
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setUsePlatformDiagnostics(false)
            .build()
            .also { tail = it }
        t.stop()
        t.setMediaItem(item, at)
        t.volume = 0f
        t.playWhenReady = false
        t.prepare()
        readyFor = key
        started = false
    }

    private fun beginFade(next: Int, length: Long) {
        val t = tail ?: return
        tailFrom = main.volume.takeIf { it > 0f } ?: levelVolume()
        t.volume = tailFrom
        fading = true
        fadeStart = SystemClock.uptimeMillis()
        fadeLength = length.coerceAtLeast(300)
        switching = true
        try {
            main.seekTo(next, 0)
        } finally {
            switching = false
        }
        main.volume = levelVolume() * mainFactor()
    }

    private fun fadeStep(): Long {
        val p = progress()
        main.volume = levelVolume() * sin(p * PI / 2).toFloat()
        tail?.volume = tailFrom * cos(p * PI / 2).toFloat()
        if (p >= 1f) {
            finishFade()
            return 500
        }
        return FADE_STEP_MS
    }

    private fun finishFade() {
        fading = false
        dropTail()
        main.volume = levelVolume()
        wake()
    }

    private fun dropTail() {
        started = false
        readyFor = null
        tail?.let { runCatching { it.stop(); it.clearMediaItems() } }
    }

    /** Only songs on the phone: an online stream could not start in time. */
    private fun isFile(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme ?: return false
        return scheme == "file" || scheme == "content"
    }

    private companion object {
        const val PREPARE_AHEAD_MS = 5000L
        const val START_EARLY_MS = 120L
        const val LATE_MS = 250L
        const val FADE_STEP_MS = 30L
    }
}
