package app.malachite.player.playback

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import app.malachite.core.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.malachite.core.library.TrackInfo
import app.malachite.player.MainActivity
import app.malachite.player.MalachiteApp
import app.malachite.player.R
import app.malachite.player.widget.WidgetUpdater
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.math.pow

/**
 * Background playback: notification, lock screen, headset / Bluetooth / car buttons,
 * audio focus, "becoming noisy" (headphones unplugged) – all via the standard Media3 session,
 * which works the same on every Android phone.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    lateinit var player: ExoPlayer
        private set
    private val handler = Handler(Looper.getMainLooper())

    private val graph get() = MalachiteApp.graph

    /** The next song in the playing order that is not marked "Не нравится"; -1 when there is none. */
    private fun nextLiked(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return -1
        var i = player.currentMediaItemIndex
        repeat(timeline.windowCount) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_ALL, player.shuffleModeEnabled)
            if (i == androidx.media3.common.C.INDEX_UNSET) return -1
            if (!graph.library.isDisliked(player.getMediaItemAt(i).mediaId)) return i
        }
        return -1
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        graph.openLibraryIfPossible()
        player = buildPlayer()
        crossfader = Crossfader(this, player, handler, { graph.settings.value.crossfadeSec * 1000 }, ::upcomingIndex) { levelVolume }
        val s = graph.settings.value
        player.skipSilenceEnabled = s.skipSilence
        player.playbackParameters = params(s.speed, s.pitchSemitones)

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The notification's "next"/"previous" never vanish: with more than one song the buttons
        // stay, and at the ends of the queue they wrap round (last → first, first → last). Only a
        // button press wraps; a song ending on its own still stops at the end unless repeat is on.
        session = MediaSession.Builder(this, WrapAroundPlayer(player))
            .setSessionActivity(activityIntent)
            .setCallback(SessionCallback())
            .setCustomLayout(modeButtons())
            .setBitmapLoader(artworkLoader())
            .build()
        setMediaNotificationProvider(CoverColorNotificationProvider(this) { graph.settings.value.notificationCoverColors })
        // the detailed log: what the player did, one line for each batch of its events
        player.addListener(object : Player.Listener {
            // volume steps (a fade: one every 30 ms) and the buffer filling up are counted, not
            // written one by one: they were nine lines in ten of the log
            private var volumeSteps = 0
            private var loads = 0
            private var quietSince = 0L

            override fun onEvents(p: Player, events: Player.Events) {
                if (!app.malachite.player.data.FrameWatch.isOn) return
                val quiet = (0 until events.size()).all { events.get(it) == Player.EVENT_VOLUME_CHANGED || events.get(it) == Player.EVENT_IS_LOADING_CHANGED }
                if (quiet) {
                    if (volumeSteps == 0 && loads == 0) quietSince = android.os.SystemClock.uptimeMillis()
                    if (events.contains(Player.EVENT_VOLUME_CHANGED)) volumeSteps++
                    if (events.contains(Player.EVENT_IS_LOADING_CHANGED)) loads++
                    return
                }
                if (volumeSteps > 0 || loads > 0) {
                    app.malachite.player.data.AppLog.i(
                        "player",
                        "до этого за ${android.os.SystemClock.uptimeMillis() - quietSince} мс: громкость менялась $volumeSteps раз, подгрузка файла $loads раз, громкость сейчас ${"%.2f".format(p.volume)}",
                    )
                    volumeSteps = 0
                    loads = 0
                }
                val names = (0 until events.size()).joinToString(", ") { eventName(events.get(it)) }
                app.malachite.player.data.AppLog.i(
                    "player",
                    "события: $names; песня ${p.currentMediaItemIndex + 1} из ${p.mediaItemCount} «${p.mediaMetadata.title ?: ""}», " +
                        (if (p.isPlaying) "играет" else "не играет") + ", состояние ${p.playbackState}",
                )
            }
        })

        // switching "equal loudness" on or off applies at once, not from the next song
        levelWatch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
            graph.settings.state.map { it.levelLoudness }.distinctUntilChanged().collect { applyLoudness(player.currentMediaItem?.mediaId) }
        }
        levelWatch?.let { job ->
            kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main).launch {
                graph.settings.state.map { it.crossfadeSec }.distinctUntilChanged().collect { crossfader?.wake() }
            }
            // the mixer's knobs as saved (while a knob is turned, its screen sets them directly)
            kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main).launch {
                graph.settings.state.map { it.mixerOn to it.mixer }.distinctUntilChanged().collect { (on, knobs) ->
                    Mixer.on = on
                    Mixer.knobs = knobs
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // another song chosen by the user while the old one was fading: it goes silent at once
                if (crossfader?.switching != true && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) crossfader?.interrupt()
                crossfader?.wake()
                val path = mediaItem?.mediaId ?: return
                // in shuffle, songs the user does not like are passed over when one song follows another by itself
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.shuffleModeEnabled && graph.library.isDisliked(path)) {
                    val next = nextLiked()
                    if (next >= 0 && next != player.currentMediaItemIndex) {
                        handler.post { runCatching { player.seekTo(next, 0) } }
                        return
                    }
                }
                graph.library.markPlayed(path)
                graph.library.onlineItem(path)?.let { item -> background.execute { OnlineCache.heard(this@PlaybackService, item) } }
                applyPerSongSpeed(path)
                applyLoudness(path)
                saveQueueSoon()
                WidgetUpdater.requestUpdate(this@PlaybackService)
                // After an error the player is idle, and an idle player ignores "next" and "previous":
                // the song changed on the screen (or not even that) and nothing played.
                if (player.playbackState == Player.STATE_IDLE && player.playerError != null) player.prepare()
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                Spectrum.speed = playbackParameters.speed
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) failuresInARow = 0
                playStateChanged()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // a pause ends a fade: the old song does not go on alone
                if (!playWhenReady) crossfader?.interrupt()
                crossfader?.wake()
                playStateChanged()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = playStateChanged()

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (crossfader?.switching != true) {
                    crossfader?.interrupt()
                    crossfader?.wake()
                }
                // the same for "to the start of this song" (what "previous" does after the first seconds)
                if (player.playbackState == Player.STATE_IDLE && player.playerError != null) player.prepare()
                // the widget's lyric line follows the seek at once, not when its next line was due
                if (shownPlaying == true) startTicker() else WidgetUpdater.requestUpdate(this@PlaybackService)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // listening time for the statistics: counted while music really plays
                if (isPlaying) {
                    listenFrom = android.os.SystemClock.elapsedRealtime()
                    handler.removeCallbacks(statsTick)
                    handler.postDelayed(statsTick, STATS_EVERY_MS)
                } else {
                    flushListening()
                    handler.removeCallbacks(statsTick)
                }
                saveQueueSoon()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEffects.attach(this@PlaybackService, audioSessionId)
                runCatching { enhancer?.release() }
                enhancer = null
                applyLoudness(player.currentMediaItem?.mediaId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val broken = player.currentMediaItem
                broken?.mediaId?.let { PlaybackFailures.add(it) }
                app.malachite.player.data.AppLog.e("player", "Ошибка воспроизведения «${broken?.mediaId}»: ${error.errorCodeName}", error)
                // A file that cannot be played (moved, damaged, not really audio) must not stop the
                // whole queue: the player goes on to the next song. A queue of broken files is not
                // run through to the end, though.
                failuresInARow++
                val name = broken?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: broken?.mediaId?.substringAfterLast('/').orEmpty()
                handler.post {
                    if (failuresInARow <= MAX_FAILURES_IN_A_ROW && player.hasNextMediaItem()) {
                        runCatching { android.widget.Toast.makeText(this@PlaybackService, tr("Не воспроизводится, пропущена: $name"), android.widget.Toast.LENGTH_SHORT).show() }
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    } else {
                        runCatching { android.widget.Toast.makeText(this@PlaybackService, tr("Не воспроизводится: $name"), android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
            }

            // the buttons in the notification show the current mode
            override fun onRepeatModeChanged(repeatMode: Int) { session?.setCustomLayout(modeButtons()); saveQueueSoon() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { session?.setCustomLayout(modeButtons()); saveQueueSoon() }
        })
        AudioEffects.attach(this, player.audioSessionId)
        instance = this
        restoreQueueIfEmpty()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            saveQueue(now = true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        background.shutdown()
        crossfader?.release()
        crossfader = null
        levelWatch?.cancel()
        runCatching { enhancer?.release() }
        enhancer = null
        flushListening()
        handler.removeCallbacks(statsTick)
        handler.removeCallbacks(saveRunnable)
        saveQueue(now = true)
        stopTicker()
        handler.removeCallbacks(notifyLater)
        waitingSession = null
        instance = null
        AudioEffects.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        WidgetUpdater.requestUpdate(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ queue persistence

    private val saveRunnable = Runnable { saveQueue() }

    private fun saveQueueSoon() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 1500)
    }

    /** [now]: written before returning (the service is going away); otherwise on the queue thread. */
    fun saveQueue(now: Boolean = false) {
        if (!::player.isInitialized || player.mediaItemCount == 0 || restoring) return
        // songs from the internet are not kept for the next start: they are not files of the library
        val paths = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            .filterNot { graph.library.isOnline(it) }
        if (paths.isEmpty()) return
        val state = QueueStore.Saved(paths, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        if (now) QueueStore.save(this, state) else QueueStore.saveSoon(this, state)
    }

    /** True while the last queue is being read back (nothing is saved over it meanwhile). */
    @Volatile private var restoring = false

    /**
     * The last queue comes back in the background: checking hundreds of files on the main
     * thread held the start of the app for a moment on a slow phone.
     */
    private fun restoreQueueIfEmpty() {
        if (player.mediaItemCount > 0 || !graph.settings.value.resumeLastSession) return
        restoring = true
        QueueStore.io.execute {
            val saved = QueueStore.load(this)
            val items = saved?.paths?.mapNotNull { runCatching { mediaItemFor(it) }.getOrNull() }.orEmpty()
            handler.post {
                restoring = false
                // something was started in the meantime (a button on the headphones, a tap in the app)
                if (saved == null || items.isEmpty() || instance !== this || player.mediaItemCount > 0) return@post
                player.setMediaItems(items, saved.index.coerceIn(0, items.size - 1), saved.positionMs)
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeatMode
                player.prepare()
            }
        }
    }

    // ------------------------------------------------------------------ speed / pitch

    private fun applyPerSongSpeed(path: String) {
        val s = graph.settings.value
        if (!s.rememberSpeedPerSong) return
        val u = graph.library.data.value.user(path)
        val speed = u.speed ?: s.speed
        val pitch = u.pitchSemitones ?: s.pitchSemitones
        player.playbackParameters = params(speed, pitch)
    }

    /**
     * The player made with settings for songs on the phone: after a seek or at a new song it plays
     * as soon as half a second of sound is loaded (the standard waits for 2.5 s, made for video
     * over the internet). Every seek was a visible pause: the button, the notification and the
     * widget showed "loading" for a moment.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this, sounding())
        // the standard bandwidth meter asks the phone's mobile network for its country at once,
        // a third of a second of the main thread at every start; songs here are files
        .setBandwidthMeter(QuietBandwidthMeter)
        // songs from the internet that were heard are kept for playing without it (files of the main folder are read as they are)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(OnlineCache.dataSourceFactory(this)))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // the player tells the system about every state change for its statistics: a call into
        // another process from the main thread each time, which the log showed taking seconds
        .setUsePlatformDiagnostics(false)
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    BUFFER_TO_START_MS,
                    BUFFER_AFTER_STALL_MS,
                )
                .build(),
        )
        .build()

    /** The usual renderers with the DJ mixer in front of the speaker; the mixed sound also feeds the visualizer ([Spectrum], no microphone). */
    private fun sounding(): androidx.media3.exoplayer.RenderersFactory = Mixer.renderers(this, withSpectrum = true)

    /** Play / pause as the buttons show it (see [meantToPlay]); null before the first change. */
    private var shownPlaying: Boolean? = null

    /**
     * The widget and its lyric clock follow "meant to play", not the short loading pauses of a
     * seek: those redrew every widget twice per seek and stopped its lyrics for a moment.
     */
    private fun playStateChanged() {
        val playing = player.meantToPlay()
        if (playing == shownPlaying) return
        shownPlaying = playing
        WidgetUpdater.requestUpdate(this)
        if (playing) startTicker() else stopTicker()
    }

    // ------------------------------------------------------------------ widget ticker

    private val power by lazy { getSystemService(POWER_SERVICE) as android.os.PowerManager }

    private val ticker = object : Runnable {
        override fun run() {
            // no widget on any home screen (the list kept by WidgetUpdater says so): nothing to wake for
            if (!WidgetUpdater.hasWidgets(this@PlaybackService)) return
            // the widget asks to be woken exactly when the next lyric line (or word) starts;
            // with the screen off nobody sees it, so nothing is drawn
            // nor while the player itself is on the screen: the widget is hidden behind it, and
            // drawing its lines several times a second only took time from the app (it catches up
            // the moment the app is left)
            val appOnScreen = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            val wait = if (power.isInteractive && !appOnScreen) WidgetUpdater.tick(this@PlaybackService) else 1000L
            handler.postDelayed(this, wait)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        if (WidgetUpdater.hasWidgets(this)) handler.post(ticker)
    }

    private fun stopTicker() = handler.removeCallbacks(ticker)

    /** Widgets came or went (main thread): while music plays the widget's lyric clock starts again. */
    fun widgetsChanged() {
        if (shownPlaying == true) startTicker()
    }

    // ------------------------------------------------------------------ the notification, at most 4 times a second

    /*
     * Media3 renews the notification at each batch of the player's events, and one switch of songs
     * brings several within a fraction of a second (the song, its tags, loading, playing). Each
     * renewal is a call into the system made on the main thread, and pressing "next" a few times
     * in a row made a dozen of them. The first one goes at once; those that follow within 250 ms
     * wait, and only the newest of them is shown when the time is up. A move into the foreground or
     * out of it always goes at once: Android wants that right away.
     */
    private var notifiedAt = -NOTIFY_EVERY_MS
    private var notifiedForeground: Boolean? = null
    private var waitingSession: MediaSession? = null
    private var waitingForeground = false
    private var merged = 0
    private val notifyLater = Runnable {
        val s = waitingSession ?: return@Runnable
        waitingSession = null
        if (app.malachite.player.data.FrameWatch.isOn) {
            app.malachite.player.data.AppLog.i("player", "уведомление обновлено один раз вместо ${merged + 1}")
        }
        merged = 0
        runCatching { notifyNow(s, waitingForeground) }
            .onFailure { app.malachite.player.data.AppLog.e("player", "Уведомление не обновилось", it) }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (startInForegroundRequired != notifiedForeground || now - notifiedAt >= NOTIFY_EVERY_MS) {
            handler.removeCallbacks(notifyLater)
            waitingSession = null
            merged = 0
            notifyNow(session, startInForegroundRequired)
            return
        }
        if (waitingSession != null) merged++
        waitingSession = session
        waitingForeground = startInForegroundRequired
        handler.removeCallbacks(notifyLater)
        handler.postAtTime(notifyLater, notifiedAt + NOTIFY_EVERY_MS)
    }

    private fun notifyNow(session: MediaSession, foreground: Boolean) {
        notifiedAt = android.os.SystemClock.uptimeMillis()
        super.onUpdateNotification(session, foreground)
        // remembered only once it went through: a start in the foreground that Android refused is
        // tried again, at once, the next time
        notifiedForeground = foreground
    }

    // ------------------------------------------------------------------ repeat / shuffle in the notification

    /**
     * Two extra buttons for the notification, lock screen and system media panel: repeat
     * (off → queue → one song) and shuffle. Systems that show five buttons put them around
     * "previous / play / next".
     */
    private fun modeButtons(): ImmutableList<CommandButton> {
        val repeat = CommandButton.Builder()
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> tr("Повтор одной песни")
                    Player.REPEAT_MODE_ALL -> tr("Повтор очереди")
                    else -> tr("Без повтора")
                },
            )
            .setIconResId(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                    Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
                    else -> R.drawable.ic_repeat_off
                },
            )
            .setSessionCommand(CMD_REPEAT)
            .build()
        val shuffle = CommandButton.Builder()
            .setDisplayName(if (player.shuffleModeEnabled) tr("Вперемешку") else tr("По порядку"))
            .setIconResId(if (player.shuffleModeEnabled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off)
            .setSessionCommand(CMD_SHUFFLE)
            .build()
        return ImmutableList.of(repeat, shuffle)
    }

    // ------------------------------------------------------------------ session callback

    private inner class SessionCallback : MediaSession.Callback {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(CMD_REPEAT).add(CMD_SHUFFLE).build()
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(modeButtons())
            if (session.isMediaNotificationController(controller)) {
                // The notification's own controller sets what Android's media session (the panel
                // in the shade, the lock screen, Bluetooth) is told. Not the queue: with it, every
                // change of the queue – also each song whose length became known – sent all the
                // songs (a thousand and more) to the system from the main thread, up to a second
                // each time, and the app stood still while songs were switched. The notification
                // itself still sees the song playing, and its buttons come from the player.
                result.setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_GET_TIMELINE)
                        .build(),
                )
            } else if (!controller.isTrusted && controller.packageName != packageName) {
                // Any app may connect. One that is neither this app nor trusted by Android (the
                // system, the notification and lock screen, a watch's notification listener) can
                // steer the playback and see the song playing, but not read the whole queue or change it.
                result.setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .removeAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .build(),
                )
            }
            return result.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Controllers send only ids (paths inside the main folder, or online songs this app
         * registered); the real address is resolved here. An address sent by another app is never
         * used: any app can connect to the player (the notification, a watch, a car), and it must
         * not be able to make it open an arbitrary file or web address.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { mediaItemFor(it.mediaId) }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        /** "Play" pressed on headphones / car / widget while the app was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = QueueStore.load(this@PlaybackService)
            val items = saved?.paths?.mapNotNull { mediaItemFor(it) }.orEmpty()
            if (saved == null || items.isEmpty()) {
                val all = graph.library.tracks.value.take(200).mapNotNull { mediaItemFor(it.path) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(all, 0, 0))
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(0, items.size - 1), saved.positionMs),
            )
        }
    }

    private var networkWake = false

    // ------------------------------------------------------------------ equal loudness

    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var levelWatch: kotlinx.coroutines.Job? = null

    /**
     * Brings the song to one level by its measured loudness (healing "Громкость"): a loud song is
     * played quieter by the player itself, a quiet one is raised by the system's loudness
     * enhancer (which also keeps peaks from clipping). Songs not measured play as they are.
     */
    /** Small file work away from the main thread (the list of heard online songs). */
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** The volume of the playing song for equal loudness (1: as it is); a fade is applied on top. */
    private var levelVolume = 1f

    /** "Плавный переход" between songs (null until the player is made). */
    private var crossfader: Crossfader? = null

    /** Where the player goes after this song: none after "stop after this song"; in shuffle, past the songs the user does not like. */
    private fun upcomingIndex(): Int {
        if (graph.player.state.value.sleepAfterTrack) return C.INDEX_UNSET
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET || !player.shuffleModeEnabled) return next
        return if (graph.library.isDisliked(player.getMediaItemAt(next).mediaId)) nextLiked().takeIf { it >= 0 } ?: next else next
    }

    private fun applyLoudness(path: String?) {
        if (!::player.isInitialized) return
        val lufs = path?.let { graph.library.data.value.user(it).loudness }
        val gain = if (graph.settings.value.levelLoudness && lufs != null) app.malachite.core.audio.LoudnessMeter.gainFor(lufs.toDouble()) else 0.0
        if (gain <= 0.0) {
            levelVolume = Math.pow(10.0, gain / 20.0).toFloat()
            player.volume = levelVolume * (crossfader?.mainFactor() ?: 1f)
            runCatching { enhancer?.enabled = false }
        } else {
            levelVolume = 1f
            player.volume = levelVolume * (crossfader?.mainFactor() ?: 1f)
            runCatching {
                val e = enhancer ?: android.media.audiofx.LoudnessEnhancer(player.audioSessionId).also { enhancer = it }
                e.setTargetGain((gain * 100).toInt())
                e.enabled = true
            }
        }
    }

    /** When the current stretch of listening began (elapsedRealtime), 0 while nothing plays. */
    private var listenFrom = 0L

    private fun flushListening() {
        if (listenFrom == 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        val ms = now - listenFrom
        listenFrom = if (::player.isInitialized && player.isPlaying) now else 0L
        if (ms in 1..3_600_000) app.malachite.player.data.Stats.addListening(ms)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            flushListening()
            if (listenFrom != 0L) handler.postDelayed(this, STATS_EVERY_MS)
        }
    }

    /** Songs that failed one after another (reset by the first one that plays). */
    private var failuresInARow = 0

    private fun mediaItemFor(path: String): MediaItem? {
        // a song from the online section: the same player, only the address is on the internet
        graph.library.onlineItem(path)?.let { item ->
            val t = graph.library.track(path) ?: return null
            // keep Wi-Fi awake with the screen off, too (local files only need the processor)
            if (!networkWake) { networkWake = true; handler.post { runCatching { player.setWakeMode(C.WAKE_MODE_NETWORK) } } }
            return buildItem(t, Uri.parse(item.streamUrl), item.coverUrl)
        }
        val root = graph.library.root ?: return null
        // only a file of the main folder: "../" in an id sent by another app leads nowhere
        val f = app.malachite.core.fs.PathGuard.child(root, path) ?: return null
        if (!f.isFile) return null
        val t = graph.library.track(path) ?: TrackInfo(path = path)
        return buildItem(t, Uri.fromFile(f))
    }

    /**
     * Pictures for Android's media session and the notification, at most 512 px on a side, read
     * on a thread of their own. The cover embedded in a file (often 1500–3000 px) went to the
     * system as it was: Android scaled it down and copied it on the main thread, up to half a
     * second at each song.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun artworkLoader(): androidx.media3.common.util.BitmapLoader =
        androidx.media3.session.CacheBitmapLoader(TimedBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader(
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
                    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                        Thread(r, "malachite-artwork").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
                    },
                ),
                androidx.media3.datasource.DefaultDataSource.Factory(this),
                null,
                ARTWORK_MAX_PX,
            ),
        ))

    companion object {
        private const val ARTWORK_MAX_PX = 512
        private const val MAX_FAILURES_IN_A_ROW = 12
        private const val BUFFER_TO_START_MS = 500
        private const val BUFFER_AFTER_STALL_MS = 2000
        private const val STATS_EVERY_MS = 30_000L
        /** The notification is renewed at most this often (the first renewal always at once). */
        private const val NOTIFY_EVERY_MS = 250L

        @Volatile
        var instance: PlaybackService? = null
            private set

        private const val ACTION_REPEAT = "app.malachite.player.REPEAT"
        private const val ACTION_SHUFFLE = "app.malachite.player.SHUFFLE"
        private val CMD_REPEAT = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
        private val CMD_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)

        fun params(speed: Float, semitones: Float) =
            PlaybackParameters(speed.coerceIn(0.25f, 3f), 2.0.pow(semitones / 12.0).toFloat().coerceIn(0.25f, 4f))

        fun buildItem(t: TrackInfo, uri: Uri?, artworkUrl: String? = null): MediaItem {
            val b = MediaItem.Builder()
                .setMediaId(t.path)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.displayTitle)
                        .setArtist(t.displayArtist)
                        .setAlbumTitle(t.album)
                        .setAlbumArtist(t.albumArtist)
                        .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                )
            if (uri != null) b.setUri(uri)
            return b.build()
        }
    }
}

