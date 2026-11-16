package app.malachite.player.widget

import app.malachite.core.i18n.tr
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import app.malachite.core.lyrics.Lyrics
import app.malachite.player.MainActivity
import app.malachite.player.MalachiteApp
import app.malachite.player.R
import app.malachite.player.data.AppLog
import app.malachite.player.data.FontChoice
import app.malachite.player.data.LyricLook
import app.malachite.player.data.WordFill
import app.malachite.player.data.widgetLyricLook
import app.malachite.player.data.wordFillProgress
import app.malachite.core.lyrics.LineSweep
import app.malachite.player.data.CoverLoader
import app.malachite.player.playback.PlaybackService
import app.malachite.player.playback.meantToPlay
import app.malachite.player.playback.playPause
import app.malachite.player.ui.theme.menuScheme
import app.malachite.player.ui.theme.shade
import java.util.concurrent.Executors

/**
 * One resizable widget that adapts to any size the launcher allows:
 * tiny (cover + play) → small → medium (buttons) → large (synced lyrics) → huge
 * (lyrics + seek bar you can tap anywhere to jump). Colours, transparency and which parts
 * are shown are set in the app's settings.
 */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // a widget was just put on a home screen (or the launcher asks to draw them again)
        WidgetUpdater.widgetsChanged(context)
        WidgetUpdater.requestUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        WidgetUpdater.requestUpdate(context)

    override fun onEnabled(context: Context) {
        WidgetUpdater.widgetsChanged(context)
        WidgetUpdater.requestUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetUpdater.widgetsChanged(context)

    override fun onDisabled(context: Context) = WidgetUpdater.widgetsChanged(context)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) = WidgetUpdater.widgetsChanged(context)
}

