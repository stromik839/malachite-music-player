package app.malachite.player.data

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/**
 * The detailed log ("how smooth is it really"): with it on, the log gets every few seconds what
 * the screen did – how many frames were drawn, how many frames a second while something moved,
 * at what refresh rate the screen ran, how many frames came late and what the worst one spent
 * its time on (measuring, drawing, the graphics chip). Also which screen was open, slow pieces of
 * work on the main thread, the display modes and the memory in use. Nothing runs while it is off.
 */
object FrameWatch {
    private const val REPORT_EVERY_MS = 6_000L
    private const val SLOW_MESSAGE_MS = 64L
    private const val MAX_SLOW_MESSAGES = 150
    private const val MEMORY_EVERY_MS = 60_000L

    @Volatile private var on = false
    private var window: Window? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** The screen being shown (set by the app's frame), for the reports. */
    @Volatile var screen: String = ""
        set(value) {
            if (value == field) return
            field = value
            if (on) AppLog.i("ui", "экран: $value")
        }

    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        val w = window
        if (value) {
            AppLog.i("frames", "Подробный журнал включён")
            if (w != null) start(w)
            watchMainThread(true)
        } else {
            if (w != null) stop(w)
            watchMainThread(false)
        }
    }

    /** The app's window came on the screen. */
    fun attach(w: Window) {
        window = w
        if (on) start(w)
    }

    /** The app's window left the screen. */
    fun detach(w: Window) {
        if (window === w) { stop(w); window = null }
    }

    // ------------------------------------------------------------------ frames

    private class Period {
        var started = SystemClock.uptimeMillis()
        var frames = 0
        var late = 0
        var veryLate = 0
        var moving = 0
        var movingNs = 0L
        var lastVsync = 0L
        var worstNs = 0L
        var worst: LongArray? = null
        val intervals = ArrayList<Long>()
    }

    private var period = Period()
    private var lastMemory = 0L
    /** The screen's refresh rate when the window came (frames before Android 12 do not tell it). */
    @Volatile private var refreshHz = 60f
    private val ADDRESS = Regex("\\{[0-9a-f]+\\}|@[0-9a-f]+")

    @Synchronized
    private fun start(w: Window) {
        if (listener != null) return
        logDisplay(w)
        val t = HandlerThread("malachite-frames", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        period = Period()
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> runCatching { frame(metrics) } }
        listener = l
        runCatching { w.addOnFrameMetricsAvailableListener(l, h) }
    }

    @Synchronized
    private fun stop(w: Window) {
        val l = listener ?: return
        runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        listener = null
        handler?.post { report(force = true) }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun frame(m: FrameMetrics) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val p = period
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        val vsync = m.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val budget = frameBudgetNs(m)
        p.frames++
        if (total > budget) p.late++
        if (total > budget * 2) p.veryLate++
        if (p.lastVsync > 0) {
            val gap = vsync - p.lastVsync
            // frames one after another: something is moving (a gap means the screen was still)
            if (gap in 1..(budget * 3)) {
                p.moving++
                p.movingNs += gap
                if (p.intervals.size < 2000) p.intervals += gap
            }
        }
        p.lastVsync = vsync
        if (total > p.worstNs) {
            p.worstNs = total
            p.worst = longArrayOf(
                m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                m.getMetric(FrameMetrics.ANIMATION_DURATION),
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                m.getMetric(FrameMetrics.DRAW_DURATION),
                m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else -1L,
                m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
            )
        }
        report(force = false)
    }

    /** How long one frame may take: the screen's own interval (from the frame itself on Android 12+). */
    private fun frameBudgetNs(m: FrameMetrics): Long {
        if (Build.VERSION.SDK_INT >= 31) {
            val d = m.getMetric(FrameMetrics.DEADLINE)
            if (d in 4_000_000L..50_000_000L) return d
        }
        return (1_000_000_000.0 / refreshHz.coerceIn(30f, 240f)).toLong()
    }