/**
 * Keeps "previous" and "next" always offered (when the queue has more than one song) and makes
 * them wrap at the ends, so the system notification never drops the button on the last song.
 * Automatic advance at the end of a song is untouched: it does not go through these methods.
 */
private class WrapAroundPlayer(base: Player) : ForwardingPlayer(base) {
    private val wrapCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        .build()

    private fun many() = mediaItemCount > 1

    // The session (and through it the notification, the lock screen and the app's own buttons)
    // learns the commands from the "commands changed" event, not by asking. That event, passed
    // on from the player underneath, carried its commands, without "next" on the last song: the
    // button vanished and "next" did nothing. Every listener still hears every event as it is,
    // and right after it (this listener is always the last one of the player underneath) this
    // player's own commands, also when the queue grows past one song or shrinks to it.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()
    private var told: Player.Commands? = null

    private val corrector = object : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = correct(availableCommands)
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = correct(null)
    }

    private fun correct(heard: Player.Commands?) {
        val ours = availableCommands
        if (heard != null && heard == ours) { told = ours; return }
        if (heard == null && ours == told) return
        told = ours
        for (l in listeners) l.onAvailableCommandsChanged(ours)
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
        // heard after the player's own event, so the last word is this player's
        wrappedPlayer.removeListener(corrector)
        wrappedPlayer.addListener(corrector)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return if (many()) base.buildUpon().addAll(wrapCommands).build() else base
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (many() && wrapCommands.contains(command)) || super.isCommandAvailable(command)

    override fun seekToNext() { if (hasNextMediaItem()) super.seekToNext() else if (many()) seekToDefaultPosition(0) }

    override fun seekToNextMediaItem() {
        if (hasNextMediaItem()) super.seekToNextMediaItem() else if (many()) seekToDefaultPosition(0)
    }

    override fun seekToPrevious() {
        // within a song go to its start (as Media3 does); at the very first song wrap to the last
        if (currentPosition > maxSeekToPreviousPosition || hasPreviousMediaItem()) super.seekToPrevious()
        else if (many()) seekToDefaultPosition(mediaItemCount - 1)
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) super.seekToPreviousMediaItem() else if (many()) seekToDefaultPosition(mediaItemCount - 1)
    }
}