/** Handles taps on widget buttons. Runs on the main thread, talks to the player directly. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = PlaybackService.instance?.player
        when (intent.action) {
            WidgetUpdater.ACTION_PLAY_PAUSE -> {
                if (player != null && player.mediaItemCount > 0) {
                    player.playPause()
                } else {
                    // Service not running: let Media3 start it and resume the last queue.
                    val mb = Intent(Intent.ACTION_MEDIA_BUTTON)
                        .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    runCatching { MediaButtonReceiver().onReceive(context, mb) }
                }
            }
            WidgetUpdater.ACTION_NEXT -> player?.seekToNextMediaItem()
            WidgetUpdater.ACTION_PREV -> player?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() }
            WidgetUpdater.ACTION_SEEK -> player?.let { p ->
                val frac = intent.getFloatExtra(WidgetUpdater.EXTRA_FRACTION, -1f)
                if (frac >= 0 && p.duration > 0) p.seekTo((p.duration * frac).toLong())
            }
            WidgetUpdater.ACTION_FAVORITE -> player?.currentMediaItem?.mediaId?.let { MalachiteApp.graph.library.toggleFavorite(it) }
        }
        WidgetUpdater.requestUpdate(context)
    }
}

object WidgetUpdater {
    const val ACTION_PLAY_PAUSE = "app.malachite.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "app.malachite.widget.NEXT"
    const val ACTION_PREV = "app.malachite.widget.PREV"
    const val ACTION_SEEK = "app.malachite.widget.SEEK"
    const val ACTION_FAVORITE = "app.malachite.widget.FAVORITE"
    const val EXTRA_FRACTION = "fraction"
    private const val SEEK_ZONES = 20

    /** All widget work runs here, one job at a time, so the views never get mixed states. */
    private val io = Executors.newSingleThreadExecutor()

    private enum class Size { TINY, SMALL, MEDIUM, LARGE, HUGE }

    private data class Snapshot(
        val path: String?,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val favorite: Boolean,
        /** Playback speed, read on the main thread: the player must never be touched from the worker thread. */
        val speed: Float = 1f,
    )

    /** Colours of the widget, taken from the same theme as the app (or from the cover, like the player). */
    private data class Palette3(val bg: Int, val text: Int, val sub: Int, val accent: Int, val onAccent: Int)

    @Volatile private var lyricsPath: String? = null
    @Volatile private var lyrics: Lyrics? = null
    @Volatile private var coverPath: String? = null
    @Volatile private var cover: Bitmap? = null
    @Volatile private var colorsKey: Any? = null
    @Volatile private var colors: Palette3? = null

    // main thread only
    private var lastLine = -2
    private var lastWord = -1
    private var lastProgress = 0L

    // io thread only: which of the two lyric pages each widget shows
    private val shownPage = HashMap<Int, Int>()

    /** The old line fades out and the new one fades in; started this early, the new line is there when it is sung. */
    private const val LEAD_MS = 420L
    private const val WORD_GAP_MS = 110L
    /** While a word fills in smoothly the line is redrawn this often (about 7 times a second). */
    private const val FRAME_MS = 150L
    private const val FILL_STEPS = 6

    /** "Letter by letter": the edge blends over this many letters. */
    private const val SWEEP_SOFT_LETTERS = 1.6f
    /** A swept line is renewed this often in the widget (a launcher is not a place for 20 updates a second). */
    private const val SWEEP_FRAME_MS = 230L
    /** An update needs a moment to reach the screen: colours are computed for that moment. */
    private const val FILL_AHEAD_MS = 60L

    /**
     * The line the widget should show at [pos]: the next line already a little before it starts
     * (never earlier than half-way through the current line), so the transition finishes on time.
     */
    private fun lineAt(l: Lyrics, pos: Long, speed: Float, playing: Boolean): Int =
        // paused: exactly the line under the position, the same one the app shows
        if (playing) l.activeLineAhead(pos, (LEAD_MS * speed).toLong()) else l.activeLine(pos)

    /** Media time at which the widget wants to switch to the line after [line]. */
    private fun switchTime(l: Lyrics, line: Int, speed: Float): Long? =
        // the same moment the app uses: never before the last word of a word-by-word line is sung
        l.switchMs(line, (LEAD_MS * speed).toLong())?.plus(l.offsetMs)



    /**
     * The widgets on the home screens, asked from Android once and then kept. Asking is a call into
     * another process: it used to be made on the main thread at every switch of songs, and on a busy
     * phone such a call can take tens of milliseconds. [MusicWidget] says when widgets come and go.
     */
    @Volatile private var knownIds: IntArray? = null

    /** A widget was added, removed or restored: the kept list is asked for again (off the main thread). */
    fun widgetsChanged(context: Context) {
        val app = context.applicationContext
        knownIds = null
        io.execute { freshIds(app) }
        // a widget put on the home screen while music plays gets its lyric lines at once
        PlaybackService.instance?.widgetsChanged()
    }

    /**
     * Whether there is a widget to draw. Never asks Android on the calling thread: while the list is
     * not known yet the answer is "yes", and the worker thread (which asks) then finds out.
     */
    fun hasWidgets(context: Context): Boolean {
        val known = knownIds
        if (known != null) return known.isNotEmpty()
        val app = context.applicationContext
        io.execute { ids(app) }
        return true
    }

    /** Worker thread only: the kept list, asked for when not known. */
    private fun ids(context: Context): IntArray = knownIds ?: freshIds(context)

    /** Worker thread only: the list asked from Android now, and kept. */
    private fun freshIds(context: Context): IntArray {
        val fresh = runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MusicWidget::class.java)) }.getOrNull()
        if (fresh != null) knownIds = fresh
        return fresh ?: IntArray(0)
    }

    private fun snapshot(): Snapshot {
        val p = PlaybackService.instance?.player
        val graph = MalachiteApp.graph
        if (p == null || p.currentMediaItem == null) {
            return Snapshot(null, "Malachite", tr("Нажмите ▶, чтобы продолжить"), false, 0, 0, false)
        }
        val path = p.currentMediaItem?.mediaId
        val t = path?.let { graph.library.track(it) }
        return Snapshot(
            path,
            t?.displayTitle ?: p.mediaMetadata.title?.toString().orEmpty(),
            t?.displayArtist ?: p.mediaMetadata.artist?.toString().orEmpty(),
            p.meantToPlay(),
            p.currentPosition,
            p.duration.coerceAtLeast(0),
            path?.let { graph.library.data.value.user(it).favorite } ?: false,
            p.playbackParameters.speed.takeIf { it > 0f } ?: 1f,
        )
    }

    /** Full update of all widgets (track changed, play/pause, settings changed). Call on the main thread. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        if (!hasWidgets(app)) return
        val snap = snapshot()
        // the full update shows the current line already: the next tick must not flip it again
        lastLine = lyrics.takeIf { lyricsPath == snap.path && it?.synced == true }?.let { lineAt(it, snap.position, snap.speed, snap.playing) } ?: -1
        lastWord = -1
        io.execute {
            // asked again here, on the worker thread: the kept list corrects itself at every full update
            val all = freshIds(app)
            if (all.isEmpty()) return@execute
            prepareAssets(app, snap.path)
            val manager = AppWidgetManager.getInstance(app)
            for (id in all) {
                runCatching { manager.updateAppWidget(id, build(app, manager, id, snap)) }
                    .onFailure { AppLog.e("widget", "Виджет $id не обновился", it) }
            }
        }
    }

    /**
     * Called by the player while it plays. Updates only what changed (the lyric lines, the seek
     * bar) and returns after how many ms it wants to be called again: exactly when the next
     * line starts, so lines change on time instead of up to half a second late.
     */
    fun tick(context: Context): Long {
        val app = context.applicationContext
        val snap = snapshot()
        val l = lyrics.takeIf { lyricsPath == snap.path && it?.synced == true }
        val speed = snap.speed
        val line = if (l != null) lineAt(l, snap.position, speed, snap.playing) else -1
        val now = SystemClock.elapsedRealtime()
        val lineChanged = line != lastLine
        val progressDue = now - lastProgress >= 1000
        // the words of the line being sung take the "sung" colour one after another, like in the player
        val settings = MalachiteApp.graph.settings.value
        val fillMode = widgetFill(settings.widgetLyricLook())
        // "letter by letter": the whole line is swept at an even pace, words timed or not
        val sweeping = l != null && line >= 0 && settings.widget.wordFill && fillMode == WordFill.SWEEP
        val words = if (l != null && line >= 0 && settings.widget.wordFill && !sweeping) l.lines[line].words else emptyList()
        var fillKey = 0
        var filling = false
        var nextWord: Long? = null
        if (sweeping && l != null) {
            val ln = l.lines[line]
            val shown = sweepText(ln)
            val reach = LineSweep.reach(
                shown, ln, snap.position - l.offsetMs + FILL_AHEAD_MS, l.lines.getOrNull(line + 1)?.startMs, l.switchMs(line, (LEAD_MS * snap.speed).toLong()),
            )
            fillKey = (reach * 2).toInt()
            filling = reach > 0f && reach < shown.length
        }
        if (l != null && words.isNotEmpty()) {
            val mode = fillMode
            val p = snap.position - l.offsetMs + FILL_AHEAD_MS
            val nextLine = l.lines.getOrNull(line + 1)?.startMs
            words.forEachIndexed { i, w ->
                val f = wordFillProgress(l.lines[line], i, p, nextLine, mode, l.switchMs(line, (LEAD_MS * snap.speed).toLong()))
                fillKey = fillKey * (FILL_STEPS + 1) + (f * FILL_STEPS).toInt()
                if (f > 0f && f < 1f) filling = true
                if (nextWord == null && w.startMs > p) nextWord = w.startMs + l.offsetMs - FILL_AHEAD_MS
            }
        }
        val wordChanged = !lineChanged && (words.isNotEmpty() || sweeping) && fillKey != lastWord
        if (lineChanged || progressDue || wordChanged) {
            lastLine = line
            lastWord = fillKey
            if (progressDue) lastProgress = now
            io.execute { partial(app, snap, l, line, lineChanged, progressDue, wordChanged) }
        }
        var wait = 1000L
        val nextSwitch = if (l != null) switchTime(l, line, speed) else null
        if (nextSwitch != null) wait = minOf(wait, ((nextSwitch - snap.position) / speed).toLong() + 10)
        // words closer together than this are coloured in one go: a launcher is not a place for 20 updates a second
        nextWord?.let { wait = minOf(wait, (((it - snap.position) / speed).toLong() + 5).coerceAtLeast(WORD_GAP_MS)) }
        // a word that fills in smoothly gets a few steps while it is sung
        if (filling) wait = minOf(wait, if (sweeping) SWEEP_FRAME_MS else FRAME_MS)
        return wait.coerceIn(60L, 1000L)
    }

    private fun partial(context: Context, s: Snapshot, l: Lyrics?, line: Int, lineChanged: Boolean, progress: Boolean, wordChanged: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val settings = MalachiteApp.graph.settings.value
        val ws = settings.widget
        val look = settings.widgetLyricLook()
        val c = colors ?: return
        for (id in ids(context)) {
            val size = sizeOf(manager, id)
            if (size != Size.LARGE && size != Size.HUGE) continue
            val rv = RemoteViews(context.packageName, layoutOf(size))
            var any = false
            if ((lineChanged || wordChanged) && ws.showLyrics && l != null && l.lines.isNotEmpty()) {
                val (wDp, hDp) = dimsOf(manager, id)
                val m = lyricMetrics(context, size, wDp, hDp, look, ws, coverDpFor(size, wDp, hDp))
                val pos = s.position - l.offsetMs + FILL_AHEAD_MS
                val shown = shownPage[id] ?: 0
                when {
                    // The new lines go into the hidden page, then the pages swap: the old one fades out and
                    // the new one fades in. "Show page N" is harmless when repeated, unlike a flipper.
                    lineChanged && look.fade -> {
                        val page = 1 - shown
                        fillLyrics(context, rv, page, l, line, c, pos, m, look)
                        rv.setViewVisibility(PAGE_IDS[page], View.VISIBLE)
                        rv.setViewVisibility(PAGE_IDS[shown], View.INVISIBLE)
                        shownPage[id] = page
                    }
                    // animation switched off (as in the player): the lines change in place
                    lineChanged -> fillLyrics(context, rv, shown, l, line, c, pos, m, look)
                    // only the line being sung changes: one more word takes the "sung" colour
                    else -> fillLyrics(context, rv, shown, l, line, c, pos, m, look, onlyCurrent = true)
                }
                any = true
            }
            if (progress && size == Size.HUGE && ws.showSeekBar) {
                setProgress(rv, s)
                any = true
            }
            if (any) runCatching { manager.partiallyUpdateAppWidget(id, rv) }.onFailure { AppLog.e("widget", "Виджет $id: строка не обновилась", it) }
        }
    }

    private fun prepareAssets(context: Context, path: String?) {
        val graph = MalachiteApp.graph
        if (path != lyricsPath) {
            lyricsPath = path
            lyrics = path?.let { p -> graph.library.track(p)?.let { graph.library.loadLyrics(it) } }
        }
        if (path != coverPath) {
            coverPath = path
            val onlineCover = graph.library.onlineItem(path)?.coverUrl
            val f = path?.let { graph.library.file(it) }
            cover = if (onlineCover != null) CoverLoader.loadUrl(onlineCover, 256) else f?.let { CoverLoader.load(it, 256) }
        }
        val settings = graph.settings.value
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val key = listOf(coverPath, cover != null, dark, settings.themeMode, settings.accent, settings.customAccent,
            settings.customBackground, settings.customSurface, settings.widget)
        if (key != colorsKey || colors == null) {
            colors = computeColors(settings, dark)
            colorsKey = key
        }
    }

    private fun computeColors(settings: app.malachite.player.data.AppSettings, dark: Boolean): Palette3 {
        val ws = settings.widget
        val shade = settings.shade(dark)
        val scheme = (if (ws.useCoverColors) cover?.let { pic ->
            val p = coverPath ?: return@let null
            val modified = MalachiteApp.graph.library.track(p)?.lastModified ?: 0L
            app.malachite.player.ui.theme.CoverColors.compute(app.malachite.player.data.coverKey(p, modified), pic, shade)
        } else null) ?: settings.menuScheme(dark)
        val custom = ws.backgroundColor?.takeIf { !ws.useCoverColors }?.toInt()
        if (custom != null) {
            // own background colour: text colour follows its brightness
            val light = ColorUtils.calculateLuminance(custom) > 0.5
            val text = if (light) 0xFF15161A.toInt() else Color.WHITE
            return Palette3(custom, text, ColorUtils.setAlphaComponent(text, 0xB3), scheme.primary.toArgb(), scheme.onPrimary.toArgb())
        }
        return Palette3(
            bg = scheme.surfaceContainer.toArgb(),
            text = scheme.onSurface.toArgb(),
            sub = scheme.onSurfaceVariant.toArgb(),
            accent = scheme.primary.toArgb(),
            onAccent = scheme.onPrimary.toArgb(),
        )
    }

    /**
     * The layout for the place the widget has. The thresholds are what each layout needs at
     * its smallest, so a layout is never put into a place it does not fit (that cut the cover
     * and the play button off at the top and bottom).
     */
    private fun sizeOf(manager: AppWidgetManager, id: Int): Size {
        val (w, h) = dimsOf(manager, id)
        return when {
            h < 118 && w < 170 -> Size.TINY
            h < 118 -> Size.SMALL      // one row
            h < 200 -> Size.MEDIUM     // song + buttons
            h < 310 -> Size.LARGE      // + lyric lines
            else -> Size.HUGE          // + seek bar
        }
    }

    /**
     * Width and height of the widget in dp, as the launcher reports them. Every launcher that
     * was examined (HyperOS, One UI, Pixel, ColorOS, vivo, Honor, Huawei, ASUS, Motorola, Sony,
     * Transsion…) uses the standard options: the narrow width with the tall height describe
     * the portrait placement, the wide width with the low height the landscape one.
     */
    private fun dimsOf(manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = manager.getAppWidgetOptions(id)
        val landscape = MalachiteApp.instance.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return (w.takeIf { it > 0 } ?: 250) to (h.takeIf { it > 0 } ?: 110)
    }

    private fun layoutOf(size: Size) = when (size) {
        Size.TINY -> R.layout.widget_tiny
        Size.SMALL -> R.layout.widget_small
        Size.MEDIUM -> R.layout.widget_medium
        Size.LARGE -> R.layout.widget_large
        Size.HUGE -> R.layout.widget_huge
    }

    private fun pi(context: Context, action: String, code: Int, fraction: Float = -1f): PendingIntent {
        val i = Intent(context, WidgetActionReceiver::class.java).setAction(action)
        if (fraction >= 0) i.putExtra(EXTRA_FRACTION, fraction)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Rounded corners like the covers inside the app (RemoteViews cannot clip by themselves). */
    private fun rounded(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height).coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply { setTranslate(-(src.width - size) / 2f, -(src.height - size) / 2f) })
        paint.shader = shader
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.16f, size * 0.16f, paint)
        return out
    }

    /** A note on a soft square in the theme colours, for songs without a cover. */
    private fun placeholder(context: Context, c: Palette3): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.blendARGB(c.bg, c.accent, 0.22f) }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.16f, size * 0.16f, paint)
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification)?.mutate()?.let { d ->
            d.setTint(c.accent)
            d.setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
            d.draw(canvas)
        }
        return out
    }

    @Volatile private var roundedFor: Bitmap? = null
    @Volatile private var roundedCover: Bitmap? = null

    private fun build(context: Context, manager: AppWidgetManager, id: Int, s: Snapshot): RemoteViews {
        val settings = MalachiteApp.graph.settings.value
        val ws = settings.widget
        val c = colors ?: computeColors(settings, true)
        val size = sizeOf(manager, id)
        val rv = RemoteViews(context.packageName, layoutOf(size))
        val (wDp, hDp) = dimsOf(manager, id)
        val big = size == Size.LARGE || size == Size.HUGE
        // cover and text grow with the widget instead of staying tiny in a big widget
        val coverDp = coverDpFor(size, wDp, hDp)
        if (size != Size.TINY && size != Size.SMALL) {
            // A narrow widget has no room for five buttons: the heart goes first, then "previous".
            rv.setViewVisibility(R.id.widget_fav, if (wDp < 230) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_fav_balance, if (wDp < 230) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_prev, if (wDp < 170) View.GONE else View.VISIBLE)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            rv.setViewLayoutWidth(R.id.widget_cover, coverDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewLayoutHeight(R.id.widget_cover, coverDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }
        if (size != Size.TINY) {
            val titleSp = (if (Build.VERSION.SDK_INT >= 31) coverDp * 0.26f else 15f).coerceIn(14f, 22f)
            rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
            rv.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, titleSp * 0.82f)
        }
        val look = settings.widgetLyricLook()
        val metrics = if (big) lyricMetrics(context, size, wDp, hDp, look, ws, coverDp) else null
        if (metrics != null) {
            // each row is either text or a picture of the same text drawn with the player's typeface
            val exact = exactNow(ws, look)
            val extra = if (metrics.five) View.VISIBLE else View.GONE
            val side = when (look.align) {
                app.malachite.player.data.LyricsAlign.START -> Gravity.START
                app.malachite.player.data.LyricsAlign.CENTER -> Gravity.CENTER_HORIZONTAL
                app.malachite.player.data.LyricsAlign.END -> Gravity.END
            }
            for (p in 0..1) {
                for (k in 0..4) {
                    val shown = if (k == 0 || k == 4) extra else View.VISIBLE
                    rv.setViewVisibility(LINE_IDS[p][k], if (exact) View.GONE else shown)
                    rv.setViewVisibility(IMAGE_IDS[p][k], if (exact) shown else View.GONE)
                    rv.setTextViewTextSize(LINE_IDS[p][k], TypedValue.COMPLEX_UNIT_SP, if (k == 2) metrics.curSp else metrics.sideSp)
                }
                rv.setInt(PAGE_IDS[p], "setGravity", Gravity.CENTER_VERTICAL or side)
            }
        }
        rv.setInt(R.id.widget_bg, "setColorFilter", c.bg or 0xFF000000.toInt())
        rv.setInt(R.id.widget_bg, "setImageAlpha", (ws.backgroundAlpha.coerceIn(0f, 1f) * 255).toInt())

        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        rv.setOnClickPendingIntent(R.id.widget_root, open)

        // cover
        if (ws.showCover) {
            rv.setViewVisibility(R.id.widget_cover, View.VISIBLE)
            val src = cover
            val bmp = if (src != null) {
                if (roundedFor !== src) { roundedCover = rounded(src); roundedFor = src }
                roundedCover
            } else placeholder(context, c)
            rv.setImageViewBitmap(R.id.widget_cover, bmp)
        } else rv.setViewVisibility(R.id.widget_cover, View.GONE)

        // play: an accent circle with the icon, as in the player
        rv.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setInt(R.id.widget_play, "setColorFilter", c.onAccent)
        rv.setInt(R.id.widget_play_bg, "setColorFilter", c.accent)
        rv.setOnClickPendingIntent(R.id.widget_play, pi(context, ACTION_PLAY_PAUSE, 10))

        if (size == Size.TINY) return rv

        rv.setTextViewText(R.id.widget_title, s.title)
        rv.setTextColor(R.id.widget_title, c.text)
        rv.setTextViewText(R.id.widget_artist, s.artist)
        rv.setTextColor(R.id.widget_artist, c.sub)
        rv.setOnClickPendingIntent(R.id.widget_next, pi(context, ACTION_NEXT, 11))
        rv.setInt(R.id.widget_next, "setColorFilter", c.text)
        if (size != Size.SMALL) {
            rv.setOnClickPendingIntent(R.id.widget_prev, pi(context, ACTION_PREV, 12))
            rv.setInt(R.id.widget_prev, "setColorFilter", c.text)
            rv.setImageViewResource(R.id.widget_fav, if (s.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            rv.setInt(R.id.widget_fav, "setColorFilter", if (s.favorite) c.accent else c.sub)
            rv.setOnClickPendingIntent(R.id.widget_fav, pi(context, ACTION_FAVORITE, 13))
        }
        if (size == Size.LARGE || size == Size.HUGE) {
            val l = lyrics.takeIf { lyricsPath == s.path }
            val show = ws.showLyrics && l != null && l.lines.isNotEmpty()
            // Without lyrics the buttons stay at the bottom, where they always are: the cover fills
            // the room between (it used to collapse, the buttons jumped up under a big empty space).
            // Without a cover either, the song and the buttons sit in the middle.
            val bigCover = !show && ws.showCover
            rv.setViewVisibility(R.id.widget_lyrics, if (show || bigCover) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.widget_big_cover, if (bigCover) View.VISIBLE else View.GONE)
            rv.setInt(R.id.widget_content, "setGravity", if (show || bigCover) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START)
            if (bigCover) {
                rv.setImageViewBitmap(R.id.widget_big_cover, bigCoverBitmap(context, c))
                // one cover is enough: the small one in the song row steps aside
                rv.setViewVisibility(R.id.widget_cover, View.GONE)
                PAGE_IDS.forEach { rv.setViewVisibility(it, View.INVISIBLE) }
            }
            if (show && l != null && metrics != null) {
                val page = shownPage[id] ?: 0
                // (the speed comes from the snapshot: asking the player here, off its thread, throws –
                // that silently stopped every full update for songs with lyrics)
                val line = if (l.synced) lineAt(l, s.position, s.speed, s.playing) else -1
                fillLyrics(context, rv, page, l, line, c, s.position - l.offsetMs + FILL_AHEAD_MS, metrics, look)
                rv.setViewVisibility(PAGE_IDS[page], View.VISIBLE)
                rv.setViewVisibility(PAGE_IDS[1 - page], View.INVISIBLE)
            }
        }
        if (size == Size.HUGE) {
            val showSeek = ws.showSeekBar
            rv.setViewVisibility(R.id.widget_seek_row, if (showSeek) View.VISIBLE else View.GONE)
            if (showSeek) {
                setProgress(rv, s)
                rv.setTextColor(R.id.widget_time, c.sub)
                rv.setTextColor(R.id.widget_duration, c.sub)
                if (Build.VERSION.SDK_INT >= 31) {
                    rv.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(c.accent))
                    rv.setColorStateList(R.id.widget_progress, "setProgressBackgroundTintList", ColorStateList.valueOf(ColorUtils.setAlphaComponent(c.text, 0x40)))
                }
                for (z in 0 until SEEK_ZONES) {
                    rv.setOnClickPendingIntent(SEEK_IDS[z], pi(context, ACTION_SEEK, 100 + z, (z + 0.5f) / SEEK_ZONES))
                }
            }
        }
        return rv
    }

    // ------------------------------------------------------------------ lyric lines

    private val PAGE_IDS = intArrayOf(R.id.widget_page0, R.id.widget_page1)
    private val LINE_IDS = arrayOf(
        intArrayOf(R.id.widget_l0_p2, R.id.widget_l0_p1, R.id.widget_l0_cur, R.id.widget_l0_n1, R.id.widget_l0_n2),
        intArrayOf(R.id.widget_l1_p2, R.id.widget_l1_p1, R.id.widget_l1_cur, R.id.widget_l1_n1, R.id.widget_l1_n2),
    )
    /** The same rows as pictures (see [LyricPainter]). */
    private val IMAGE_IDS = arrayOf(
        intArrayOf(R.id.widget_i0_p2, R.id.widget_i0_p1, R.id.widget_i0_cur, R.id.widget_i0_n1, R.id.widget_i0_n2),
        intArrayOf(R.id.widget_i1_p2, R.id.widget_i1_p1, R.id.widget_i1_cur, R.id.widget_i1_n1, R.id.widget_i1_n2),
    )
    /** How many lines of text a row may take: far rows one, the neighbours two, the line being sung three. */
    private val ROW_LINES = intArrayOf(1, 2, 3, 2, 1)

    /** The size of the cover in the song row: it grows with the widget. */
    private fun coverDpFor(size: Size, wDp: Int, hDp: Int): Int = when (size) {
        Size.TINY -> (minOf(wDp / 2 - 12, hDp - 12)).coerceIn(32, 72)
        Size.SMALL -> (hDp - 20).coerceIn(40, 72)
        // song row + button row must both fit: 20 dp of padding and about 54 dp of buttons
        Size.MEDIUM -> (hDp - 20 - 56).coerceIn(36, 72)
        else -> bigCoverDp(wDp, hDp)
    }

    private fun bigCoverDp(wDp: Int, hDp: Int) = (minOf(wDp, hDp) * 0.2f).toInt().coerceIn(52, 112)

    /** Sizes of the lyric rows of one widget: they follow the room the widget has. */
    private class LyricMetrics(val curSp: Float, val sideSp: Float, val five: Boolean, val maxWidthPx: Int, val areaPx: Int)

    private fun lyricMetrics(
        context: Context, size: Size, wDp: Int, hDp: Int, look: LyricLook,
        ws: app.malachite.player.data.WidgetSettings, coverDp: Int,
    ): LyricMetrics {
        // the space left for the lyrics decides their size and how many lines fit
        val area = hDp - (if (Build.VERSION.SDK_INT >= 31) bigCoverDp(wDp, hDp) else 64) - 32 - 64 - (if (size == Size.HUGE) 34 else 0)
        val cur = (minOf(area / 6.5f, wDp / 12f).coerceIn(16f, 32f) * look.textScale.coerceIn(0.6f, 1.5f)).coerceIn(12f, 42f)
        // as in the player: the other lines are a little smaller when "enlarge the current line" is on
        val side = if (look.scaleActive) cur * 0.9f else cur
        val padding = if (size == Size.HUGE) 32 else 28
        val width = ((wDp - padding) * context.resources.displayMetrics.density).toInt().coerceAtLeast(160)
        // The height the rows really have: the widget minus its padding, the song row, the
        // buttons (60 dp), the seek bar and the lyric frame's own padding (8 dp). A few dp are
        // kept in reserve: launchers round the size they report.
        val fontScale = context.resources.configuration.fontScale
        val titleSp = (if (Build.VERSION.SDK_INT >= 31) coverDp * 0.26f else 15f).coerceIn(14f, 22f)
        val songText = titleSp * 2.2f * fontScale + 1
        val songRow = if (ws.showCover) maxOf((if (Build.VERSION.SDK_INT >= 31) coverDp else 56).toFloat(), songText) else songText
        val seek = if (size == Size.HUGE && ws.showSeekBar) 34 else 0
        val roomDp = hDp - (if (size == Size.HUGE) 32 else 28) - songRow - 8 - 60 - seek - 6
        val areaPx = (roomDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(0)
        return LyricMetrics(cur, side, area >= cur * 8f, width, areaPx)
    }

    /** The extra space around each row in the layout (its paddings), in dp. */
    private val ROW_PAD_DP = intArrayOf(0, 2, 12, 2, 0)

    /**
     * How many text lines each row gets so that all rows fit the room: the line being sung
     * first, then the next one, the previous one, the far ones. A row that does not fit even in
     * one line stays empty. (A row that did not fit used to be squeezed by the launcher, and its
     * picture was drawn tiny.) [height] gives a row's height in px for a number of lines.
     */
    private fun fitRows(m: LyricMetrics, density: Float, onlyCurrent: Boolean, height: (Int, Int) -> Int): IntArray {
        val lines = IntArray(5)
        var room = m.areaPx
        for (k in intArrayOf(2, 3, 1, 4, 0)) {
            if ((k == 0 || k == 4) && !m.five) continue
            val pad = (ROW_PAD_DP[k] * density).toInt()
            var n = ROW_LINES[k]
            while (n > 1 && height(k, n) + pad > room) n--
            if (k != 2 && height(k, n) + pad > room) n = 0
            lines[k] = n
            if (n > 0) room -= height(k, n) + pad
            if (onlyCurrent) break
        }
        return lines
    }

    // How the rows of the current line were fitted: a word taking its colour (several times a
    // second) reuses it instead of measuring the lines again. Only the io thread uses it.
    private var fitKey: Any? = null
    private var fitLines: IntArray? = null

    private fun cachedFit(key: Any, onlyCurrent: Boolean, compute: () -> IntArray): IntArray {
        val known = fitLines
        if (onlyCurrent && known != null && fitKey == key) return known
        return compute().also { if (!onlyCurrent) { fitKey = key; fitLines = it } }
    }

    @Volatile private var bigCoverFor: String? = null
    @Volatile private var bigCover: Bitmap? = null

    /** The cover drawn big (for a song without lyrics), sharper than the small one. */
    private fun bigCoverBitmap(context: Context, c: Palette3): Bitmap {
        val path = coverPath
        if (bigCoverFor != path || bigCover == null) {
            val graph = MalachiteApp.graph
            val local = if (graph.library.onlineItem(path) == null) path?.let { graph.library.file(it) } else null
            val src = local?.let { CoverLoader.load(it, 512) } ?: cover
            bigCover = src?.let { rounded(it) }
            bigCoverFor = path
        }
        return bigCover ?: placeholder(context, c)
    }

    /** The line as the widget shows it while it is swept: its words, or its text when the words are not timed. */
    private fun sweepText(line: app.malachite.core.lyrics.LyricLine): String =
        if (line.words.isNotEmpty()) line.words.joinToString(" ") { it.text } else line.text

    /** The widget cannot show a 0.14-second flash: "fast" colours a word at once. */
    private fun widgetFill(look: LyricLook) = if (look.fill == WordFill.SMOOTH || look.fill == WordFill.SWEEP) look.fill else WordFill.INSTANT

    // io thread only: a look whose pictures could not be drawn falls back to plain text
    private var paintFailedFor: LyricLook? = null

    private fun exactNow(ws: app.malachite.player.data.WidgetSettings, look: LyricLook) = ws.exactText && paintFailedFor != look

    /** The built-in font families by name: what a launcher's own text can follow. */
    private fun fontFamilyName(font: FontChoice): String? = when (font) {
        FontChoice.SANS -> "sans-serif"
        FontChoice.SERIF -> "serif"
        FontChoice.MONO -> "monospace"
        FontChoice.CURSIVE -> "cursive"
        else -> null
    }

    private fun styled(text: CharSequence, family: String?, bold: Boolean): CharSequence {
        if (text.isEmpty() || (family == null && !bold)) return text
        val out = SpannableString(text)
        if (family != null) out.setSpan(TypefaceSpan(family), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (bold) out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return out
    }

    /**
     * The line being sung, the way the player shows it: with word timing the sung words take the
     * "sung" colour (smoothly or at once, as set) and the rest wait in a dimmer tone; without it
     * the whole line has the line colour. [posMs] is the position on the lyrics' own timeline.
     */
    private fun currentLineText(l: Lyrics, i: Int, posMs: Long, c: Palette3, look: LyricLook): CharSequence {
        val line = l.lines.getOrNull(i)
        val plain = line?.text ?: if (l.synced) "♪" else ""
        if (line == null || !l.synced || !MalachiteApp.graph.settings.value.widget.wordFill) return plain
        val base = look.activeColor?.toInt() ?: c.text
        val sung = look.sungColor?.toInt() ?: c.accent
        val waiting = ColorUtils.setAlphaComponent(base, 0x8C)
        val mode = widgetFill(look)
        if (mode == WordFill.SWEEP) {
            // the whole line, letter after letter at an even pace, with a soft edge
            val shown = sweepText(line)
            val reach = LineSweep.reach(shown, line, posMs, l.lines.getOrNull(i + 1)?.startMs, l.switchMs(i, LEAD_MS))
            val sb = SpannableStringBuilder(shown)
            for (j in shown.indices) {
                val t = LineSweep.mix(j, shown.length, reach, SWEEP_SOFT_LETTERS)
                sb.setSpan(ForegroundColorSpan(ColorUtils.blendARGB(waiting, sung, t)), j, j + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }
        if (line.words.isEmpty()) return plain
        val next = l.lines.getOrNull(i + 1)?.startMs
        val sb = SpannableStringBuilder()
        line.words.forEachIndexed { k, w ->
            if (k > 0) sb.append(' ')
            val from = sb.length
            sb.append(w.text)
            val f = wordFillProgress(line, k, posMs, next, mode, l.switchMs(i, LEAD_MS))
            sb.setSpan(ForegroundColorSpan(ColorUtils.blendARGB(waiting, sung, f)), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /**
     * Writes the lines around [line] into one page, looking like the lyrics inside the player:
     * same typeface, weight, alignment, colours and dimming. With [onlyCurrent] just the line
     * being sung is renewed (a word got its colour).
     */
    private fun fillLyrics(
        context: Context, rv: RemoteViews, page: Int, l: Lyrics, line: Int, c: Palette3, posMs: Long,
        m: LyricMetrics, look: LyricLook, onlyCurrent: Boolean = false,
    ) {
        // a text without timing simply starts in the middle row
        val i = if (l.synced) line else 0
        val ws = MalachiteApp.graph.settings.value.widget
        val dim = look.inactiveAlpha.coerceIn(0.15f, 0.9f)
        // as in the player: lines already sung are a little dimmer than the ones still to come
        val alphas = floatArrayOf(dim * 0.8f, dim * 0.8f, 1f, dim, dim)
        // the line being sung: the player's own colour for it; without word timing it can take the accent
        val lineColor = look.activeColor?.toInt()
            ?: if (ws.accentCurrentLine && l.synced) (look.sungColor?.toInt() ?: c.accent) else c.text
        fun text(k: Int): CharSequence = if (k == 2) currentLineText(l, i, posMs, c, look) else l.lines.getOrNull(i + k - 2)?.text.orEmpty()
        fun color(k: Int) = if (k == 2) lineColor else ColorUtils.setAlphaComponent(c.text, (alphas[k] * 255).toInt())
        // favourite lines are marked the same way as in the player
        val settings = MalachiteApp.graph.settings.value
        val marks = lyricsPath?.let { MalachiteApp.graph.library.data.value.user(it).markedLines.toSet() }.orEmpty()
        val markKeys = if (marks.isEmpty()) emptyList() else app.malachite.core.lyrics.lineMarkKeys(l.lines.map { it.text })
        fun marked(k: Int) = marks.isNotEmpty() && app.malachite.core.lyrics.isLineMarked(markKeys.getOrElse(i + k - 2) { "" }, marks)
        val markColor = settings.lineMarkColor?.toInt() ?: c.accent
        val rows = if (onlyCurrent) 2..2 else 0..4

        if (exactNow(ws, look)) {
            val drawn = runCatching {
                val typeface = LyricPainter.typeface(look)
                val dm = context.resources.displayMetrics
                fun px(k: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (k == 2) m.curSp else m.sideSp, dm)
                fun mark(k: Int) = if (marked(k)) LineMarkPaint(settings.lineMarkStyle, markColor, settings.lineMarkThickness * dm.density) else null
                val texts = arrayOfNulls<CharSequence>(5)
                fun textOf(k: Int) = texts[k] ?: text(k).also { texts[k] = it }
                val fit = cachedFit(listOf("exact", System.identityHashCode(l), i, m.curSp, m.sideSp, m.areaPx, m.maxWidthPx, look), onlyCurrent) {
                    fitRows(m, dm.density, onlyCurrent) { k, n -> LyricPainter.measure(textOf(k), typeface, px(k), m.maxWidthPx, n, mark(k) != null) }
                }
                for (k in rows) {
                    if ((k == 0 || k == 4) && !m.five) continue
                    val bmp = if (fit[k] == 0) LyricPainter.empty(dm.densityDpi)
                    else LyricPainter.draw(textOf(k), color(k), typeface, px(k), m.maxWidthPx, fit[k], look.align, dm.densityDpi, mark(k))
                    rv.setImageViewBitmap(IMAGE_IDS[page][k], bmp)
                }
            }
            if (drawn.isSuccess) return
            // the next full update shows plain text instead
            paintFailedFor = look
            AppLog.e("widget", "Строки текста не нарисовались, виджет покажет их обычным текстом", drawn.exceptionOrNull())
            android.os.Handler(android.os.Looper.getMainLooper()).post { requestUpdate(context) }
            return
        }
        val family = fontFamilyName(look.font)
        val gravity = when (look.align) {
            app.malachite.player.data.LyricsAlign.START -> Gravity.START or Gravity.CENTER_VERTICAL
            app.malachite.player.data.LyricsAlign.CENTER -> Gravity.CENTER
            app.malachite.player.data.LyricsAlign.END -> Gravity.END or Gravity.CENTER_VERTICAL
        }
        // the same fitting for plain text rows: how many lines each may take
        val dm = context.resources.displayMetrics
        val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(family, if (look.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val fit = cachedFit(listOf("text", System.identityHashCode(l), i, m.curSp, m.sideSp, m.areaPx, m.maxWidthPx, look), onlyCurrent) { fitRows(m, dm.density, onlyCurrent) { k, n ->
            measurePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (k == 2) m.curSp else m.sideSp, dm)
            val t = text(k).toString()
            if (t.isBlank()) (measurePaint.fontSpacing).toInt()
            else StaticLayout.Builder.obtain(t, 0, t.length, measurePaint, m.maxWidthPx).setMaxLines(n).setEllipsize(TextUtils.TruncateAt.END).build().height
        } }
        for (k in rows) {
            if ((k == 0 || k == 4) && !m.five) continue
            rv.setInt(LINE_IDS[page][k], "setMaxLines", fit[k].coerceAtLeast(1))
            rv.setViewVisibility(LINE_IDS[page][k], if (fit[k] == 0) View.INVISIBLE else View.VISIBLE)
            val shown = styled(text(k), family, look.bold).let { t ->
                if (!marked(k) || t.isEmpty()) t
                else SpannableStringBuilder(t).apply {
                    if (settings.lineMarkStyle != app.malachite.player.data.LineMarkStyle.UNDERLINE) {
                        setSpan(android.text.style.BackgroundColorSpan(ColorUtils.setAlphaComponent(markColor, 0x48)), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                    if (settings.lineMarkStyle != app.malachite.player.data.LineMarkStyle.MARKER) {
                        setSpan(android.text.style.UnderlineSpan(), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                }
            }
            rv.setTextViewText(LINE_IDS[page][k], shown)
            rv.setTextColor(LINE_IDS[page][k], color(k))
            rv.setInt(LINE_IDS[page][k], "setGravity", gravity)
        }
    }

    private fun setProgress(rv: RemoteViews, s: Snapshot) {
        val max = 1000
        val prog = if (s.duration > 0) (s.position * max / s.duration).toInt() else 0
        rv.setProgressBar(R.id.widget_progress, max, prog, false)
        rv.setTextViewText(R.id.widget_time, fmt(s.position))
        rv.setTextViewText(R.id.widget_duration, fmt(s.duration))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private val SEEK_IDS = intArrayOf(
        R.id.seek_0, R.id.seek_1, R.id.seek_2, R.id.seek_3, R.id.seek_4, R.id.seek_5, R.id.seek_6, R.id.seek_7,
        R.id.seek_8, R.id.seek_9, R.id.seek_10, R.id.seek_11, R.id.seek_12, R.id.seek_13, R.id.seek_14,
        R.id.seek_15, R.id.seek_16, R.id.seek_17, R.id.seek_18, R.id.seek_19,
    )
}

/**
 * Draws a lyric line the way the player does – with the very same typeface (a font from the
 * user's file included), weight and colours – into a picture the widget can show. A launcher
 * draws widget text itself and knows only the system fonts; a picture has no such limit.
 */
private object LyricPainter {
    private var key: Any? = null
    private var cached: Typeface? = null

    fun typeface(look: LyricLook): Typeface {
        val k = listOf(look.font, look.fontFile, look.bold)
        cached?.let { if (key == k) return it }
        val base = when (look.font) {
            FontChoice.SYSTEM -> Typeface.DEFAULT
            FontChoice.SANS -> Typeface.SANS_SERIF
            FontChoice.SERIF -> Typeface.SERIF
            FontChoice.MONO -> Typeface.MONOSPACE
            FontChoice.CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
            FontChoice.CUSTOM -> runCatching {
                java.io.File(look.fontFile ?: "").takeIf { it.isFile }?.let { Typeface.createFromFile(it) }
            }.getOrNull() ?: Typeface.DEFAULT
        }
        // the player shows lyrics bold or medium
        val tf = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, if (look.bold) 700 else 500, false)
        else Typeface.create(base, if (look.bold) Typeface.BOLD else Typeface.NORMAL)
        key = k
        cached = tf
        return tf
    }

    private fun layoutOf(text: CharSequence, paint: TextPaint, maxWidth: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(align)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** The height in px [draw] gives the same text with the same size and number of lines. */
    fun measure(text: CharSequence, typeface: Typeface, sizePx: Float, maxWidth: Int, maxLines: Int, marked: Boolean): Int {
        val rowHeight = (sizePx * 1.3f).toInt().coerceAtLeast(1)
        if (text.isBlank()) return rowHeight
        val pad = if (marked) (sizePx * 0.18f).toInt() else 0
        synchronized(measurePaint) {
            measurePaint.typeface = typeface
            measurePaint.textSize = sizePx
            return maxOf(layoutOf(text, measurePaint, maxWidth, maxLines).height + (sizePx * 0.12f).toInt() + pad, rowHeight)
        }
    }

    /** A row left empty (it did not fit). */
    fun empty(densityDpi: Int): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { density = densityDpi }

    /**
     * [text] may carry colour spans (the sung words). The picture is as wide as the text itself,
     * at most [maxWidth]; longer text wraps up to [maxLines] lines and ends with "…".
     */
    fun draw(
        text: CharSequence, color: Int, typeface: Typeface, sizePx: Float, maxWidth: Int, maxLines: Int,
        align: app.malachite.player.data.LyricsAlign, densityDpi: Int, mark: LineMarkPaint? = null,
    ): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = sizePx
            this.color = color
        }
        // every row is at least one line of the player's height (1.3 of the text size)
        val rowHeight = (sizePx * 1.3f).toInt().coerceAtLeast(1)
        if (text.isBlank()) return Bitmap.createBitmap(1, rowHeight, Bitmap.Config.ARGB_8888).apply { density = densityDpi }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(
                when (align) {
                    app.malachite.player.data.LyricsAlign.START -> Layout.Alignment.ALIGN_NORMAL
                    app.malachite.player.data.LyricsAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    app.malachite.player.data.LyricsAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        var left = Float.MAX_VALUE
        var right = 0f
        for (n in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(n))
            right = maxOf(right, layout.getLineRight(n))
        }
        if (left > right) { left = 0f; right = 1f }
        // room around the words for the marker of a favourite line
        val pad = if (mark != null) (sizePx * 0.18f).toInt() else 0
        val width = (kotlin.math.ceil(right - left).toInt() + 2 + pad * 2).coerceIn(1, maxWidth + 2 + pad * 2)
        val height = maxOf(layout.height + (sizePx * 0.12f).toInt() + pad, rowHeight)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // the launcher must show it pixel for pixel
        out.density = densityDpi
        val canvas = Canvas(out)
        canvas.translate(1f + pad - left, (height - layout.height) / 2f)
        if (mark != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for (n in 0 until layout.lineCount) {
                val l = layout.getLineLeft(n)
                val r = layout.getLineRight(n)
                if (r <= l) continue
                val top = layout.getLineTop(n).toFloat()
                val bottom = layout.getLineBottom(n).toFloat()
                if (mark.style != app.malachite.player.data.LineMarkStyle.UNDERLINE) {
                    p.color = ColorUtils.setAlphaComponent(mark.color, 0x48)
                    val h = bottom - top
                    canvas.drawRoundRect(RectF(l - pad, top + h * 0.12f, r + pad, top + h * 0.92f), pad.toFloat(), pad.toFloat(), p)
                }
                if (mark.style != app.malachite.player.data.LineMarkStyle.MARKER) {
                    p.color = mark.color
                    val y = layout.getLineBaseline(n) + mark.thicknessPx * 1.2f
                    canvas.drawRoundRect(RectF(l, y, r, y + mark.thicknessPx), mark.thicknessPx / 2, mark.thicknessPx / 2, p)
                }
            }
        }
        layout.draw(canvas)
        return out
    }
}

/** How a favourite lyric line is marked in a picture row. */
private class LineMarkPaint(val style: app.malachite.player.data.LineMarkStyle, val color: Int, val thicknessPx: Float)