    private fun report(force: Boolean) {
        val p = period
        val now = SystemClock.uptimeMillis()
        if (!force && now - p.started < REPORT_EVERY_MS) return
        period = Period()
        if (p.frames >= 5) {
            val secs = (now - p.started) / 1000.0
            val fps = if (p.movingNs > 0) p.moving * 1e9 / p.movingNs else 0.0
            val typical = p.intervals.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
            val hz = if (typical > 0) 1e9 / typical else 0.0
            val w = p.worst
            val parts = if (w == null) "" else listOfNotNull(
                "ввод ${ms(w[0])}", "анимация ${ms(w[1])}", "разметка ${ms(w[2])}", "рисование ${ms(w[3])}",
                "отправка ${ms(w[4])}", if (w[5] >= 0) "GPU ${ms(w[5])}" else null, "ожидание ${ms(w[6])}",
            ).joinToString(", ")
            AppLog.i(
                "frames",
                "«$screen»: ${p.frames} кадров за ${"%.1f".format(secs)} с; в движении ${"%.0f".format(fps)} к/с" +
                    (if (hz > 0) ", кадр обычно через ${"%.1f".format(typical / 1e6)} мс (≈${"%.0f".format(hz)} Гц)" else "") +
                    "; опоздали ${p.late} (${p.late * 100 / p.frames}%), сильно ${p.veryLate}; худший ${ms(p.worstNs)} мс" +
                    (if (parts.isNotEmpty()) " ($parts)" else ""),
            )
        }
        if (now - lastMemory >= MEMORY_EVERY_MS) {
            lastMemory = now
            val rt = Runtime.getRuntime()
            AppLog.i(
                "frames",
                "память: Java ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} из ${rt.maxMemory() / 1_048_576} МБ, " +
                    "native ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} МБ, потоков ${Thread.activeCount()}",
            )
        }
    }

    private fun ms(ns: Long) = "%.0f".format(ns / 1e6)

    /** The display modes and the one asked for: does the phone really give the app 90 / 120 Hz? */
    private fun logDisplay(w: Window) {
        runCatching {
            @Suppress("DEPRECATION")
            val d = w.decorView.display ?: w.windowManager.defaultDisplay ?: return
            refreshHz = d.refreshRate
            val modes = d.supportedModes.joinToString(", ") { "${it.modeId}: ${it.physicalWidth}×${it.physicalHeight} ${"%.0f".format(it.refreshRate)} Гц" }
            val asked = w.attributes.preferredDisplayModeId
            AppLog.i(
                "frames",
                "экран сейчас ${"%.0f".format(d.refreshRate)} Гц (режим ${d.mode.modeId}); режимы: $modes; " +
                    "приложение просит режим ${if (asked == 0) "по выбору системы" else asked.toString()}; Android ${Build.VERSION.SDK_INT}",
            )
        }
    }

    // ------------------------------------------------------------------ the main thread

    private var slowMessages = 0
    private var dispatchStart = 0L
    private var dispatchWhat = ""

    /**
     * Each piece of work of the main thread is timed; the long ones go into the log with what
     * they were ("Choreographer" is drawing a frame, "MediaSession" the notification, …).
     */
    private fun watchMainThread(enable: Boolean) {
        val looper = Looper.getMainLooper()
        if (!enable) { looper.setMessageLogging(null); return }
        slowMessages = 0
        looper.setMessageLogging { line ->
            if (line.startsWith(">")) {
                dispatchStart = SystemClock.uptimeMillis()
                dispatchWhat = line
            } else if (line.startsWith("<") && dispatchStart > 0) {
                val took = SystemClock.uptimeMillis() - dispatchStart
                dispatchStart = 0
                if (took >= SLOW_MESSAGE_MS && slowMessages < MAX_SLOW_MESSAGES) {
                    slowMessages++
                    // ">>>>> Dispatching to Handler (android.view.Choreographer$FrameHandler) {…} android.view.Choreographer$FrameDisplayEventReceiver@…: 0"
                    val what = dispatchWhat.substringAfter("Handler (", dispatchWhat).replace(ADDRESS, "").take(200)
                    AppLog.i("main", "главный поток занят $took мс: $what")
                    if (slowMessages == MAX_SLOW_MESSAGES) AppLog.i("main", "дальше долгие задачи не записываются до следующего запуска")
                }
            }
        }
    }
}