/**
 * The standard Media3 notification, plus the colours of the cover where the system can show
 * them (Android 8–10 and some vendor skins; newer Android colours its media panel from the
 * cover by itself). If anything goes wrong the plain notification is used.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CoverColorNotificationProvider(
    private val context: Context,
    private val enabled: () -> Boolean,
) : MediaNotification.Provider {

    private val base = DefaultMediaNotificationProvider(context).apply { setSmallIcon(R.drawable.ic_notification) }
    private var lastCover: Bitmap? = null
    private var lastColor: Int? = null
    private var kept: KeptActions? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val started = android.os.SystemClock.uptimeMillis()
        // the buttons' intents are made once, not at every renewal (see KeptActions)
        val actions = kept?.takeIf { it.inner === actionFactory } ?: KeptActions(context, actionFactory).also { kept = it }
        val n = base.createNotification(mediaSession, customLayout, actions) { updated ->
            val again = android.os.SystemClock.uptimeMillis()
            val t = tinted(updated)
            timing("уведомление с обложкой", again, t)
            onNotificationChangedCallback.onNotificationChanged(t)
        }
        return tinted(n).also { timing("уведомление", started, it) }
    }

    /** The detailed log: how long the notification took to make, and the picture in it. */
    private fun timing(what: String, started: Long, n: MediaNotification) {
        if (!app.malachite.player.data.FrameWatch.isOn) return
        val icon = runCatching { (n.notification.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap }.getOrNull()
        app.malachite.player.data.AppLog.i(
            "notify",
            "$what собрано за ${android.os.SystemClock.uptimeMillis() - started} мс" + (icon?.let { ", картинка ${it.width}×${it.height}" } ?: ", без картинки"),
        )
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        base.handleCustomCommand(session, action, extras)

    private fun tinted(n: MediaNotification): MediaNotification {
        if (!enabled()) return n
        return runCatching {
            val color = coverColor(n.notification) ?: return n
            val b = Notification.Builder.recoverBuilder(context, n.notification)
            b.setColorized(true)
            b.setColor(color)
            MediaNotification(n.notificationId, b.build())
        }.getOrDefault(n)
    }

    /** A dark, saturated colour of the cover shown in the notification (cached per cover). */
    private fun coverColor(n: Notification): Int? {
        val bmp = (n.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap ?: return null
        if (bmp === lastCover) return lastColor
        // pixels of a hardware bitmap cannot be read directly
        val readable = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bmp
        val small = Bitmap.createScaledBitmap(readable, 48, 48, true)
        val p = androidx.palette.graphics.Palette.from(small).generate()
        val c = p.getDarkVibrantColor(p.getVibrantColor(p.getDarkMutedColor(p.getDominantColor(0))))
        lastCover = bmp
        lastColor = c.takeIf { it != 0 }
        return lastColor
    }
}

/**
 * Media3's notification buttons, their intents kept. Making a PendingIntent is a call into the
 * system on the main thread, and Media3 made one for every button (and one more for swiping the
 * notification away) at every renewal: six calls each time, several renewals at each switch of
 * songs, and every custom button got a new intent record in the system each time. What an intent
 * does depends only on its command – and for "play" on whether the player is meant to play (the
 * "play" of a paused player may start the service in the foreground) – so each is made once.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class KeptActions(private val context: Context, val inner: MediaNotification.ActionFactory) : MediaNotification.ActionFactory {
    private data class Key(val session: MediaSession, val what: Any)

    private val intents = HashMap<Key, PendingIntent>()

    private fun keep(key: Key, intent: PendingIntent) {
        if (intents.size >= MAX_KEPT) intents.clear()
        intents[key] = intent
    }

    override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Long): PendingIntent {
        val what: Any = if (command == Player.COMMAND_PLAY_PAUSE.toLong()) "play/" + mediaSession.player.playWhenReady else command
        val key = Key(mediaSession, what)
        intents[key]?.let { return it }
        return inner.createMediaActionPendingIntent(mediaSession, command).also { keep(key, it) }
    }

    override fun createMediaAction(
        mediaSession: MediaSession,
        icon: androidx.core.graphics.drawable.IconCompat,
        title: CharSequence,
        command: Int,
    ): androidx.core.app.NotificationCompat.Action =
        androidx.core.app.NotificationCompat.Action(icon, title, createMediaActionPendingIntent(mediaSession, command.toLong()))

    override fun createCustomAction(
        mediaSession: MediaSession,
        icon: androidx.core.graphics.drawable.IconCompat,
        title: CharSequence,
        customAction: String,
        extras: Bundle,
    ): androidx.core.app.NotificationCompat.Action {
        // an intent with extras is made anew each time: what is in them is not compared here
        if (!extras.isEmpty) return inner.createCustomAction(mediaSession, icon, title, customAction, extras)
        val key = Key(mediaSession, "custom/$customAction")
        intents[key]?.let { return androidx.core.app.NotificationCompat.Action(icon, title, it) }
        return inner.createCustomAction(mediaSession, icon, title, customAction, extras).also { a -> a.getActionIntent()?.let { keep(key, it) } }
    }

    override fun createCustomActionFromCustomCommandButton(
        mediaSession: MediaSession,
        customCommandButton: CommandButton,
    ): androidx.core.app.NotificationCompat.Action {
        val command = customCommandButton.sessionCommand
        if (command == null || !command.customExtras.isEmpty || customCommandButton.iconResId == 0) {
            return inner.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)
        }
        val key = Key(mediaSession, "custom/" + command.customAction)
        intents[key]?.let {
            return androidx.core.app.NotificationCompat.Action(
                androidx.core.graphics.drawable.IconCompat.createWithResource(context, customCommandButton.iconResId),
                customCommandButton.displayName,
                it,
            )
        }
        return inner.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton).also { a -> a.getActionIntent()?.let { keep(key, it) } }
    }

    private companion object {
        const val MAX_KEPT = 32
    }
}

/** A player event by name, for the detailed log. */
private fun eventName(e: Int): String = when (e) {
    Player.EVENT_TIMELINE_CHANGED -> "очередь"
    Player.EVENT_MEDIA_ITEM_TRANSITION -> "другая песня"
    Player.EVENT_TRACKS_CHANGED -> "дорожки"
    Player.EVENT_IS_LOADING_CHANGED -> "загрузка"
    Player.EVENT_PLAYBACK_STATE_CHANGED -> "состояние"
    Player.EVENT_PLAY_WHEN_READY_CHANGED -> "играть/пауза"
    Player.EVENT_IS_PLAYING_CHANGED -> "играет?"
    Player.EVENT_REPEAT_MODE_CHANGED -> "повтор"
    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED -> "перемешать"
    Player.EVENT_PLAYER_ERROR -> "ошибка"
    Player.EVENT_POSITION_DISCONTINUITY -> "скачок позиции"
    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED -> "скорость"
    Player.EVENT_AVAILABLE_COMMANDS_CHANGED -> "команды"
    Player.EVENT_MEDIA_METADATA_CHANGED -> "теги"
    Player.EVENT_PLAYLIST_METADATA_CHANGED -> "теги очереди"
    Player.EVENT_AUDIO_ATTRIBUTES_CHANGED -> "звук"
    Player.EVENT_VOLUME_CHANGED -> "громкость"
    else -> "#$e"
}

/** The artwork loader, telling the detailed log how long each picture took and how big it came out. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TimedBitmapLoader(private val inner: androidx.media3.common.util.BitmapLoader) : androidx.media3.common.util.BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = inner.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = timed("из файла, ${data.size / 1024} КБ") { inner.decodeBitmap(data) }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = timed("по адресу") { inner.loadBitmap(uri) }

    private fun timed(what: String, start: () -> ListenableFuture<Bitmap>): ListenableFuture<Bitmap> {
        val started = android.os.SystemClock.uptimeMillis()
        val future = start()
        if (app.malachite.player.data.FrameWatch.isOn) {
            future.addListener({
                val b = runCatching { Futures.getDone(future) }.getOrNull()
                app.malachite.player.data.AppLog.i(
                    "artwork",
                    "обложка $what: ${b?.let { "${it.width}×${it.height}" } ?: "не прочиталась"} за ${android.os.SystemClock.uptimeMillis() - started} мс",
                )
            }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }
        return future
    }
}

/**
 * A bandwidth meter that measures nothing: the songs are files (or one stream at a time), and the
 * standard one asked the mobile network for its country on the main thread when the player was made.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object QuietBandwidthMeter : androidx.media3.exoplayer.upstream.BandwidthMeter {
    override fun getBitrateEstimate(): Long = 1_000_000L
    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null
    override fun addEventListener(eventHandler: Handler, eventListener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) = Unit
    override fun removeEventListener(eventListener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) = Unit
}

/** Songs that failed to play in this run: "Повреждённые файлы" in healing lists them for a look. */
object PlaybackFailures {
    private val set = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    fun add(path: String) { set += path }
    fun paths(): Set<String> = synchronized(set) { set.toSet() }
}
